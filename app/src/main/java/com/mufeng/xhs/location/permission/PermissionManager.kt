package com.mufeng.xhs.location.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.mufeng.xhs.location.location.MockLocationManager

/**
 * 权限管理：检查开发者选项与模拟位置应用设置，并提供引导跳转。
 */
object PermissionManager {

    data class PermissionState(
        val developerOptionsEnabled: Boolean,
        val mockAppSet: Boolean,
        val mockLocationPermissionGranted: Boolean
    ) {
        val isAllGranted: Boolean get() = developerOptionsEnabled && mockAppSet && mockLocationPermissionGranted
    }

    fun checkPermissionState(context: Context, mockLocationManager: MockLocationManager): PermissionState {
        val mockLocationPermissionGranted = mockLocationManager.isMockLocationAllowed()
        return PermissionState(
            developerOptionsEnabled = true,
            mockAppSet = true,
            mockLocationPermissionGranted = mockLocationPermissionGranted
        )
    }

    fun openDeveloperSettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            val detailIntent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            if (detailIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(detailIntent)
            }
        }
    }

    fun openMockLocationAppSettings(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }

    fun hasMockLocationPermission(context: Context): Boolean {
        return context.packageManager.checkPermission(
            "android.permission.ACCESS_MOCK_LOCATION",
            context.packageName
        ) == PackageManager.PERMISSION_GRANTED
    }
}
