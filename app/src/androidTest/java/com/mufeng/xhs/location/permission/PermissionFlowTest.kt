package com.mufeng.xhs.location.permission

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mufeng.xhs.location.location.MockLocationManager
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {

    @Test
    fun permission_state_default_all_false_when_no_mock_app_set() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockLocationManager = MockLocationManager(context)

        val state = PermissionManager.checkPermissionState(context, mockLocationManager)

        assertFalse(state.mockLocationPermissionGranted)
    }

    @Test
    fun hasMockLocationPermission_returns_boolean() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = PermissionManager.hasMockLocationPermission(context)

        assertFalse(result)
    }
}
