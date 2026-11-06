package com.mufeng.xhs.location.vpn

/**
 * 代理隧道公共接口（SOCKS5 / HTTP CONNECT 均实现）。
 *
 * VPN 转发层只依赖此接口，不关心具体代理协议。
 */
interface ProxyTunnel {
    /** 连接远端主机（经代理隧道）。失败时抛出 IOException。 */
    fun connect(targetHost: String, targetPort: Int)

    /** 从隧道读取数据；对端关闭时返回 -1。 */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** 向隧道写入数据 */
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** 关闭隧道 */
    fun close()
}
