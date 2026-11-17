package com.mufeng.xhs.location.vpn

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * NAS 代理池服务客户端。
 *
 * 服务器端（proxy-server/，见仓库 docs/requirement-proxy-server.md）定时抓取免费 CN
 * 代理、实测（小红书可达性 + 出口归属地）并落在 SQLite，仅暴露只读 API：
 *   GET /api/proxies           → 节点数组（ province / city / latitude / longitude /
 *                                xhs_reachable / latency_ms / type / port / server ）
 *   GET /api/status            → { last_update, total, reachable, sources[] }
 * 两处均需 `Authorization: Bearer <token>`。
 *
 * 本类只做网络与解析；缓存逻辑在 ProxyDataStore（SharedPreferences）里。
 */

/** NAS 侧状态（用于“更新于 x 分钟前 · 可达 n/m”展示） */
data class NasStatus(
    val lastUpdateIso: String?,
    val total: Int,
    val reachable: Int
)

data class NasFetch(
    val proxies: List<BuiltinProxies.BuiltinProxy>,
    val status: NasStatus
)

object NasJson {

    private fun typeOf(raw: String?): String? = when (raw?.lowercase()) {
        "socks5" -> ProxyType.SOCKS5
        "http", "https" -> ProxyType.HTTP
        else -> null
    }

    /** 解析 /api/proxies 响应；任何条目解析失败整体跳过该条，不影响其他节点 */
    fun parseProxies(text: String): List<BuiltinProxies.BuiltinProxy> {
        return try {
            val arr = JSONArray(text)
            val out = ArrayList<BuiltinProxies.BuiltinProxy>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val server = item.optString("server").trim()
                val port = item.optInt("port", 0)
                val type = typeOf(item.optString("type")) ?: continue
                if (server.isBlank() || port <= 0 || port > 65535) continue
                val province = item.optString("province").ifBlank { "其他地区" }
                val lat = item.optDouble("latitude", 0.0)
                val lon = item.optDouble("longitude", 0.0)
                val xhs = item.optInt("xhs_reachable", 0) == 1 || item.optBoolean("xhs_reachable")
                val latency = if (item.isNull("latency_ms")) null else item.optInt("latency_ms")
                out.add(
                    BuiltinProxies.BuiltinProxy(
                        server = server,
                        port = port,
                        type = type,
                        label = province,
                        latitude = lat,
                        longitude = lon,
                        city = province,
                        xhsReachable = xhs,
                        regionDetail = if (xhs) "小红书可达${latency?.let { " · ${it}ms" } ?: ""}" else "不可达"
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析地理编号为“小红书可达 · 120ms”形式的 regionDetail 文案
     * （缓存写入用；read 侧见 parseStatus）。
     */
    fun serializeStatus(status: NasStatus): String {
        val obj = JSONObject()
        obj.put("last_update", status.lastUpdateIso ?: "")
        obj.put("total", status.total)
        obj.put("reachable", status.reachable)
        return obj.toString()
    }

    fun serializeProxies(proxies: List<BuiltinProxies.BuiltinProxy>): String {
        val arr = JSONArray()
        for (p in proxies) {
            val obj = JSONObject()
            obj.put("server", p.server)
            obj.put("port", p.port)
            obj.put("type", p.type)
            obj.put("province", p.city)
            obj.put("city", p.city)
            obj.put("latitude", p.latitude)
            obj.put("longitude", p.longitude)
            obj.put("xhs_reachable", if (p.xhsReachable) 1 else 0)
            arr.put(obj)
        }
        return arr.toString()
    }

    /** 解析 /api/status 响应；失败返回未知状态 */
    fun parseStatus(text: String): NasStatus = try {
        val obj = JSONObject(text)
        NasStatus(
            lastUpdateIso = obj.optString("last_update").ifBlank { null },
            total = obj.optInt("total", 0),
            reachable = obj.optInt("reachable", 0)
        )
    } catch (e: Exception) {
        NasStatus(lastUpdateIso = null, total = 0, reachable = 0)
    }
}

/** vlocation://setup?url=...&token=... 扫码解析 */
object NasQr {
    /** 解析配对二维码；返回 null 表示不是合法的配对码 */
    fun parse(raw: String): NasConfig? {
        val text = raw.trim()
        if (!text.startsWith("vlocation://setup")) return null
        val query = text.substringAfter('?', "")
        if (query.isBlank()) return null
        val params = HashMap<String, String>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim()
            val value = part.substring(eq + 1)
            params[key] = try {
                java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
            } catch (e: Exception) {
                return null
            }
        }
        val url = params["url"]?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val token = params["token"]?.trim()?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            ?.takeIf { it.isNotBlank() } ?: return null
        return NasConfig(url, token)
    }
}

object NasProxyClient {

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 8000

    private fun get(baseUrl: String, path: String, token: String): Pair<Int, String> {
        val base = baseUrl.trimEnd('/')
        val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        try {
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?: java.io.ByteArrayInputStream(ByteArray(0))
            val stream = if (conn.contentEncoding == "gzip") GZIPInputStream(raw) else raw
            val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    class NasAuthException(message: String) : Exception(message)
    class NasUnreachableException(message: String) : Exception(message)

    /** 拉取节点列表 + 状态；鉴权失败抛 NasAuthException，网络/服务端错误抛 NasUnreachableException */
    fun fetch(baseUrl: String, token: String): NasFetch {
        require(baseUrl.isNotBlank() && token.isNotBlank()) { "NAS 地址或 Token 为空" }
        val (proxiesCode, proxiesText) = try {
            get(baseUrl, "/api/proxies", token)
        } catch (e: Exception) {
            throw NasUnreachableException("NAS 不可达：${e.message ?: "连接失败"}")
        }
        if (proxiesCode == 401 || proxiesCode == 403) {
            throw NasAuthException("NAS 拒绝访问：Token 无效")
        }
        if (proxiesCode !in 200..299) {
            throw NasUnreachableException("NAS 返回 $proxiesCode")
        }
        val (statusCode, statusText) = try {
            get(baseUrl, "/api/status", token)
        } catch (e: Exception) {
            200 to ""
        }
        val status = if (statusCode in 200..299) {
            NasJson.parseStatus(statusText)
        } else {
            NasStatus(lastUpdateIso = null, total = 0, reachable = 0)
        }
        val proxies = NasJson.parseProxies(proxiesText)
        return NasFetch(
            proxies = proxies,
            status = status.copy(
                total = status.takeIf { it.total > 0 }?.total ?: proxies.size,
                reachable = status.takeIf { it.total > 0 }?.reachable
                    ?: proxies.count { it.xhsReachable }
            )
        )
    }

    /** 仅校验连通性与鉴权（“测试连接”按钮用） */
    fun fetchStatus(baseUrl: String, token: String): NasStatus {
        val (code, text) = get(baseUrl, "/api/status", token)
        if (code == 401 || code == 403) throw NasAuthException("NAS 拒绝访问：Token 无效")
        if (code !in 200..299) throw NasUnreachableException("NAS 返回 $code")
        return NasJson.parseStatus(text)
    }
}
