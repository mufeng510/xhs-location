package com.mufeng.xhs.location.vpn

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * HTTP CONNECT 代理客户端。
 *
 * 建立握手：向代理发送 `CONNECT host:port HTTP/1.1`，代理返回 `HTTP/1.1 200` 后，
 * 双方开始透传原始 TCP 字节流。接口与 [Socks5Client] 保持一致，
 * 供 VPN 转发层统一调度。
 */
class HttpProxyClient private constructor(
    private val proxyServer: String,
    private val proxyPort: Int,
    private val socketTimeout: Int = 10000
) : ProxyTunnel {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    companion object {
        fun create(proxyServer: String, proxyPort: Int): HttpProxyClient =
            HttpProxyClient(proxyServer, proxyPort)
    }

    /**
     * 通过 HTTP CONNECT 代理连接到目标主机。
     */
    override fun connect(targetHost: String, targetPort: Int) {
        socket = Socket()
        socket!!.connect(InetSocketAddress(proxyServer, proxyPort), socketTimeout)
        socket!!.soTimeout = socketTimeout
        socket!!.tcpNoDelay = true

        input = socket!!.getInputStream()
        output = socket!!.getOutputStream()

        val request = "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
            "Host: $targetHost:$targetPort\r\n" +
            "User-Agent: vlocation/1.0\r\n" +
            "Proxy-Connection: keep-alive\r\n\r\n"
        output!!.write(request.toByteArray(Charsets.US_ASCII))
        output!!.flush()

        // 读取响应头（直到 \r\n\r\n），首行必须为 2xx
        val header = readResponseHeader(maxBytes = 8192)
        val statusLine = header.lineSequence().firstOrNull() ?: ""
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        if (code < 200 || code >= 300) {
            throw IOException("HTTP CONNECT failed: $statusLine")
        }
    }

    /** 从代理隧道读取数据 */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input!!.read(buffer, offset, length)

    /** 向代理隧道写入数据 */
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output!!.write(buffer, offset, length)
        output!!.flush()
    }

    /** 关闭连接 */
    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
    }

    private fun readResponseHeader(maxBytes: Int): String {
        val buffer = ByteArray(maxBytes)
        var total = 0
        var matched = 0 // 已匹配 \r\n\r\n 的长度
        val marker = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (total < maxBytes) {
            val n = input!!.read(buffer, total, maxBytes - total)
            if (n < 0) throw IOException("Proxy closed connection while reading CONNECT response")
            for (i in total until total + n) {
                if (buffer[i] == marker[matched]) {
                    matched++
                    if (matched == 4) {
                        return String(buffer, 0, total + n, Charsets.US_ASCII)
                    }
                } else {
                    matched = if (buffer[i] == marker[0]) 1 else 0
                }
            }
            total += n
        }
        throw IOException("CONNECT response header too large")
    }
}
