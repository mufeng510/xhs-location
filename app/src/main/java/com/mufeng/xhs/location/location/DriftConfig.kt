package com.mufeng.xhs.location.location

/**
 * 漂移算法参数配置。
 * 所有参数内部固定，不对外暴露用户设置。
 */
object DriftConfig {
    /** 漂移半径（米），围绕用户选定中心点的范围 */
    const val RADIUS_METERS: Double = 30.0

    /** 模拟速度上限（m/s），约 0-5 km/h，自然漫步速度 */
    const val SPEED_MAX_MPS: Float = 1.5f

    /** 精度范围（米） */
    const val ACCURACY_MIN: Float = 3.0f
    const val ACCURACY_MAX: Float = 20.0f

    /** 海拔范围（米） */
    const val ALTITUDE_MAX: Double = 50.0

    /** 方位角范围 */
    const val BEARING_MAX: Float = 360.0f

    /** 地球赤道周长近似值（米/度） */
    const val METERS_PER_DEGREE_LAT: Double = 111_320.0
}
