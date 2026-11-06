package com.mufeng.xhs.location.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 代理数据源注册表。
 *
 * 每个源独立拉取、各自 try/catch，失败互不影响；合并后按
 * “源优先级（注册顺序）+ 源内质量排序”轮转取样、ip:port 去重，
 * 截断 [MAX_FETCH_CANDIDATES] 个进入实测探测。
 * 不再自动兜底——用户在设置页明确选择用哪些源，失败的源单独标出。
 */

/** 单个源的抓取状态（UI 展示用） */
sealed interface SourceFetchStatus {
    /** 尚未抓取 */
    data object Idle : SourceFetchStatus

    /** 成功，candidateCount 为该源返回的原始候选数（去重前） */
    data class Ok(val candidateCount: Int) : SourceFetchStatus

    /** 失败，message 为简短原因（展示给用户） */
    data class Failed(val message: String) : SourceFetchStatus
}

/** 一次“拉取并合并”的结果（未实测） */
data class FetchOutcome(
    /** 合并去重、截断后的候选（未实测） */
    val candidates: List<BuiltinProxies.BuiltinProxy>,
    /** 每个源的抓取状态，key 为源 id */
    val sourceStatus: Map<String, SourceFetchStatus>
)

/** 一次“从所选源获取并实测”的完整结果（返回给 UI） */
data class FetchResult(
    /** 实测后的节点列表（小红书可达置顶，按地区限量） */
    val proxies: List<BuiltinProxies.BuiltinProxy>,
    /** 每个源的抓取状态，key 为源 id */
    val sourceStatus: Map<String, SourceFetchStatus>
)

/** 城市名 → (地区昵称, 纬度, 经度) 兜底归类（中英文城市名均可，匹配不到归“其他地区”） */
internal object ProxyRegion {
    fun classifyCity(city: String?): Triple<String, Double, Double> {
        val c = city?.trim()?.lowercase() ?: ""
        return when {
            c.contains("beijing") || c.contains("北京") -> Triple("北京", 39.9042, 116.4074)
            c.contains("shanghai") || c.contains("上海") -> Triple("上海", 31.2304, 121.4737)
            c.contains("guangzhou") || c.contains("shenzhen") || c.contains("foshan") ||
                c.contains("dongguan") || c.contains("zhongshan") ||
                c.contains("广州") || c.contains("深圳") || c.contains("广东") ->
                Triple("广东", 23.1291, 113.2644)
            c.contains("hangzhou") || c.contains("ningbo") || c.contains("wenzhou") ||
                c.contains("杭州") || c.contains("浙江") -> Triple("浙江", 30.2741, 120.1551)
            c.contains("chengdu") || c.contains("chongqing") || c.contains("成都") || c.contains("重庆") ->
                Triple("四川", 30.6595, 104.0648)
            else -> Triple("其他地区", 39.9042, 116.4074)
        }
    }

    /** 实测归属地（myip.ipip.net 返回串，如 "中国 湖北 十堰 电信"）→ 归类为我们的地区 */
    fun classifyByRegionDetail(regionDetail: String): Triple<String, Double, Double> {
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
}

/** 解析一行代理文本：支持 `socks5://1.2.3.4:1080`、`1.2.3.4:1080`，可选 ` #cn` 国家标签。
 *  返回 null 表示该行无法解析。countryTag 为小写国家码（如 cn/us），无标签时为 null。 */
internal data class ParsedProxyLine(
    val server: String,
    val port: Int,
    val type: String,
    val countryTag: String?
)

internal fun parseProxyLine(raw: String): ParsedProxyLine? {
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return null
    val m = Regex("^(?:(socks5|socks4|https?)://)?([0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[A-Za-z0-9.-]+):([0-9]{1,5})(?:\\s*#\\s*([A-Za-z]{2}))?\\s*$")
        .find(line) ?: return null
    val proto = m.groupValues[1].lowercase()
    val host = m.groupValues[2]
    val port = m.groupValues[3].toIntOrNull() ?: return null
    if (port !in 1..65535 || host.isBlank()) return null
    val type = when (proto) {
        "socks5", "" -> ProxyType.SOCKS5
        "http", "https" -> ProxyType.HTTP
        else -> return null // socks4 等协议 App 不支持
    }
    val tag = m.groupValues[4].lowercase().ifBlank { null }
    return ParsedProxyLine(host, port, type, tag)
}

/** 解析“每行一个代理”的文本；countryFilter 非空时只保留无标签或标签匹配的行。 */
internal fun parseProxyLines(text: String, countryFilter: String? = null): List<ParsedProxyLine> {
    val want = countryFilter?.lowercase()
    return text.lineSequence()
        .mapNotNull { parseProxyLine(it) }
        .filter { parsed -> want == null || parsed.countryTag == null || parsed.countryTag == want }
        .toList()
}

/** 从 geonode 风格 JSON 提取候选。兼容两种形态：
 *   1. 裸数组 `[ {...}, ... ]`（proxyfreeonly 返回）
 *   2. `{ "total": n, "data": [ ... ] }`（geonode 官方 API 返回）
 *  只保留 socks5 / http / https 协议（socks4 丢弃）；按 upTime 降序（无该字段时保持原序）。 */
internal fun parseGeonodeJson(text: String): List<BuiltinProxies.BuiltinProxy> {
    val trimmed = text.trim()
    val arr: JSONArray = when {
        trimmed.startsWith("[") -> try {
            JSONArray(trimmed)
        } catch (e: Exception) {
            return emptyList()
        }
        else -> try {
            JSONObject(trimmed).optJSONArray("data") ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
    }
    val out = ArrayList<Pair<Double, BuiltinProxies.BuiltinProxy>>(arr.length())
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val ip = item.optString("ip", "").trim()
        val port = item.optString("port", "").toIntOrNull() ?: continue
        if (ip.isBlank() || port <= 0) continue
        val protocols = item.optJSONArray("protocols")
        val protoList = (0 until (protocols?.length() ?: 0))
            .map { protocols!!.optString(it).lowercase() }
        val type = when {
            protoList.contains("socks5") -> ProxyType.SOCKS5
            protoList.contains("http") || protoList.contains("https") -> ProxyType.HTTP
            else -> continue
        }
        val city = item.optString("city", "")
        val (region, lat, lon) = ProxyRegion.classifyCity(city)
        val uptime = item.optDouble("upTime", -1.0)
        out.add(
            uptime to BuiltinProxies.BuiltinProxy(
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
    return out.sortedByDescending { it.first }.map { it.second }
}

/** 解析 proxifly/free-proxy-list 的 proxies/countries/CN/data.json：
 *  `[ { "proxy": "socks5://1.2.3.4:1080", "protocol": "socks5", "ip": ..., "port": ...,
 *       "score": 1, "geolocation": { "city": "Foshan", ... } }, ... ]`
 *  只保留 socks5 / http / https；按 score 降序（无该字段时保持原序）。 */
internal fun parseProxiflyJson(text: String): List<BuiltinProxies.BuiltinProxy> {
    val arr = try {
        JSONArray(text.trim())
    } catch (e: Exception) {
        return emptyList()
    }
    val out = ArrayList<Pair<Int, BuiltinProxies.BuiltinProxy>>(arr.length())
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val ip = item.optString("ip", "").trim()
        val port = item.optInt("port", 0)
        val proto = item.optString("protocol", "").lowercase()
        if (ip.isBlank() || port <= 0) continue
        val type = when (proto) {
            "socks5" -> ProxyType.SOCKS5
            "http", "https" -> ProxyType.HTTP
            else -> continue
        }
        val city = item.optJSONObject("geolocation")?.optString("city", "") ?: ""
        val (region, lat, lon) = ProxyRegion.classifyCity(city)
        val score = item.optInt("score", 0)
        out.add(
            score to BuiltinProxies.BuiltinProxy(
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
    return out.sortedByDescending { it.first }.map { it.second }
}

/** 解析 ProxyScrape/free-proxy-list 的 cn/data.json：
 *  `[ { "protocol": "socks5", "ip": ..., "port": 1080, "uptime_percent": 26.4, "city": "Chengdu", ... }, ... ]`
 *  只保留 socks5 / http / https；按 uptime_percent 降序。 */
internal fun parseProxyScrapeJson(text: String): List<BuiltinProxies.BuiltinProxy> {
    val arr = try {
        JSONArray(text.trim())
    } catch (e: Exception) {
        return emptyList()
    }
    val out = ArrayList<Pair<Double, BuiltinProxies.BuiltinProxy>>(arr.length())
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val ip = item.optString("ip", "").trim()
        val port = item.optInt("port", 0)
        val proto = item.optString("protocol", "").lowercase()
        if (ip.isBlank() || port <= 0) continue
        val type = when (proto) {
            "socks5" -> ProxyType.SOCKS5
            "http", "https" -> ProxyType.HTTP
            else -> continue
        }
        val (region, lat, lon) = ProxyRegion.classifyCity(item.optString("city", ""))
        val uptime = item.optDouble("uptime_percent", -1.0)
        out.add(
            uptime to BuiltinProxies.BuiltinProxy(
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
    return out.sortedByDescending { it.first }.map { it.second }
}

/**
 * 多源合并：按“轮转取样”从各源依次取 1 个，ip:port 去重，最多 max 个。
 * 某个源数量少不影响其余源继续供给，避免单一源占满候选池。
 */
internal fun mergeCandidates(
    perSource: List<List<BuiltinProxies.BuiltinProxy>>,
    max: Int
): List<BuiltinProxies.BuiltinProxy> {
    if (max <= 0) return emptyList()
    val seen = HashSet<String>()
    val out = ArrayList<BuiltinProxies.BuiltinProxy>(max)
    var round = 0
    while (out.size < max) {
        var progressed = false
        for (list in perSource) {
            if (round >= list.size) continue
            progressed = true
            val p = list[round]
            val key = "${p.server}:${p.port}".lowercase()
            if (seen.add(key)) {
                out.add(p)
                if (out.size >= max) break
            }
        }
        if (!progressed) break
        round++
    }
    return out
}

/** 一次合并最多进入实测的候选数 */
const val MAX_FETCH_CANDIDATES = 60

/** 并发拉取所选源并合并去重（未实测）。单个源失败不影响其他源；取消异常会原样抛出。 */
internal suspend fun fetchCandidates(sourceIds: Set<String>): FetchOutcome {
    val sources = ProxySourceRegistry.byIds(sourceIds)
    if (sources.isEmpty()) return FetchOutcome(emptyList(), emptyMap())
    val results = coroutineScope {
        sources.map { src ->
            async(Dispatchers.IO) {
                src to try {
                    Result.success(src.fetch())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }.awaitAll()
    }
    val perSource = ArrayList<List<BuiltinProxies.BuiltinProxy>>(results.size)
    val status = LinkedHashMap<String, SourceFetchStatus>(results.size)
    for ((src, result) in results) {
        result.fold(
            onSuccess = { list ->
                perSource.add(list)
                status[src.id] = SourceFetchStatus.Ok(list.size)
            },
            onFailure = { e ->
                status[src.id] = SourceFetchStatus.Failed(e.message ?: "网络错误")
            }
        )
    }
    return FetchOutcome(mergeCandidates(perSource, MAX_FETCH_CANDIDATES), status)
}

/** 代理数据源接口：实现需幂等、线程安全；失败抛异常，由调用方捕获并标注 */
interface ProxySource {
    /** 稳定 id，持久化与 UI 状态 key 用 */
    val id: String

    /** 展示名 */
    val name: String

    /** 一句话说明（含更新频率/规模，如“GitHub，每 5 分钟更新”） */
    val desc: String

    /** 拉取候选节点（未实测）；失败抛异常 */
    suspend fun fetch(): List<BuiltinProxies.BuiltinProxy>
}

private fun httpGet(url: String, accept: String? = null): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 15000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        if (accept != null) setRequestProperty("Accept", accept)
    }
    try {
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/** proxifly/free-proxy-list（GitHub，按国家分组，每 5 分钟更新）—— CN 约 400+ */
private object ProxiflyCnSource : ProxySource {
    override val id = "proxifly_cn"
    override val name = "proxifly（GitHub）"
    override val desc = "按国家分组的免费代理池，GitHub 约每 5 分钟更新，CN 约 400+（SOCKS5 为主），按 score 排序"
    override suspend fun fetch(): List<BuiltinProxies.BuiltinProxy> = withContext(Dispatchers.IO) {
        parseProxiflyJson(
            httpGet("https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/countries/CN/data.json",
                "application/json")
        )
    }
}

/** ProxyScrape/free-proxy-list（GitHub，国家验证）—— CN 约 20+，含 uptime/延迟 */
private object ProxyScrapeCnSource : ProxySource {
    override val id = "proxyscrape_cn"
    override val name = "ProxyScrape（GitHub）"
    override val desc = "国家验证代理，含 uptime/延迟/ISP，CN 约 20+ 条，质量高量少，按 uptime 排序"
    override suspend fun fetch(): List<BuiltinProxies.BuiltinProxy> = withContext(Dispatchers.IO) {
        parseProxyScrapeJson(
            httpGet("https://raw.githubusercontent.com/ProxyScrape/free-proxy-list/main/proxies/countries/cn/data.json",
                "application/json")
        )
    }
}

/** proxyfreeonly.com 免费代理 API（Geonode 同源数据），CN，limit 150 */
private object ProxyFreeOnlySource : ProxySource {
    override val id = "proxyfreeonly"
    override val name = "proxyfreeonly.com"
    override val desc = "免费代理 API（Geonode 同源），按国家筛选 CN，含 uptime/city，limit 150"
    override suspend fun fetch(): List<BuiltinProxies.BuiltinProxy> = withContext(Dispatchers.IO) {
        parseGeonodeJson(
            httpGet(
                "https://proxyfreeonly.com/api/free-proxy-list?limit=150&page=1&country=CN&sortBy=lastChecked&sortType=desc",
                "application/json"
            )
        )
    }
}

/** Geonode 官方免费 API（免 key）—— 与 proxyfreeonly 同源数据，互为备份 */
private object GeonodeCnSource : ProxySource {
    override val id = "geonode_cn"
    override val name = "Geonode 官方 API"
    override val desc = "Geonode 官方免费 API（免 key，limit 100，按 upTime 排序），与 proxyfreeonly 同源数据，可互为备份"
    override suspend fun fetch(): List<BuiltinProxies.BuiltinProxy> = withContext(Dispatchers.IO) {
        parseGeonodeJson(
            httpGet(
                "https://proxylist.geonode.com/api/proxy-list?country=CN&limit=100&sort_by=upTime&sort_type=desc",
                "application/json"
            )
        )
    }
}

/** ProxyClean（GitHub）—— 国内 SOCKS5 代理池，仅取 #cn 标记节点（当前约个位数） */
private object ProxyCleanCnSource : ProxySource {
    override val id = "proxyclean_cn"
    override val name = "ProxyClean（GitHub）"
    override val desc = "国内 SOCKS5 代理池，仅取标记为 cn 的节点；当前数量很少（约个位数）"
    override suspend fun fetch(): List<BuiltinProxies.BuiltinProxy> = withContext(Dispatchers.IO) {
        parseProxyLines(
            httpGet("https://raw.githubusercontent.com/HankNovic/ProxyClean/refs/heads/main/SOCKS5.txt"),
            countryFilter = "cn"
        ).map { parsed ->
            BuiltinProxies.BuiltinProxy(
                server = parsed.server,
                port = parsed.port,
                type = parsed.type,
                label = "其他地区 · 未知归属",
                latitude = 39.9042,
                longitude = 116.4074,
                city = "其他地区"
            )
        }
    }
}

/** 源注册表：UI 展示顺序即合并优先级；默认勾选见 [defaultSelectedIds] */
object ProxySourceRegistry {
    /** 展示顺序 = 合并取样优先级 */
    val all: List<ProxySource> = listOf(
        ProxiflyCnSource,
        ProxyScrapeCnSource,
        ProxyFreeOnlySource,
        ProxyCleanCnSource,
        GeonodeCnSource
    )

    /** 默认勾选：proxifly + ProxyScrape + proxyfreeonly + ProxyClean（geonode 与 proxyfreeonly 同源，默认不勾） */
    val defaultSelectedIds: Set<String> = setOf("proxifly_cn", "proxyscrape_cn", "proxyfreeonly", "proxyclean_cn")

    fun byIds(ids: Set<String>): List<ProxySource> = all.filter { it.id in ids }

    fun isKnown(id: String): Boolean = all.any { it.id == id }
}
