package com.mufeng.xhs.location.location

import com.mufeng.xhs.location.data.datastore.LocationConfig
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 定位调度器：按固定间隔从 DriftEngine 生成漂移坐标并推送到 MockLocationManager。
 *
 * 发送间隔内部固定（3 秒），不对外暴露设置。
 */
class LocationScheduler @Inject constructor(
    private val mockLocationManager: MockLocationManager,
    private val driftEngine: DriftEngine,
    private val accuracy: Float
) {

    companion object {
        const val INTERVAL_MILLIS: Long = 3000
    }

    private var isRunning = false

    fun start(scope: CoroutineScope, config: LocationConfig, onLocation: (Double, Double) -> Unit, onError: (Throwable) -> Unit) {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                runScheduler(config, onLocation, onError)
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    private suspend fun runScheduler(
        config: LocationConfig,
        onLocation: (Double, Double) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        var currentLat = config.latitude
        var currentLon = config.longitude

        while (isRunning) {
            try {
                val (nextLat, nextLon) = driftEngine.nextLocation(currentLat, currentLon)
                currentLat = nextLat
                currentLon = nextLon

                val location = driftEngine.createLocation(currentLat, currentLon, accuracy)
                mockLocationManager.sendLocation(location)
                onLocation(currentLat, currentLon)
            } catch (e: Exception) {
                onError(e)
            }
            delay(INTERVAL_MILLIS)
        }
    }
}
