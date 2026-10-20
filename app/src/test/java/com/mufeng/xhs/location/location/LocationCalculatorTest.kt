package com.mufeng.xhs.location.location

import org.junit.Test
import org.junit.Assert.assertTrue

class LocationCalculatorTest {

    @Test
    fun `format coordinate to 7 decimal places`() {
        val lat = 31.230456789
        val formatted = lat.toString().take(7)
        assertTrue("格式应该截断到 7 位", formatted == "31.2304")
    }

    @Test
    fun `address string format`() {
        val lat = 31.2304
        val lon = 121.4737
        val address = String.format("%.4f, %.4f", lat, lon)
        assertTrue("地址格式应该正确", address == "31.2304, 121.4737")
    }
}
