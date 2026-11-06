package com.mufeng.xhs.location.vpn

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 经代理建立隧道（SOCKS5 / HTTP CONNECT），用于代理探测与检测。
 *
 * 与 [ProxyTunnel] 的区别：这里返回裸 Socket，调用方可继续做 TLS 包装
 * （XHS 可达性测试）或直接走明文 HTTP（IP 归属地检测）。
 */
object ProxyHandshake {

    /**
     * 经代理隧道连接 targetHost:targetPort，返回已完成握手的 Socket（未加密）。
     * @throws IOException 连接失败/被代理拒绝
     */
    fun openTunnel(proxy: BuiltinProxies.BuiltinProxy, targetHost: String, targetPort: Int, timeoutMs: Int = 8000): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(proxy.server, proxy.port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true

        if (proxy.type == ProxyType.HTTP) {
            httpConnect(socket, targetHost, targetPort)
        } else {
            socks5Connect(socket, targetHost, targetPort)
        }
        return socket
    }

    /**
     * 经代理隧道建立到 targetHost:targetPort 的 TLS 连接（带 SNI），返回已握手的 SSLSocket。
     * 探测用途：免费代理常有 MITM 改写证书，证书校验失败会误判为"小红书不可达"，故探测不校验证书。
     * @throws IOException 隧道失败或 TLS 握手失败
     */
    fun openTlsTunnel(proxy: BuiltinProxies.BuiltinProxy, targetHost: String, targetPort: Int, timeoutMs: Int = 8000): SSLSocket {
        val plain = openTunnel(proxy, targetHost, targetPort, timeoutMs)
        try {
            val factory = sslFactoryPreservingAllCerts()
            val sslSocket = factory.createSocket(plain, targetHost, targetPort, true) as SSLSocket
            val params = SSLParameters().apply {
                serverNames = listOf(SNIHostName(targetHost))
            }
            sslSocket.sslParameters = params
            sslSocket.soTimeout = timeoutMs
            sslSocket.startHandshake()
            return sslSocket
        } catch (e: Exception) {
            try { plain.close() } catch (_: Exception) {}
            throw e
        }
    }

    /** 探测专用的信任所有证书的 SSLSocketFactory（免费代理 MITM 常见，仅用于可达性探测） */
    private fun sslFactoryPreservingAllCerts(): javax.net.ssl.SSLSocketFactory {
        val trustAll = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustAll), java.security.SecureRandom())
        return context.socketFactory
    }

    /** 明文读取 HTTP 响应（直到对端关闭或达到上限） */
    fun readHttpResponse(input: InputStream, maxBytes: Int = 65536): String {
        val buffer = ByteArray(4096)
        val out = ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                break
            }
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
            // 响应体已完整：Content-Length 到齐或 chunked 结束 —— 简单按可用数据处理即可（我们只需要状态行/正文片段）
            if (total >= maxBytes) break
        }
        return out.toString("UTF-8")
    }

    private fun httpConnect(socket: Socket, targetHost: String, targetPort: Int) {
        val out: OutputStream = socket.getOutputStream()
        val in_: InputStream = socket.getInputStream()
        val req = "CONNECT $targetHost:$targetPort HTTP/1.1\r\n" +
            "Host: $targetHost:$targetPort\r\n" +
            "User-Agent: vlocation/1.0\r\n" +
            "Proxy-Connection: keep-alive\r\n\r\n"
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        val header = readHeader(in_, maxBytes = 8192)
        val statusLine = header.lineSequence().firstOrNull() ?: ""
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        if (code < 200 || code >= 300) {
            throw IOException("proxy CONNECT failed: $statusLine")
        }
    }

    private fun socks5Connect(socket: Socket, targetHost: String, targetPort: Int) {
        val out: OutputStream = socket.getOutputStream()
        val in_: InputStream = socket.getInputStream()

        // 握手：版本5 + 支持无认证
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val resp1 = readFully(in_, 2)
        if (resp1[0] != 5.toByte() || resp1[1] != 0.toByte()) {
            throw IOException("socks5 handshake rejected")
        }

        // CONNECT 请求
        val addrBytes = targetHost.toByteArray(Charsets.US_ASCII)
        val req = ByteArray(4 + 1 + addrBytes.size + 2)
        req[0] = 5; req[1] = 1; req[2] = 0
        req[3] = 3 // ATYP=DOMAIN
        req[4] = addrBytes.size.toByte()
        System.arraycopy(addrBytes, 0, req, 5, addrBytes.size)
        val portOffset = 5 + addrBytes.size
        req[portOffset] = ((targetPort ushr 8) and 0xFF).toByte()
        req[portOffset + 1] = (targetPort and 0xFF).toByte()
        out.write(req)
        out.flush()

        val header = readFully(in_, 4)
        if (header[0] != 5.toByte() || header[1] != 0.toByte()) {
            throw IOException("socks5 connect failed, reply=${(header[1].toInt() and 0xFF)}")
        }
        when (header[3].toInt() and 0xFF) {
            1 -> readFully(in_, 4 + 2)   // IPv4 + 端口
            3 -> {
                val len = readFully(in_, 1)[0].toInt() and 0xFF
                readFully(in_, len + 2)
            }
            4 -> readFully(in_, 16 + 2)  // IPv6 + 端口
            else -> throw IOException("socks5 unknown reply atyp")
        }
    }

    private fun readHeader(in_: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(maxBytes)
        var total = 0
        val marker = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0
        while (total < maxBytes) {
            val n = in_.read(buffer, total, maxBytes - total)
            if (n < 0) throw IOException("proxy closed connection while reading CONNECT response")
            for (i in total until total + n) {
                if (buffer[i] == marker[matched]) {
                    matched++
                    if (matched == 4) return String(buffer, 0, total + n, Charsets.US_ASCII)
                } else {
                    matched = if (buffer[i] == marker[0]) 1 else 0
                }
            }
            total += n
        }
        throw IOException("proxy CONNECT response header too large")
    }

    private fun readFully(in_: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = in_.read(data, read, length - read)
            if (n < 0) throw IOException("connection closed unexpectedly")
            read += n
        }
        return data
    }
}
