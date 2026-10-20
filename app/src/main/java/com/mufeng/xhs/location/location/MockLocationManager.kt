package com.mufeng.xhs.location.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import javax.inject.Inject

/**
 * Mock Location 调度器：通过 Android 官方 Mock Location API 向系统注入模拟位置。
 */
class MockLocationManager @Inject constructor(
    private val context: Context
) {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var isRunning = false

    fun isMockLocationEnabled(): Boolean {
        return try {
            val method = LocationManager::class.java.getMethod("isTestProviderEnabled", String::class.java)
            method.invoke(locationManager, LocationManager.GPS_PROVIDER) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    fun isMockLocationAllowed(): Boolean {
        return context.packageManager.checkPermission(
            "android.permission.ACCESS_MOCK_LOCATION",
            context.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun setMockLocationEnabled(enabled: Boolean): Boolean {
        return try {
            if (enabled) {
                locationManager.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    true, false, false, false,
                    true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                locationManager.setTestProviderStatus(
                    LocationManager.GPS_PROVIDER,
                    android.location.LocationProvider.AVAILABLE,
                    null,
                    System.currentTimeMillis()
                )
            } else {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendLocation(location: Location): Boolean {
        return try {
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        isRunning = false
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            // ignore
        }
    }
}
