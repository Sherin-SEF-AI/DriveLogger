package com.blurabbit.drivelogger.recording

import com.blurabbit.drivelogger.sensors.SensorHealthSnapshot

enum class RecordingPhase { IDLE, RECORDING, PAUSED, STOPPING }

/** Immutable snapshot rendered by the dashboard; published as a StateFlow by the recorder. */
data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val tripId: String? = null,
    val startElapsedNs: Long = 0,
    val durationNs: Long = 0,
    val currentSpeedMps: Double = 0.0,
    val satellitesTotal: Int = 0,
    val satellitesUsed: Int = 0,
    val distanceMeters: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val gpsSamples: Long = 0,
    val imuSamples: Long = 0,
    val frameCount: Long = 0,
    val eventCount: Long = 0,
    val storageFreeBytes: Long = 0,
    val batteryPct: Double = 0.0,
    val thermalStatus: Int = 0,
    val droppedWrites: Long = 0,
    val sensorHealth: List<SensorHealthSnapshot> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Control surface the UI/service drives. */
interface RecordingController {
    val state: kotlinx.coroutines.flow.StateFlow<RecordingState>
    suspend fun start(tripId: String)
    suspend fun pause()
    suspend fun resume()
    suspend fun stop()
}
