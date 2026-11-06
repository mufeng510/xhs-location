package com.mufeng.xhs.location.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.mufeng.xhs.location.location.MockLocationManager

/**
 * 权限管理：检查开发者选项与模拟位置应用设置，并提供引导跳转。
 *
 * 注意：ACCESS_MOCK_LOCATION 是签名级权限，三方应用永远不会被授予。
 * 真正决定能否注入模拟位置的是：(1) 开发者选项已开启；(2) 本应用被设为“模拟位置应用”。
 */
object PermissionManager {

    data class PermissionState(
        val developerOptionsEnabled: Boolean,
        val mockAppSet: Boolean,
        val mockLocationPermissionGranted: Boolean
    ) {
        /** 开发者选项开启 且 本应用已被选为模拟位置应用，即可开始模拟。 */
        val isAllGranted: Boolean get() = developerOptionsEnabled && mockAppSet
    }

    fun checkPermissionState(context: Context, mockLocationManager: MockLocationManager): PermissionState {
        val developerOptionsEnabled = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            false
        }

        val mockAppSet = try {
            context.packageName == Settings.Secure.getString(
                context.contentResolver,
                "mock_location_app"
            )
        } catch (e: Exception) {
            false
        }

        val mockLocationPermissionGranted = mockLocationManager.isMockLocationAllowed()

        return PermissionState(
            developerOptionsEnabled = developerOptionsEnabled,
            mockAppSet = mockAppSet,
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
