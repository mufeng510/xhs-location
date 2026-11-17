package com.mufeng.xhs.location.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NasProxyFetcherTest {

    private val proxiesJson = """[
        {"server":"115.236.37.105","port":2411,"type":"http","province":"浙江","city":"杭州",
         "latitude":30.2741,"longitude":120.1551,"xhs_reachable":1,"latency_ms":328},
        {"server":"113.249.111.67","port":1080,"type":"socks5","province":"重庆","city":"重庆",
         "latitude":29.5630,"longitude":106.5516,"xhs_reachable":0,"latency_ms":null},
        {"server":"1.2.3.4","port":1080,"type":"socks4","province":"北京","city":"北京",
         "latitude":39.9,"longitude":116.4,"xhs_reachable":1,"latency_ms":10}
    ]"""

    @Test
    fun `parseProxies maps fields and skips unsupported protocol`() {
        val out = NasJson.parseProxies(proxiesJson)
        assertEquals(2, out.size)
        val zhe = out[0]
        assertEquals("115.236.37.105", zhe.server)
        assertEquals(2411, zhe.port)
        assertEquals(ProxyType.HTTP, zhe.type)
        assertEquals("浙江", zhe.city)          // 分组按 city == province
        assertEquals("浙江", zhe.label)
        assertTrue(zhe.xhsReachable)
        assertTrue(zhe.regionDetail.contains("328"))
        val cq = out[1]
        assertFalse(cq.xhsReachable)
        assertEquals(ProxyType.SOCKS5, cq.type)
    }

    @Test
    fun `serializeProxies then parseProxies round trips`() {
        val original = NasJson.parseProxies(proxiesJson)
        val reparsed = NasJson.parseProxies(NasJson.serializeProxies(original))
        assertEquals(original.size, reparsed.size)
        assertEquals(original.map { it.server }, reparsed.map { it.server })
        assertEquals(original.map { it.port }, reparsed.map { it.port })
        assertEquals(original.map { it.xhsReachable }, reparsed.map { it.xhsReachable })
    }

    @Test
    fun `parseStatus reads server summary`() {
        val s = NasJson.parseStatus("""{"last_update":"2026-11-17T14:04:47+0800","total":60,"reachable":7}""")
        assertEquals("2026-11-17T14:04:47+0800", s.lastUpdateIso)
        assertEquals(60, s.total)
        assertEquals(7, s.reachable)
        assertNull(NasJson.parseStatus("garbage").lastUpdateIso)
    }

    @Test
    fun `serializeStatus round trips through parseStatus`() {
        val s = NasStatus("2026-11-17T10:00:00+0800", 42, 9)
        val parsed = NasJson.parseStatus(NasJson.serializeStatus(s))
        assertEquals("2026-11-17T10:00:00+0800", parsed.lastUpdateIso)
        assertEquals(42, parsed.total)
        assertEquals(9, parsed.reachable)
    }

    @Test
    fun `qr parse accepts well formed setup link`() {
        val cfg = NasQr.parse("vlocation://setup?url=http%3A%2F%2F192.168.1.10%3A8080&token=abc-DEF_123")
        assertEquals("http://192.168.1.10:8080", cfg?.url)
        assertEquals("abc-DEF_123", cfg?.token)
    }

    @Test
    fun `qr parse rejects garbage and wrong scheme`() {
        assertNull(NasQr.parse(""))
        assertNull(NasQr.parse("https://example.com/setup?url=https%3A%2F%2Fx%2F&token=abc"))
        assertNull(NasQr.parse("vlocation://setup?url=not-a-url&token=abc"))
        assertNull(NasQr.parse("vlocation://setup?url=http%3A%2F%2Fnas%3A8080"))           // 缺 token
        assertNull(NasQr.parse("vlocation://setup?token=abc"))                              // 缺 url
        assertNull(NasQr.parse("vlocation://setup?url=http%3A%2F%2Fnas%3A8080&token="))     // 空 token
    }
}
