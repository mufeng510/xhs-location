package com.mufeng.xhs.location.service

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationForegroundServiceTest {

    @Test
    fun `createStartIntent should set action START`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = LocationForegroundService.createStartIntent(context, 31.2304, 121.4737, 5.0f, "Shanghai")

        assertThat(intent.action).isEqualTo(LocationForegroundService.ACTION_START)
        assertThat(intent.getDoubleExtra(LocationForegroundService.EXTRA_LATITUDE, 0.0)).isEqualTo(31.2304)
        assertThat(intent.getDoubleExtra(LocationForegroundService.EXTRA_LONGITUDE, 0.0)).isEqualTo(121.4737)
        assertThat(intent.getFloatExtra(LocationForegroundService.EXTRA_ACCURACY, 0.0f)).isEqualTo(5.0f)
        assertThat(intent.getStringExtra(LocationForegroundService.EXTRA_ADDRESS)).isEqualTo("Shanghai")
    }

    @Test
    fun `createStopIntent should set action STOP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = LocationForegroundService.createStopIntent(context)

        assertThat(intent.action).isEqualTo(LocationForegroundService.ACTION_STOP)
    }

    @Test
    fun `service class extends Service`() {
        assertThat(Service::class.java.isAssignableFrom(LocationForegroundService::class.java)).isTrue()
    }
}
