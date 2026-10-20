package com.mufeng.xhs.location.data.repository

import com.mufeng.xhs.location.data.datastore.AppSettings
import com.mufeng.xhs.location.data.datastore.AppSettingsDataStore
import com.mufeng.xhs.location.data.datastore.LocationConfig
import com.mufeng.xhs.location.data.datastore.LocationDataStore
import com.mufeng.xhs.location.location.DriftEngine
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class LocationRepository @Inject constructor(
    private val locationDataStore: LocationDataStore,
    private val appSettingsDataStore: AppSettingsDataStore,
    private val driftEngine: DriftEngine
) {

    suspend fun getLocationConfig(): LocationConfig = locationDataStore.getLocationConfig()

    suspend fun saveLocationConfig(config: LocationConfig) = locationDataStore.saveLocationConfig(config)

    suspend fun clearLocation() = locationDataStore.clearLocation()

    val locationConfigFlow: Flow<LocationConfig> = locationDataStore.locationConfigFlow

    suspend fun getAppSettings(): AppSettings = appSettingsDataStore.getAppSettings()

    suspend fun saveAppSettings(settings: AppSettings) = appSettingsDataStore.saveAppSettings(settings)

    val appSettingsFlow: Flow<AppSettings> = appSettingsDataStore.appSettingsFlow

    suspend fun markFirstLaunchCompleted() = appSettingsDataStore.markFirstLaunchCompleted()

    /**
     * 生成围绕中心点的漂移坐标。供定位调度器使用。
     */
    fun driftAround(latitude: Double, longitude: Double): Pair<Double, Double> =
        driftEngine.nextLocation(latitude, longitude)

    fun isEnabled(config: LocationConfig): Boolean = config.enabled
}
