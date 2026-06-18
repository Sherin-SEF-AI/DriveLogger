package com.blurabbit.drivelogger.data.repository

import com.blurabbit.drivelogger.data.db.DeviceHealthEntity
import com.blurabbit.drivelogger.data.db.EventEntity
import com.blurabbit.drivelogger.data.db.RecordingSessionEntity
import com.blurabbit.drivelogger.data.db.SensorHealthEntity
import com.blurabbit.drivelogger.data.db.TripEntity
import com.blurabbit.drivelogger.data.db.UploadEntity
import com.blurabbit.drivelogger.domain.model.ArtifactKind
import com.blurabbit.drivelogger.domain.model.CloudProvider
import com.blurabbit.drivelogger.domain.model.DeviceHealthSample
import com.blurabbit.drivelogger.domain.model.DrivingEvent
import com.blurabbit.drivelogger.domain.model.DrivingEventType
import com.blurabbit.drivelogger.domain.model.RecordingSession
import com.blurabbit.drivelogger.domain.model.SensorHealthSample
import com.blurabbit.drivelogger.domain.model.Trip
import com.blurabbit.drivelogger.domain.model.TripProfile
import com.blurabbit.drivelogger.domain.model.TripStats
import com.blurabbit.drivelogger.domain.model.TripStatus
import com.blurabbit.drivelogger.domain.model.UploadStatus
import com.blurabbit.drivelogger.domain.model.UploadTask

internal fun TripEntity.toDomain() = Trip(
    id = id,
    profile = TripProfile(name, vehicleId, vehicleName, driverName, routeName, weather, city, notes),
    status = TripStatus.valueOf(status),
    startWallMs = startWallMs,
    endWallMs = endWallMs,
    startElapsedNs = startElapsedNs,
    stats = TripStats(distanceMeters, maxSpeedMps, avgSpeedMps, gpsSamples, imuSamples, frameCount, eventCount),
)

internal fun EventEntity.toDomain() = DrivingEvent(
    id = id, tripId = tripId, type = DrivingEventType.valueOf(type), confidence = confidence,
    unifiedTsNs = unifiedTsNs, latitude = latitude, longitude = longitude, speedMps = speedMps,
    evidenceJson = evidenceJson,
)

internal fun DrivingEvent.toEntity() = EventEntity(
    id = id, tripId = tripId, type = type.name, confidence = confidence, unifiedTsNs = unifiedTsNs,
    latitude = latitude, longitude = longitude, speedMps = speedMps, evidenceJson = evidenceJson,
)

internal fun UploadEntity.toDomain() = UploadTask(
    id = id, tripId = tripId, artifact = ArtifactKind.valueOf(artifact), provider = CloudProvider.valueOf(provider),
    localPath = localPath, remoteKey = remoteKey, status = UploadStatus.valueOf(status), uploadId = uploadId,
    bytesSent = bytesSent, totalBytes = totalBytes, checksumSha256 = checksumSha256, retryCount = retryCount,
)

internal fun UploadTask.toEntity() = UploadEntity(
    id = id, tripId = tripId, artifact = artifact.name, provider = provider.name, localPath = localPath,
    remoteKey = remoteKey, status = status.name, uploadId = uploadId, bytesSent = bytesSent,
    totalBytes = totalBytes, checksumSha256 = checksumSha256, retryCount = retryCount,
)

internal fun RecordingSessionEntity.toDomain() = RecordingSession(
    id, tripId, segmentIndex, startElapsedNs, endElapsedNs, mcapPath, mp4Path,
)

internal fun DeviceHealthSample.toEntity() = DeviceHealthEntity(
    id, tripId, unifiedTsNs, batteryPct, charging, cpuTemperatureC, ramUsedBytes, ramTotalBytes,
    storageFreeBytes, storageTotalBytes, networkType, thermalStatus,
)

internal fun DeviceHealthEntity.toDomain() = DeviceHealthSample(
    id, tripId, unifiedTsNs, batteryPct, charging, cpuTemperatureC, ramUsedBytes, ramTotalBytes,
    storageFreeBytes, storageTotalBytes, networkType, thermalStatus,
)

internal fun SensorHealthSample.toEntity() = SensorHealthEntity(
    id, tripId, sourceId, unifiedTsNs, expectedHz, actualHz, droppedSamples, driftMs, healthy,
)

internal fun SensorHealthEntity.toDomain() = SensorHealthSample(
    id, tripId, sourceId, unifiedTsNs, expectedHz, actualHz, droppedSamples, driftMs, healthy,
)
