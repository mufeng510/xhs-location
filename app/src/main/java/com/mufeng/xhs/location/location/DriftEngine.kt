package com.mufeng.xhs.location.location

import android.location.Location
import android.location.LocationManager
import javax.inject.Inject
import java.util.Random

/**
 * 小范围随机漂移引擎。
 *
 * 围绕用户选定的中心点，在半径范围内随机生成坐标，并模拟自然移动。
 * 参数由 DriftConfig 内部固定，不对外暴露设置。
 */
class DriftEngine @Inject constructor() {

    private val random: Random = Random(System.currentTimeMillis())

    /**
     * 返回下一个漂移坐标（纬度，经度）。
     * 生成的点围绕中心点在半径内均匀分布。
     */
    fun nextLocation(latitude: Double, longitude: Double): Pair<Double, Double> {
        // 以当前传入点为基准，在半径范围内随机偏移（极坐标变换）
        val angle = random.nextDouble() * 2 * Math.PI
        val r = DriftConfig.RADIUS_METERS * Math.sqrt(random.nextDouble())

        val latOffset = (r * Math.cos(angle)) / DriftConfig.METERS_PER_DEGREE_LAT
        val lonOffset = (r * Math.sin(angle)) / (DriftConfig.METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(latitude)))

        return Pair(latitude + latOffset, longitude + lonOffset)
    }

    /**
     * 生成模拟 GPS 的 Location 对象。
     */
    fun createLocation(latitude: Double, longitude: Double, accuracy: Float): Location {
        val location = Location("mock")
        location.latitude = latitude
        location.longitude = longitude
        location.accuracy = accuracy.coerceIn(DriftConfig.ACCURACY_MIN, DriftConfig.ACCURACY_MAX)
        // 模拟合理的速度（0-1.5 m/s，约 0-5 km/h，自然漫步速度）
        location.speed = random.nextFloat() * DriftConfig.SPEED_MAX_MPS
        // 模拟方位角 0-360
        location.bearing = random.nextFloat() * DriftConfig.BEARING_MAX
        // 海拔：围绕 0-50m 的合理值
        location.altitude = random.nextDouble() * DriftConfig.ALTITUDE_MAX
        location.time = System.currentTimeMillis()
        location.provider = LocationManager.GPS_PROVIDER
        return location
    }
}
