package com.mufeng.xhs.location.vpn

/**
 * 代理探测器。
 *
 * 节点列表由 NAS 代理池服务下发（见 [NasProxyFetcher]），本地无需再抓取/实测
 * 除非用户主动“测试连接”某节点。
 *
 * 实测能力（保留供“测试连接”使用）：
 *  1. 小红书可达性：经代理隧道对 edith.xiaohongshu.com:443 做 TLS 握手 + HTTP GET，
 *     返回 2xx/3xx/4xx/5xx 即视为可访问小红书；
 *  2. 真实归属地：经代理隧道请求 http://myip.ipip.net，解析 "当前 IP：x.x.x.x  来自于：中国 …"。
 */
class ProxyFetcher private constructor() {

    companion object {
        /** 小红书 API 探测目标（主要用途：刷小红书） */
        private const val XHS_HOST = "edith.xiaohongshu.com"
        private const val XHS_PORT = 443

        /** IP 归属地查询（明文 HTTP，经代理只看服务器视角的地址） */
        private const val REGION_HOST = "myip.ipip.net"
        private const val REGION_PORT = 80

        /** 探测超时（毫秒） */
        private const val PROBE_TIMEOUT_MS = 8000

        fun create(): ProxyFetcher = ProxyFetcher()
    }

    // ========== 实测（供“测试连接”使用；列表正常来自 NAS） ==========

    /**
     * 经代理测试小红书 API 是否可访问：TLS 握手 + HTTP GET /，
     * 返回 2xx/3xx/4xx/5xx 视为可达（服务器可及响应即算）。
     */
    fun testXiaohongshu(server: String, port: Int, type: String): Boolean {
        return probeXiaohongshu(
            BuiltinProxies.BuiltinProxy(server, port, type, "", 0.0, 0.0, "")
        )
    }

    /** 测试代理能否访问小红书（失败返回 false） */
    fun probeXiaohongshu(proxy: BuiltinProxies.BuiltinProxy): Boolean {
        return try {
            ProxyHandshake.openTlsTunnel(proxy, XHS_HOST, XHS_PORT, PROBE_TIMEOUT_MS).use { ssl ->
                val out = ssl.outputStream
                val req = "GET / HTTP/1.1\r\n" +
                    "Host: $XHS_HOST\r\n" +
                    "User-Agent: Mozilla/5.0\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(req.toByteArray(Charsets.US_ASCII))
                out.flush()
                val buffer = ByteArray(2048)
                val n = ssl.inputStream.read(buffer)
                if (n <= 0) {
                    android.util.Log.w("ProxyFetcher", "probe ${proxy.server}:${proxy.port} empty response")
                    return false
                }
                val head = String(buffer, 0, n, Charsets.ISO_8859_1)
                // 支持 HTTP/1.0、HTTP/1.1、HTTP/2、HTTP/3 的状态行
                val code = Regex("""HTTP/\S*\s+(\d{3})""")
                    .find(head)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: run {
                    android.util.Log.w("ProxyFetcher", "probe ${proxy.server}:${proxy.port} no status line: ${head.take(150)}")
                    return false
                }
                // 2xx/3xx/4xx/5xx 均表示服务器可达
                code in 200..599
            }
        } catch (e: Exception) {
            android.util.Log.w("ProxyFetcher", "probe ${proxy.server}:${proxy.port} failed: ${e.message}")
            false
        }
    }

    /**
     * 经代理请求 ipip.net 归属地 API，解析真实归属。
     * 返回如 "湖北 十堰 电信"；解析失败返回 null。
     */
    fun testProxyRegion(server: String, port: Int, type: String): String? {
        return probeRegionDetail(BuiltinProxies.BuiltinProxy(server, port, type, "", 0.0, 0.0, ""))
    }

    private fun probeRegionDetail(proxy: BuiltinProxies.BuiltinProxy): String? {
        return try {
            ProxyHandshake.openTunnel(proxy, REGION_HOST, REGION_PORT, PROBE_TIMEOUT_MS).use { sock ->
                val out = sock.outputStream
                val req = "GET / HTTP/1.1\r\n" +
                    "Host: $REGION_HOST\r\n" +
                    "User-Agent: Mozilla/5.0\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(req.toByteArray(Charsets.US_ASCII))
                out.flush()
                val body = ProxyHandshake.readHttpResponse(sock.inputStream)
                val m = Regex("""当前 IP：\s*([0-9.]+)\s+来自于：\s*(.+)""").find(body) ?: return null
                val ip = m.groupValues[1].trim()
                val region = m.groupValues[2].trim().replace(Regex("\\s+"), " ")
                // 过滤内网/透明代理回写的假 IP
                if (ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.") ||
                    ip.startsWith("172.16.") || ip.startsWith("0.")
                ) {
                    "未知归属(透明代理)"
                } else {
                    region
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
