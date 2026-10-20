package com.mufeng.xhs.location.data.datastore

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

const val KEY_LATITUDE = "latitude"
const val KEY_LONGITUDE = "longitude"
const val KEY_ACCURACY = "accuracy"
const val KEY_ENABLED = "enabled"
const val KEY_LAST_UPDATED = "last_updated"

data class LocationConfig(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 5.0f,
    val enabled: Boolean = false,
    val lastUpdated: Long = 0L
)

interface LocationDataStore {
    suspend fun getLocationConfig(): LocationConfig
    suspend fun saveLocationConfig(config: LocationConfig)
    val locationConfigFlow: Flow<LocationConfig>
    suspend fun clearLocation()
}

class DefaultLocationDataStore(
    private val prefs: SharedPreferences
) : LocationDataStore {

    override suspend fun getLocationConfig(): LocationConfig {
        return LocationConfig(
            latitude = (prefs.getString(KEY_LATITUDE, "0.0") ?: "0.0").toDouble(),
            longitude = (prefs.getString(KEY_LONGITUDE, "0.0") ?: "0.0").toDouble(),
            accuracy = (prefs.getString(KEY_ACCURACY, "5.0") ?: "5.0").toFloat(),
            enabled = (prefs.getString(KEY_ENABLED, "false") ?: "false") == "true",
            lastUpdated = (prefs.getString(KEY_LAST_UPDATED, "0") ?: "0").toLong()
        )
    }

    override suspend fun saveLocationConfig(config: LocationConfig) {
        val editor = prefs.edit()
        editor.putString(KEY_LATITUDE, config.latitude.toString())
        editor.putString(KEY_LONGITUDE, config.longitude.toString())
        editor.putString(KEY_ACCURACY, config.accuracy.toString())
        editor.putString(KEY_ENABLED, config.enabled.toString())
        editor.putString(KEY_LAST_UPDATED, config.lastUpdated.toString())
        editor.apply()
    }

    override val locationConfigFlow: Flow<LocationConfig>
        get() = flow {
            while (true) {
                emit(getLocationConfig())
                delay(1000)
            }
        }

    override suspend fun clearLocation() {
        val editor = prefs.edit()
        editor.putString(KEY_ENABLED, "false")
        editor.apply()
    }
}
