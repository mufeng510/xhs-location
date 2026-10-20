package com.mufeng.xhs.location.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mufeng.xhs.location.R
import com.mufeng.xhs.location.data.datastore.LocationConfig
import com.mufeng.xhs.location.location.DriftEngine
import com.mufeng.xhs.location.location.LocationScheduler
import com.mufeng.xhs.location.location.MockLocationManager
import com.mufeng.xhs.location.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@AndroidEntryPoint
class LocationForegroundService : Service() {

    @Inject lateinit var mockLocationManager: MockLocationManager
    @Inject lateinit var driftEngine: DriftEngine
    @Inject lateinit var locationScheduler: LocationScheduler

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentConfig: LocationConfig? = null
    private var notificationId = 1001
    private val channelId = "location_foreground"

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_UPDATE -> handleUpdate(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
        locationScheduler.stop()
        mockLocationManager.stop()
        serviceScope.cancel()
    }

    private fun handleStart(intent: Intent?) {
        val lat = intent?.getDoubleExtra(EXTRA_LATITUDE, 0.0) ?: 0.0
        val lon = intent?.getDoubleExtra(EXTRA_LONGITUDE, 0.0) ?: 0.0
        val acc = intent?.getFloatExtra(EXTRA_ACCURACY, 5.0f) ?: 5.0f
        val addr = intent?.getStringExtra(EXTRA_ADDRESS) ?: "未知位置"

        currentConfig = LocationConfig(latitude = lat, longitude = lon, accuracy = acc, enabled = true, lastUpdated = System.currentTimeMillis())

        mockLocationManager.setMockLocationEnabled(true)

        startForeground(notificationId, buildNotification(addr))
        locationScheduler.start(
            scope = serviceScope,
            config = currentConfig!!,
            onLocation = { _, _ -> },
            onError = { e ->
                // 服务异常：记录并继续，由界面层通过流感知
            }
        )
    }

    private fun handleStop() {
        stopForeground(true)
        locationScheduler.stop()
        mockLocationManager.stop()
        currentConfig?.let { config ->
            val disabled = config.copy(enabled = false)
            // 状态已在 ViewModel 层持久化，此处仅停止服务
        }
        stopSelf()
    }

    private fun handleUpdate(intent: Intent?) {
        val lat = intent?.getDoubleExtra(EXTRA_LATITUDE, 0.0) ?: 0.0
        val lon = intent?.getDoubleExtra(EXTRA_LONGITUDE, 0.0) ?: 0.0
        val addr = intent?.getStringExtra(EXTRA_ADDRESS) ?: "未知位置"
        currentConfig = currentConfig?.copy(latitude = lat, longitude = lon, lastUpdated = System.currentTimeMillis())
        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(notificationId, buildNotification(addr))
    }

    private fun buildNotification(address: String): Notification {
        val stopIntent = Intent(this, LocationForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notify_running))
            .setContentText(getString(R.string.notify_location_prefix) + address)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stop, getString(R.string.btn_stop), stopPending)
            .setContentIntent(contentPending)
            .build()
    }

    companion object {
        const val ACTION_START = "com.mufeng.xhs.location.action.START"
        const val ACTION_STOP = "com.mufeng.xhs.location.action.STOP"
        const val ACTION_UPDATE = "com.mufeng.xhs.location.action.UPDATE"

        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ACCURACY = "accuracy"
        const val EXTRA_ADDRESS = "address"

        fun createStartIntent(context: Context, lat: Double, lon: Double, acc: Float, address: String): Intent {
            return Intent(context, LocationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LATITUDE, lat)
                putExtra(EXTRA_LONGITUDE, lon)
                putExtra(EXTRA_ACCURACY, acc)
                putExtra(EXTRA_ADDRESS, address)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, LocationForegroundService::class.java).apply { action = ACTION_STOP }
        }

        fun createUpdateIntent(context: Context, lat: Double, lon: Double, address: String): Intent {
            return Intent(context, LocationForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_LATITUDE, lat)
                putExtra(EXTRA_LONGITUDE, lon)
                putExtra(EXTRA_ADDRESS, address)
            }
        }
    }
}
