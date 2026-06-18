package com.blurabbit.drivelogger.domain.model

/** Point-in-time device telemetry snapshot persisted for fleet health analytics. */
data class DeviceHealthSample(
    val id: Long = 0,
    val tripId: String?,
    val unifiedTsNs: Long,
    val batteryPct: Double,
    val charging: Boolean,
    val cpuTemperatureC: Double,
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val networkType: String,
    val thermalStatus: Int,
)

/** Per-sensor data-quality snapshot (drops, gaps, drift) for one source. */
data class SensorHealthSample(
    val id: Long = 0,
    val tripId: String?,
    val sourceId: String,
    val unifiedTsNs: Long,
    val expectedHz: Double,
    val actualHz: Double,
    val droppedSamples: Long,
    val driftMs: Double,
    val healthy: Boolean,
)

/** A contiguous recording segment within a trip (pause/resume creates a new segment). */
data class RecordingSession(
    val id: Long = 0,
    val tripId: String,
    val segmentIndex: Int,
    val startElapsedNs: Long,
    val endElapsedNs: Long?,
    val mcapPath: String,
    val mp4Path: String?,
)
