package com.mufeng.xhs.location.vpn

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray

/**
 * 代理自动获取器。
 *
 * 数据源：
 *  1. proxyfreeonly.com 中国代理 API（主源，返回 JSON 数组，含 ip/port/protocols/city）
 *  2. ProxyClean SOCKS5.txt（兜底源，无城市信息）
 *
 * 获取后对每条候选做两项实测（经代理发起，不是本地直连）：
 *  1. 小红书可达性：经代理隧道对 edith.xiaohongshu.com:443 做 TLS 握手 + HTTP GET，
 *     返回 2xx/4xx 即视为可访问小红书（主要用途就是刷小红书）；
 *  2. 真实归属地：经代理隧道请求 http://myip.ipip.net，解析 "当前 IP：x.x.x.x  来自于：中国 …"，
 *     作为节点的真实地区标注（API 标注大多过时/不准，例如「北京-电信」实测在湖北十堰）。
 *
 * 地区归类优先采用实测归属地（含省份关键词），其次回退 API 的城市拼音。
 * 结果按“小红书可达优先”排序；小红书不可达的节点仍保留展示并标记，方便用户筛选。
 */
class ProxyFetcher private constructor() {

    companion object {
        private const val URL_FREE_ONLY =
            "https://proxyfreeonly.com/api/free-proxy-list?limit=150&page=1&country=CN&sortBy=lastChecked&sortType=desc"
        private const val URL_PROXY_CLEAN =
            "https://raw.githubusercontent.com/HankNovic/ProxyClean/refs/heads/main/SOCKS5.txt"

        /** 小红书 API 探测目标（主要用途：刷小红书） */
        private const val XHS_HOST = "edith.xiaohongshu.com"
        private const val XHS_PORT = 443

        /** IP 归属地查询（明文 HTTP，经代理只看服务器视角的地址） */
        private const val REGION_HOST = "myip.ipip.net"
        private const val REGION_PORT = 80

        /** 最多探测的候选节点数（避免一次获取耗时过长） */
        private const val MAX_CANDIDATES = 60

        /** 每个地区最多保留的节点数 */
        private const val MAX_PER_REGION = 12

        /** 并发探测数 */
        private const val PROBE_CONCURRENCY = 16

        /** 探测超时（毫秒） */
        private const val PROBE_TIMEOUT_MS = 8000

        fun create(): ProxyFetcher = ProxyFetcher()

        /** 城市拼音名兜底归类：city -> (地区昵称, 纬度, 经度) */
        private fun classifyCity(city: String?): Triple<String, Double, Double> {
            val c = city?.trim()?.lowercase() ?: ""
            return when {
                c.contains("beijing") -> Triple("北京", 39.9042, 116.4074)
                c.contains("shanghai") -> Triple("上海", 31.2304, 121.4737)
                c.contains("guangzhou") || c.contains("shenzhen") || c.contains("foshan") ||
                    c.contains("dongguan") || c.contains("zhongshan") ->
                    Triple("广东", 23.1291, 113.2644)
                c.contains("hangzhou") || c.contains("ningbo") || c.contains("wenzhou") ->
                    Triple("浙江", 30.2741, 120.1551)
                c.contains("chengdu") || c.contains("chongqing") -> Triple("四川", 30.6595, 104.0648)
                else -> Triple("其他地区", 39.9042, 116.4074)
            }
        }

        /** 实测归属地（myip.ipip.net 返回串，如 "中国 湖北 十堰 电信"）→ 归类为我们的地区 */
        private fun classifyByRegionDetail(regionDetail: String): Triple<String, Double, Double> {
            val r = regionDetail
            return when {
                r.contains("北京") -> Triple("北京", 39.9042, 116.4074)
                r.contains("上海") -> Triple("上海", 31.2304, 121.4737)
                r.contains("广东") || r.contains("广州") || r.contains("深圳") || r.contains("佛山") ||
                    r.contains("东莞") || r.contains("中山") || r.contains("珠海") ->
                    Triple("广东", 23.1291, 113.2644)
                r.contains("浙江") || r.contains("杭州") || r.contains("宁波") || r.contains("温州") ->
                    Triple("浙江", 30.2741, 120.1551)
                r.contains("四川") || r.contains("成都") || r.contains("重庆") ->
                    Triple("四川", 30.6595, 104.0648)
                else -> Triple("其他地区", 39.9042, 116.4074)
            }
        }

        /** 该地区对应的 GPS 坐标 */
        private fun regionGps(region: String): Pair<Double, Double> = when (region) {
            "北京" -> 39.9042 to 116.4074
            "上海" -> 31.2304 to 121.4737
            "广东" -> 23.1291 to 113.2644
            "浙江" -> 30.2741 to 120.1551
            "四川" -> 30.6595 to 104.0648
            else -> 39.9042 to 116.4074
        }
    }

    /**
     * 获取最新可用代理列表（已实测小红书可达性与真实归属地）。
     *
     * @throws Exception 网络不可达、API 返回异常等（调用方应捕获并提示）
     */
    suspend fun fetchFreshProxies(): List<BuiltinProxies.BuiltinProxy> {
        val candidates: List<BuiltinProxies.BuiltinProxy> = try {
            fetchFromFreeOnly()
        } catch (e: Exception) {
            // 主源失败，回退到 ProxyClean（无城市信息）
            fetchFromProxyClean()
        }

        if (candidates.isEmpty()) return emptyList()

        val tested = probeAll(candidates.take(MAX_CANDIDATES))

        // 优先只返回小红书可达的节点（软件就服务小红书，死节点没有展示价值）；
        // 若本批全部不可达，则返回全部并让 UI 提示"本批均不可达，建议重试"。
        val reachable = tested.filter { it.xhsReachable }
        val filtered = if (reachable.isNotEmpty()) reachable else tested

        // 按实测归属地分类（拿不到实测结果时回退 API 城市），每地区限量
        return filtered.groupBy { p ->
            val rd = p.regionDetail
            if (rd.isBlank() || rd == "未知归属") {
                // 归类仍用 fetch 阶段记录的 city 字段（来自拼音匹配或默认值）
                when {
                    p.city.startsWith("北京") -> "北京"
                    p.city.startsWith("上海") -> "上海"
                    p.city == "其他地区" -> "其他地区"
                    p.city.startsWith("广") -> "广东"
                    p.city.startsWith("杭") || p.city.startsWith("浙") -> "浙江"
                    p.city.startsWith("成") || p.city.startsWith("四") -> "四川"
                    else -> p.city
                }
            } else {
                classifyByRegionDetail(rd).first
            }
        }.values.flatMap { it.take(MAX_PER_REGION) }
    }

    // ========== 数据源 ==========

    private suspend fun fetchFromFreeOnly(): List<BuiltinProxies.BuiltinProxy> = withContext(Dispatchers.IO) {
        val connection = (URL(URL_FREE_ONLY).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(text)
            val result = mutableListOf<BuiltinProxies.BuiltinProxy>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val ip = item.optString("ip", "")
                val port = item.optString("port", "0").toIntOrNull() ?: 0
                if (ip.isBlank() || port <= 0) continue
                val protocols = item.optJSONArray("protocols")
                val protoList = (0 until (protocols?.length() ?: 0)).map { protocols!!.optString(it).lowercase() }
                val type = when {
                    protoList.contains("socks5") -> ProxyType.SOCKS5
                    protoList.contains("http") -> ProxyType.HTTP
                    protoList.contains("https") -> ProxyType.HTTP
                    else -> continue
                }
                val cityRaw = item.optString("city", "")
                val (region, lat, lon) = classifyCity(cityRaw)
                result.add(
                    BuiltinProxies.BuiltinProxy(
                        server = ip,
                        port = port,
                        type = type,
                        label = "$region · 未知归属",
                        latitude = lat,
                        longitude = lon,
                        city = region
                    )
                )
            }
            result
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun fetchFromProxyClean(): List<BuiltinProxies.BuiltinProxy> = withContext(Dispatchers.IO) {
        val connection = (URL(URL_PROXY_CLEAN).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
        }
        try {
            val lines = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
            lines.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains(":") && it.count { c -> c == ':' } == 1 }
                .mapNotNull { line ->
                    val idx = line.indexOf(':')
                    val host = line.substring(0, idx).trim()
                    val port = line.substring(idx + 1).trim().toIntOrNull() ?: return@mapNotNull null
                    if (host.isBlank() || port <= 0) null else host to port
                }
                .map { (host, port) ->
                    BuiltinProxies.BuiltinProxy(
                        server = host,
                        port = port,
                        type = ProxyType.SOCKS5,
                        label = "其他地区 · 未知归属",
                        latitude = 39.9042,
                        longitude = 116.4074,
                        city = "其他地区"
                    )
                }
                .toList()
        } finally {
            connection.disconnect()
        }
    }

    // ========== 实测探测 ==========

    private suspend fun probeAll(candidates: List<BuiltinProxies.BuiltinProxy>): List<BuiltinProxies.BuiltinProxy> =
        coroutineScope {
            val results = java.util.Collections.synchronizedList(mutableListOf<BuiltinProxies.BuiltinProxy>())
            candidates.chunked(PROBE_CONCURRENCY).forEach { chunk ->
                chunk.map { proxy ->
                    async(Dispatchers.IO) {
                        probeOne(proxy)
                    }
                }.forEach { deferred -> deferred.await()?.let { results.add(it) } }
            }
            // 小红书可达的节点置顶
            results.sortByDescending { it.xhsReachable }
            results
        }

    private fun probeOne(proxy: BuiltinProxies.BuiltinProxy): BuiltinProxies.BuiltinProxy? {
        val xhsOk = probeXiaohongshu(proxy)
        val regionDetail = probeRegionDetail(proxy) ?: "未知归属"
        val (region, lat, lon) = if (regionDetail != "未知归属") {
            classifyByRegionDetail(regionDetail)
        } else {
            Triple(proxy.city, proxy.latitude, proxy.longitude)
        }
        return proxy.copy(
            label = "$region · $regionDetail",
            latitude = lat,
            longitude = lon,
            city = region,
            xhsReachable = xhsOk,
            regionDetail = regionDetail
        )
    }

    /**
     * 经代理测试小红书 API 是否可访问：TLS 握手 + HTTP GET /，
     * 返回 2xx/4xx 视为可达（服务器可及响应即算）。
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
