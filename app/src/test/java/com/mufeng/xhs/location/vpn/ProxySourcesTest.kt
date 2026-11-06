package com.mufeng.xhs.location.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxySourcesTest {

    // ========== parseProxyLine ==========

    @Test
    fun `parseProxyLine parses proto prefixed line with country tag`() {
        val p = parseProxyLine("socks5://222.211.216.201:7890 #cn")
        requireNotNull(p)
        assertEquals("222.211.216.201", p.server)
        assertEquals(7890, p.port)
        assertEquals(ProxyType.SOCKS5, p.type)
        assertEquals("cn", p.countryTag)
    }

    @Test
    fun `parseProxyLine parses bare ip port as socks5`() {
        val p = parseProxyLine("1.2.3.4:1080")
        requireNotNull(p)
        assertEquals(ProxyType.SOCKS5, p.type)
        assertNull(p.countryTag)
    }

    @Test
    fun `parseProxyLine maps http and https to HTTP type`() {
        assertEquals(ProxyType.HTTP, parseProxyLine("http://1.2.3.4:8080")?.type)
        assertEquals(ProxyType.HTTP, parseProxyLine("https://1.2.3.4:8080")?.type)
    }

    @Test
    fun `parseProxyLine rejects socks4 and invalid ports`() {
        assertNull(parseProxyLine("socks4://1.2.3.4:1080"))
        assertNull(parseProxyLine("http://1.2.3.4:99999"))
        assertNull(parseProxyLine("http://1.2.3.4:0"))
        assertNull(parseProxyLine("not a proxy line"))
        assertNull(parseProxyLine("# comment"))
        assertNull(parseProxyLine(""))
    }

    // ========== parseProxyLines（ProxyClean 格式） ==========

    @Test
    fun `parseProxyLines keeps cn and untagged lines when filtering cn`() {
        val text = """
            socks5://222.211.216.201:7890 #cn
            socks5://98.178.72.30:4145 #us
            socks5://1.2.3.4:1080 #sg
            5.6.7.8:1080
            junk line
        """.trimIndent()
        val out = parseProxyLines(text, countryFilter = "cn")
        assertEquals(2, out.size)
        assertEquals("222.211.216.201", out[0].server)
        assertEquals("5.6.7.8", out[1].server)
    }

    @Test
    fun `parseProxyLines without filter keeps all supported lines`() {
        val text = "socks5://1.1.1.1:1080 #us\nhttp://2.2.2.2:8080\nsocks4://3.3.3.3:1080 #cn\n"
        val out = parseProxyLines(text)
        assertEquals(2, out.size)
    }

    // ========== parseGeonodeJson ==========

    private val geonodeItem = """{"ip":"113.116.227.59","port":"9000","protocols":["http"],"city":"Shenzhen","upTime":100.0}"""
    private val geonodeSocksItem = """{"ip":"39.68.66.152","port":"1080","protocols":["socks5"],"city":"Jinan","upTime":97.3}"""
    private val geonodeSocks4Only = """{"ip":"1.2.3.4","port":"1080","protocols":["socks4"],"city":"","upTime":50.0}"""

    @Test
    fun `parseGeonodeJson parses bare array format`() {
        val text = "[$geonodeItem, $geonodeSocksItem, $geonodeSocks4Only]"
        val out = parseGeonodeJson(text)
        assertEquals(2, out.size)
        // 按 upTime 降序
        assertEquals("113.116.227.59", out[0].server)
        assertEquals(ProxyType.SOCKS5, out[1].type)
    }

    @Test
    fun `parseGeonodeJson parses envelope format`() {
        val text = """{"total": 2, "data": [$geonodeItem, $geonodeSocksItem]}"""
        val out = parseGeonodeJson(text)
        assertEquals(2, out.size)
    }

    @Test
    fun `parseGeonodeJson returns empty on garbage input`() {
        assertTrue(parseGeonodeJson("not json at all").isEmpty())
        assertTrue(parseGeonodeJson("").isEmpty())
        assertTrue(parseGeonodeJson("{\"total\": 0}").isEmpty())
    }

    // ========== parseProxiflyJson ==========

    @Test
    fun `parseProxiflyJson parses and sorts by score`() {
        val text = """[
            {"protocol":"socks5","ip":"1.1.1.1","port":1080,"score":3,"geolocation":{"country":"CN","city":"Foshan"}},
            {"protocol":"http","ip":"2.2.2.2","port":8080,"score":9,"geolocation":{"country":"CN","city":"Hangzhou"}},
            {"protocol":"socks4","ip":"3.3.3.3","port":1080,"score":10,"geolocation":{"country":"CN","city":""}}
        ]"""
        val out = parseProxiflyJson(text)
        assertEquals(2, out.size)
        assertEquals("2.2.2.2", out[0].server) // score 9 排最前
        assertEquals(ProxyType.HTTP, out[0].type)
        assertEquals("浙江", out[0].city)
    }

    // ========== parseProxyScrapeJson ==========

    @Test
    fun `parseProxyScrapeJson parses and sorts by uptime`() {
        val text = """[
            {"protocol":"socks5","ip":"1.1.1.1","port":1080,"uptime_percent":26.4,"city":"Chengdu"},
            {"protocol":"http","ip":"2.2.2.2","port":8085,"uptime_percent":98.4,"city":"Haikou"}
        ]"""
        val out = parseProxyScrapeJson(text)
        assertEquals(2, out.size)
        assertEquals("2.2.2.2", out[0].server)
        assertEquals("1.1.1.1", out[1].server)
    }

    // ========== mergeCandidates ==========

    private fun node(ip: String, port: Int = 1080) = BuiltinProxies.BuiltinProxy(
        server = ip, port = port, type = ProxyType.SOCKS5,
        label = "test", latitude = 0.0, longitude = 0.0, city = "其他地区"
    )

    @Test
    fun `mergeCandidates round robins across sources`() {
        val a = listOf(node("1.1.1.1"), node("1.1.1.2"), node("1.1.1.3"))
        val b = listOf(node("2.2.2.1"), node("2.2.2.2"))
        val merged = mergeCandidates(listOf(a, b), 10)
        assertEquals(listOf("1.1.1.1", "2.2.2.1", "1.1.1.2", "2.2.2.2", "1.1.1.3"), merged.map { it.server })
    }

    @Test
    fun `mergeCandidates dedupes by ip and port keeping first occurrence`() {
        val a = listOf(node("1.1.1.1"), node("1.1.1.2"))
        val b = listOf(node("1.1.1.1"), node("2.2.2.1")) // 1.1.1.1:1080 与 a 重复
        val merged = mergeCandidates(listOf(a, b), 10)
        // 轮转取样：a0(1.1.1.1) → b0(重复跳过) → a1(1.1.1.2) → b1(2.2.2.1)
        assertEquals(listOf("1.1.1.1", "1.1.1.2", "2.2.2.1"), merged.map { it.server })
    }

    @Test
    fun `mergeCandidates respects max limit`() {
        val a = (1..50).map { node("1.1.1.$it") }
        val b = (1..50).map { node("2.2.2.$it") }
        assertEquals(60, mergeCandidates(listOf(a, b), 60).size)
        assertEquals(5, mergeCandidates(listOf(a, b), 5).size)
    }

    @Test
    fun `mergeCandidates handles empty input`() {
        assertTrue(mergeCandidates(emptyList(), 10).isEmpty())
        assertTrue(mergeCandidates(listOf(emptyList<BuiltinProxies.BuiltinProxy>(), emptyList()), 10).isEmpty())
    }
}
