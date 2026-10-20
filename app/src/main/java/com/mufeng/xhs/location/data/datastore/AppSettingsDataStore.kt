package com.mufeng.xhs.location.data.datastore

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

const val KEY_FIRST_LAUNCH = "first_launch_completed"
const val KEY_AUTO_RESTORE = "auto_restore_enabled"

data class AppSettings(
    val firstLaunchCompleted: Boolean = false,
    val autoRestoreEnabled: Boolean = true
)

interface AppSettingsDataStore {
    suspend fun getAppSettings(): AppSettings
    suspend fun saveAppSettings(settings: AppSettings)
    val appSettingsFlow: Flow<AppSettings>
    suspend fun markFirstLaunchCompleted()
}

class DefaultAppSettingsDataStore(
    private val prefs: SharedPreferences
) : AppSettingsDataStore {

    override suspend fun getAppSettings(): AppSettings {
        return AppSettings(
            firstLaunchCompleted = prefs.getBoolean(KEY_FIRST_LAUNCH, false),
            autoRestoreEnabled = prefs.getBoolean(KEY_AUTO_RESTORE, true)
        )
    }

    override suspend fun saveAppSettings(settings: AppSettings) {
        val editor = prefs.edit()
        editor.putBoolean(KEY_FIRST_LAUNCH, settings.firstLaunchCompleted)
        editor.putBoolean(KEY_AUTO_RESTORE, settings.autoRestoreEnabled)
        editor.apply()
    }

    override val appSettingsFlow: Flow<AppSettings>
        get() = flow {
            while (true) {
                emit(getAppSettings())
                delay(1000)
            }
        }

    override suspend fun markFirstLaunchCompleted() {
        val editor = prefs.edit()
        editor.putBoolean(KEY_FIRST_LAUNCH, true)
        editor.apply()
    }
}
