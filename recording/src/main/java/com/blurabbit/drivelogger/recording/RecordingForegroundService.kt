package com.blurabbit.drivelogger.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts the recording pipeline for the lifetime of a trip. As a [LifecycleService] it provides the
 * LifecycleOwner CameraX needs, keeps the process alive via a foreground notification, and routes
 * START/PAUSE/RESUME/STOP intents to the (singleton) [TripRecorder]. UI observes recorder state
 * directly through the injected [RecordingController] singleton.
 */
@AndroidEntryPoint
class RecordingForegroundService : LifecycleService() {

    @Inject lateinit var recorder: TripRecorder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        recorder.attachLifecycle(this)
        // Reflect live state in the notification.
        lifecycleScope.launch {
            recorder.state.collect { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val tripId = intent.getStringExtra(EXTRA_TRIP_ID)
                startForegroundCompat(buildNotification(recorder.state.value))
                if (tripId != null) lifecycleScope.launch { recorder.start(tripId) }
            }
            ACTION_PAUSE -> lifecycleScope.launch { recorder.pause() }
            ACTION_RESUME -> lifecycleScope.launch { recorder.resume() }
            ACTION_STOP -> lifecycleScope.launch {
                recorder.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        recorder.attachLifecycle(null)
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(state: RecordingState) {
        if (state.phase == RecordingPhase.IDLE) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(state))
    }

    private fun buildNotification(state: RecordingState): Notification {
        val seconds = state.durationNs / 1_000_000_000
        val time = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        val text = "● ${state.phase}  $time  ${"%.1f".format(state.distanceMeters / 1000)} km  " +
            "${state.satellitesUsed} sats"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Blurabbit DriveLogger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.blurabbit.drivelogger.START"
        const val ACTION_PAUSE = "com.blurabbit.drivelogger.PAUSE"
        const val ACTION_RESUME = "com.blurabbit.drivelogger.RESUME"
        const val ACTION_STOP = "com.blurabbit.drivelogger.STOP"
        const val EXTRA_TRIP_ID = "trip_id"

        fun start(context: Context, tripId: String) = context.startForegroundService(
            Intent(context, RecordingForegroundService::class.java).setAction(ACTION_START).putExtra(EXTRA_TRIP_ID, tripId),
        )
        fun action(context: Context, action: String) = context.startService(
            Intent(context, RecordingForegroundService::class.java).setAction(action),
        )
    }
}
