package com.mufeng.xhs.location.location

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriftEngineTest {

    private val SHANGHAI_LAT = 31.2304
    private val SHANGHAI_LON = 121.4737
    private val RADIUS_METERS = 30.0

    @Test
    fun `nextLocation should stay within radius around center`() {
        val engine = DriftEngine()

        repeat(1000) {
            val (lat, lon) = engine.nextLocation(SHANGHAI_LAT, SHANGHAI_LON)
            val distanceMeters = haversineDistance(SHANGHAI_LAT, SHANGHAI_LON, lat, lon)
            assertTrue("点 ($lat, $lon) 超出漂移半径 $RADIUS_METERS m，实际距离 $distanceMeters m",
                distanceMeters <= RADIUS_METERS + 1.0)
        }
    }

    @Test
    fun `nextLocation should not equal center every time`() {
        val engine = DriftEngine()
        var differentCount = 0
        repeat(100) {
            val (lat, lon) = engine.nextLocation(SHANGHAI_LAT, SHANGHAI_LON)
            if (abs(lat - SHANGHAI_LAT) > 1e-6 || abs(lon - SHANGHAI_LON) > 1e-6) {
                differentCount++
            }
        }
        assertTrue("大多数点应该不同于中心点，但只有 $differentCount 个不同", differentCount > 90)
    }

    @Test
    fun `createLocation should have reasonable GPS fields`() {
        val engine = DriftEngine()
        val location = engine.createLocation(SHANGHAI_LAT, SHANGHAI_LON, 5.0f)

        assertTrue("纬度应该匹配", location.latitude == SHANGHAI_LAT)
        assertTrue("经度应该匹配", location.longitude == SHANGHAI_LON)
        assertTrue("精度应该 >= 3", location.accuracy >= 3.0f)
        assertTrue("精度应该 <= 20", location.accuracy <= 20.0f)
        assertTrue("速度应该 >= 0", location.speed >= 0.0f)
        assertTrue("速度应该 <= 1.5", location.speed <= 1.5f)
        assertTrue("方位角应该 >= 0", location.bearing >= 0.0f)
        assertTrue("方位角应该 <= 360", location.bearing <= 360.0f)
        assertTrue("时间戳应该大于 0", location.time > 0L)
    }

    @Test
    fun `accuracy should be clamped to reasonable range`() {
        val engine = DriftEngine()
        val tooSmall = engine.createLocation(SHANGHAI_LAT, SHANGHAI_LON, 1.0f)
        val tooLarge = engine.createLocation(SHANGHAI_LAT, SHANGHAI_LON, 100.0f)

        assertTrue("最小精度应该被钳到 >= 3", tooSmall.accuracy >= 3.0f)
        assertTrue("最大精度应该被钳到 <= 20", tooLarge.accuracy <= 20.0f)
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * Math.atan2(sqrt(a), sqrt(1 - a))
        return 6_371_000.0 * c
    }
}
