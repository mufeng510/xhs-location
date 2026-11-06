package com.mufeng.xhs.location.vpn

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SOCKS5 代理客户端。
 *
 * 与远程 SOCKS5 代理服务器建立连接，并转发任意 TCP 流量。
 * 握手流程：
 *  1. 客户端发送版本 + 支持的认证方法（NO_AUTH）
 *  2. 服务端回复选定的认证方法
 *  3. 客户端发送连接请求（CMD=CONNECT，ATYP=IPv4/DOMAIN，目标地址+端口）
 *  4. 服务端回复绑定成功
 */
class Socks5Client private constructor(
    private val proxyServer: String,
    private val proxyPort: Int,
    private val socketTimeout: Int = 10000
) : ProxyTunnel {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    companion object {
        private const val VERSION = 5.toByte()
        private const val CMD_CONNECT = 1.toByte()
        private const val ATYP_IPV4 = 1.toByte()
        private const val ATYP_DOMAIN = 3.toByte()

        /** 无认证方法 */
        private const val METHOD_NO_AUTH = 0.toByte()

        /** 回复：成功 */
        private const val REP_SUCCESS = 0.toByte()

        fun create(proxyServer: String, proxyPort: Int): Socks5Client {
            return Socks5Client(proxyServer, proxyPort)
        }
    }

    /**
     * 连接到目标主机（通过 SOCKS5 代理）。
     *
     * @param targetHost 目标主机（IP 或 域名）
     * @param targetPort 目标端口
     */
    override fun connect(targetHost: String, targetPort: Int) {
        socket = Socket()
        socket!!.connect(InetSocketAddress(proxyServer, proxyPort), socketTimeout)
        socket!!.soTimeout = socketTimeout
        socket!!.tcpNoDelay = true

        input = socket!!.getInputStream()
        output = socket!!.getOutputStream()

        // 第一步：发送握手请求（版本5，1种方法：无认证）
        val handshake = byteArrayOf(VERSION, 1, METHOD_NO_AUTH)
        output!!.write(handshake)
        output!!.flush()

        // 读取握手响应（2字节：版本，方法）
        val resp = readFully(2)
        if (resp[0] != VERSION || resp[1] != METHOD_NO_AUTH) {
            throw IOException("SOCKS5 handshake failed: version=${resp[0]}, method=${resp[1]}")
        }

        // 第二步：发送连接请求
        val addrBytes = buildAddressBytes(targetHost, targetPort)
        val connectReq = byteArrayOf(VERSION, CMD_CONNECT, 0x00) + addrBytes
        output!!.write(connectReq)
        output!!.flush()

        // 读取连接响应（至少4字节：版本，回复，保留，地址类型）
        val connectResp = readFully(4)
        if (connectResp[0] != VERSION || connectResp[1] != REP_SUCCESS) {
            throw IOException("SOCKS5 connect failed: reply=${connectResp[1]}")
        }

        // 跳过绑定地址（ATYP + 地址 + 端口）
        val atyp = connectResp[3]
        when (atyp) {
            ATYP_IPV4 -> readFully(4 + 2) // IPv4 + 端口
            ATYP_DOMAIN -> {
                val len = readFully(1)[0].toInt()
                readFully(len + 2) // 域名 + 端口
            }
            else -> throw IOException("Unknown address type in SOCKS5 response: $atyp")
        }
    }

    /** 从代理隧道读取数据 */
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return input!!.read(buffer, offset, length)
    }

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

    private fun readFully(length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input!!.read(data, read, length - read)
            if (n < 0) throw IOException("SOCKS5 connection closed unexpectedly")
            read += n
        }
        return data
    }

    /**
     * 构造 SOCKS5 地址字段：
     *   ATYP(1) + 地址长度(可变) + 端口(2)
     */
    private fun buildAddressBytes(host: String, port: Int): ByteArray {
        val isIpv4Literal = run {
            val parts = host.split('.')
            parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        }
        val addr = if (isIpv4Literal) {
            byteArrayOf(ATYP_IPV4) + InetAddress.getByName(host).address
        } else {
            // 域名一律按 ATYP=DOMAIN 发送（很多免费代理拒绝裸 IP 的 CONNECT）
            val bytes = host.toByteArray(Charsets.UTF_8)
            byteArrayOf(ATYP_DOMAIN, bytes.size.toByte()) + bytes
        }
        return addr + ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(port.toShort()).array()
    }
}
