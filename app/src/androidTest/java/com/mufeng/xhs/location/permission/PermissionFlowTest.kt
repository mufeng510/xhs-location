package com.mufeng.xhs.location.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.truth.Truth.assertThat
import com.mufeng.xhs.location.location.MockLocationManager
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {

    @Test
    fun `permission state default all false when no mock app set`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockLocationManager = MockLocationManager(context)

        val state = PermissionManager.checkPermissionState(context, mockLocationManager)

        assertThat(state.mockLocationPermissionGranted).isFalse()
    }

    @Test
    fun `hasMockLocationPermission returns boolean`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = PermissionManager.hasMockLocationPermission(context)
        assertThat(result).isFalse()
    }
}
