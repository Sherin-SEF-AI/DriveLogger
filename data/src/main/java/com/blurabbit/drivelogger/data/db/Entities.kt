package com.blurabbit.drivelogger.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trips",
    indices = [Index("status"), Index("startWallMs")],
)
data class TripEntity(
    @PrimaryKey val id: String,
    val name: String?,
    val vehicleId: String?,
    val vehicleName: String?,
    val driverName: String?,
    val routeName: String?,
    val weather: String?,
    val city: String?,
    val notes: String?,
    val status: String,
    val startWallMs: Long?,
    val endWallMs: Long?,
    val startElapsedNs: Long?,
    val distanceMeters: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val gpsSamples: Long = 0,
    val imuSamples: Long = 0,
    val frameCount: Long = 0,
    val eventCount: Long = 0,
)

@Entity(
    tableName = "recording_sessions",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tripId")],
)
data class RecordingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val segmentIndex: Int,
    val startElapsedNs: Long,
    val endElapsedNs: Long?,
    val mcapPath: String,
    val mp4Path: String?,
)

@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tripId"), Index("unifiedTsNs"), Index("type")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val type: String,
    val confidence: Double,
    val unifiedTsNs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedMps: Double?,
    val evidenceJson: String,
)

@Entity(
    tableName = "uploads",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tripId"), Index("status")],
)
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val artifact: String,
    val provider: String,
    val localPath: String,
    val remoteKey: String,
    val status: String,
    val uploadId: String?,
    val bytesSent: Long,
    val totalBytes: Long,
    val checksumSha256: String?,
    val retryCount: Int,
)

@Entity(
    tableName = "device_health",
    indices = [Index("tripId"), Index("unifiedTsNs")],
)
data class DeviceHealthEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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

@Entity(
    tableName = "sensor_health",
    indices = [Index("tripId"), Index("sourceId"), Index("unifiedTsNs")],
)
data class SensorHealthEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String?,
    val sourceId: String,
    val unifiedTsNs: Long,
    val expectedHz: Double,
    val actualHz: Double,
    val droppedSamples: Long,
    val driftMs: Double,
    val healthy: Boolean,
)
