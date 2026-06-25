package com.blurabbit.drivelogger.domain.repository

import com.blurabbit.drivelogger.domain.model.DeviceHealthSample
import com.blurabbit.drivelogger.domain.model.DrivingEvent
import com.blurabbit.drivelogger.domain.model.RecordingSession
import com.blurabbit.drivelogger.domain.model.SensorHealthSample
import com.blurabbit.drivelogger.domain.model.Trip
import com.blurabbit.drivelogger.domain.model.TripProfile
import com.blurabbit.drivelogger.domain.model.TripStats
import com.blurabbit.drivelogger.domain.model.TripStatus
import com.blurabbit.drivelogger.domain.model.UploadTask
import kotlinx.coroutines.flow.Flow

interface TripRepository {
    fun observeTrips(): Flow<List<Trip>>
    fun observeTrip(id: String): Flow<Trip?>
    suspend fun getTrip(id: String): Trip?
    suspend fun inProgressTrips(): List<Trip>
    suspend fun allTripsOnce(): List<Trip>
    suspend fun createTrip(profile: TripProfile, startElapsedNs: Long, startWallMs: Long): Trip
    suspend fun updateStatus(id: String, status: TripStatus, endWallMs: Long? = null)
    suspend fun setVerified(id: String, verified: Boolean)
    suspend fun updateStats(id: String, stats: TripStats)
    suspend fun delete(id: String)
    suspend fun addSession(session: RecordingSession): Long
    suspend fun closeSession(sessionId: Long, endElapsedNs: Long, mp4Path: String?)
    suspend fun sessionsFor(tripId: String): List<RecordingSession>
}

interface EventRepository {
    fun observeEvents(tripId: String): Flow<List<DrivingEvent>>
    suspend fun insert(event: DrivingEvent): Long
    suspend fun countFor(tripId: String): Long
}

interface UploadRepository {
    fun observeQueue(): Flow<List<UploadTask>>
    suspend fun enqueue(task: UploadTask): Long
    suspend fun update(task: UploadTask)
    suspend fun pending(): List<UploadTask>
    suspend fun byId(id: Long): UploadTask?
    suspend fun countForTrip(tripId: String): Int
    suspend fun incompleteForTrip(tripId: String): Int
}

interface HealthRepository {
    suspend fun recordDevice(sample: DeviceHealthSample)
    suspend fun recordSensor(sample: SensorHealthSample)
    fun observeLatestDevice(): Flow<DeviceHealthSample?>
    fun observeSensors(tripId: String): Flow<List<SensorHealthSample>>
}
