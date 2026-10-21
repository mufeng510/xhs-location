package com.mufeng.xhs.location.service

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationForegroundServiceTest {

    @Test
    fun createStartIntent_should_set_action_START() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = LocationForegroundService.createStartIntent(context, 31.2304, 121.4737, 5.0f, "Shanghai")

        assertEquals(LocationForegroundService.ACTION_START, intent.action)
        assertEquals(31.2304, intent.getDoubleExtra(LocationForegroundService.EXTRA_LATITUDE, 0.0), 0.0001)
        assertEquals(121.4737, intent.getDoubleExtra(LocationForegroundService.EXTRA_LONGITUDE, 0.0), 0.0001)
        assertEquals(5.0f, intent.getFloatExtra(LocationForegroundService.EXTRA_ACCURACY, 0.0f))
        assertEquals("Shanghai", intent.getStringExtra(LocationForegroundService.EXTRA_ADDRESS))
    }

    @Test
    fun createStopIntent_should_set_action_STOP() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = LocationForegroundService.createStopIntent(context)

        assertEquals(LocationForegroundService.ACTION_STOP, intent.action)
    }

    @Test
    fun serviceClass_extends_Service() {
        assertTrue(Service::class.java.isAssignableFrom(LocationForegroundService::class.java))
    }
}
