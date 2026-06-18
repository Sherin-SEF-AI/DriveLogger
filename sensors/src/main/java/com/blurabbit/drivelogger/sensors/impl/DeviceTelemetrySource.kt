package com.blurabbit.drivelogger.sensors.impl

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.proto.DeviceTelemetry
import com.blurabbit.drivelogger.sensors.RateTracker
import com.blurabbit.drivelogger.sensors.SensorHealthSnapshot
import com.blurabbit.drivelogger.sensors.SensorRecord
import com.blurabbit.drivelogger.sensors.SensorSource
import com.blurabbit.drivelogger.sensors.TopicDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject

/**
 * Polls device telemetry (~1 Hz): battery, charging, CPU temperature, RAM, storage, network,
 * thermal status. CPU temperature falls back to reading common `/sys/class/thermal` zones when
 * no public API is available.
 */
class DeviceTelemetrySource @Inject constructor(
    @ApplicationContext private val context: Context,
) : SensorSource {

    override val id: String = "telemetry"
    private val tracker = RateTracker(expectedHz = 1.0)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    override val topics = listOf(TopicDescriptor(Topics.DEVICE_TELEMETRY, DeviceTelemetry.getDescriptor()))

    override fun start(scope: CoroutineScope): Flow<SensorRecord> = flow {
        // Cancellation propagates through delay()/emit() when the collector stops.
        while (true) {
            val unified = SystemClock.elapsedRealtimeNanos()
            tracker.onSample(unified)
            emit(SensorRecord(Topics.DEVICE_TELEMETRY, unified, snapshot(unified)))
            delay(1_000)
        }
    }

    private fun snapshot(unifiedNs: Long): DeviceTelemetry {
        val mem = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val statFs = android.os.StatFs(context.filesDir.absolutePath)
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toDouble()
        val charging = batteryStatus()
        val thermal = if (Build.VERSION.SDK_INT >= 29) powerManager.currentThermalStatus else 0

        return DeviceTelemetry.newBuilder()
            .setUnifiedNs(unifiedNs)
            .setBatteryPct(batteryPct)
            .setCharging(charging)
            .setCpuTemperatureC(readCpuTemperature())
            .setRamUsedBytes(mem.totalMem - mem.availMem)
            .setRamTotalBytes(mem.totalMem)
            .setStorageFreeBytes(statFs.availableBytes)
            .setStorageTotalBytes(statFs.totalBytes)
            .setNetworkType(networkType())
            .setThermalStatus(thermal)
            .build()
    }

    private fun batteryStatus(): Boolean {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun networkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "NONE"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    private fun readCpuTemperature(): Double {
        // No stable public API; probe well-known thermal zones and report the hottest plausible read.
        for (i in 0..9) {
            val f = File("/sys/class/thermal/thermal_zone$i/temp")
            if (f.canRead()) {
                val raw = runCatching { f.readText().trim().toDouble() }.getOrNull() ?: continue
                val celsius = if (raw > 1000) raw / 1000.0 else raw
                if (celsius in 1.0..150.0) return celsius
            }
        }
        // -1 = unknown. Never return NaN: SQLite stores NaN as NULL, breaking NOT NULL columns.
        return -1.0
    }

    override fun stop() {}
    override fun health(): SensorHealthSnapshot = tracker.snapshot(id, driftMs = 0.0)
}
