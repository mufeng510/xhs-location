package com.mufeng.xhs.location.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import javax.inject.Inject

/**
 * Mock Location 调度器：通过 Android 官方 Mock Location API 向系统注入模拟位置。
 *
 * 同时向 GPS_PROVIDER 与 NETWORK_PROVIDER 注入模拟位置，覆盖小红书等应用
 * 可能读取的两类定位来源。Provider 选择由 Location 对象的 provider 字段决定。
 */
class MockLocationManager @Inject constructor(
    private val context: Context
) {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val enabledProviders = mutableSetOf<String>()

    private val SUPPORTED_PROVIDERS = setOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    fun isMockLocationEnabled(provider: String): Boolean {
        return try {
            val method = LocationManager::class.java.getMethod("isTestProviderEnabled", String::class.java)
            method.invoke(locationManager, provider) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    fun isAnyMockLocationEnabled(): Boolean {
        return SUPPORTED_PROVIDERS.any { isMockLocationEnabled(it) }
    }

    fun isMockLocationAllowed(): Boolean {
        return context.packageManager.checkPermission(
            "android.permission.ACCESS_MOCK_LOCATION",
            context.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * 为指定 provider 注册测试提供者并启用。
     * 支持 "gps" 与 "network" 两个 provider。
     */
    private fun registerTestProvider(provider: String): Boolean {
        val isGps = provider == LocationManager.GPS_PROVIDER
        return try {
            if (!SUPPORTED_PROVIDERS.contains(provider)) return false
            locationManager.addTestProvider(
                provider,
                true,   // requiresNetwork
                false,  // requiresSatellite
                false,  // requiresCell
                false,  // hasMonetaryCost
                true,   // supportsAltitude
                true,   // supportsSpeed
                true,   // supportsBearing
                if (isGps) android.location.Criteria.POWER_LOW else android.location.Criteria.POWER_LOW,
                if (isGps) android.location.Criteria.ACCURACY_FINE else android.location.Criteria.ACCURACY_COARSE
            )
            locationManager.setTestProviderEnabled(provider, true)
            locationManager.setTestProviderStatus(
                provider,
                android.location.LocationProvider.AVAILABLE,
                null,
                System.currentTimeMillis()
            )
            enabledProviders.add(provider)
            true
        } catch (e: Exception) {
            android.util.Log.e("MockLocationManager", "registerTestProvider failed (${if (isGps) "GPS" else "NETWORK"}): ${e.message}", e)
            false
        }
    }

    fun setMockLocationEnabled(enabled: Boolean): Boolean {
        return try {
            if (enabled) {
                registerTestProvider(LocationManager.GPS_PROVIDER)
                registerTestProvider(LocationManager.NETWORK_PROVIDER)
            } else {
                for (provider in enabledProviders.toList()) {
                    try {
                        locationManager.setTestProviderEnabled(provider, false)
                        locationManager.removeTestProvider(provider)
                    } catch (_: Exception) {
                        // ignore
                    }
                }
                enabledProviders.clear()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendLocation(location: Location): Boolean {
        return try {
            val provider = location.provider.takeIf { SUPPORTED_PROVIDERS.contains(it) }
                ?: LocationManager.GPS_PROVIDER
            locationManager.setTestProviderLocation(provider, location)
            true
        } catch (e: Exception) {
            android.util.Log.e("MockLocationManager", "setTestProviderLocation failed (${location.provider}): ${e.message}", e)
            false
        }
    }

    fun stop() {
        try {
            for (provider in enabledProviders.toList()) {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            }
            enabledProviders.clear()
        } catch (_: Exception) {
            // ignore
        }
    }
}
