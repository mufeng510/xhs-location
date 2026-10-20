package com.mufeng.xhs.location.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mufeng.xhs.location.service.LocationForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val PREF_NAME = "vlocation_preferences"
const val KEY_LATITUDE = "latitude"
const val KEY_LONGITUDE = "longitude"
const val KEY_ACCURACY = "accuracy"
const val KEY_ENABLED = "enabled"
const val KEY_AUTO_RESTORE = "auto_restore_enabled"

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.MY_PACKAGE_REPLACED") {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dataStore = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val enabled = dataStore.getBoolean(KEY_ENABLED, false)
                if (!enabled) return@launch

                val autoRestore = dataStore.getBoolean(KEY_AUTO_RESTORE, true)
                if (!autoRestore) return@launch

                val lat = dataStore.getFloat(KEY_LATITUDE, 0.0f).toDouble()
                val lon = dataStore.getFloat(KEY_LONGITUDE, 0.0f).toDouble()
                val acc = dataStore.getFloat(KEY_ACCURACY, 5.0f)

                val address = String.format("%.4f, %.4f", lat, lon)
                context.startService(
                    LocationForegroundService.createStartIntent(context, lat, lon, acc, address)
                )
            } catch (e: Exception) {
                // 静默忽略，避免开机崩溃
            }
        }
    }
}
