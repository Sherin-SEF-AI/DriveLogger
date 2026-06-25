package com.blurabbit.drivelogger.data.repository

import com.blurabbit.drivelogger.data.db.EventDao
import com.blurabbit.drivelogger.data.db.HealthDao
import com.blurabbit.drivelogger.data.db.RecordingSessionEntity
import com.blurabbit.drivelogger.data.db.TripDao
import com.blurabbit.drivelogger.data.db.TripEntity
import com.blurabbit.drivelogger.data.db.UploadDao
import com.blurabbit.drivelogger.domain.model.DeviceHealthSample
import com.blurabbit.drivelogger.domain.model.DrivingEvent
import com.blurabbit.drivelogger.domain.model.RecordingSession
import com.blurabbit.drivelogger.domain.model.SensorHealthSample
import com.blurabbit.drivelogger.domain.model.Trip
import com.blurabbit.drivelogger.domain.model.TripProfile
import com.blurabbit.drivelogger.domain.model.TripStats
import com.blurabbit.drivelogger.domain.model.TripStatus
import com.blurabbit.drivelogger.domain.model.UploadTask
import com.blurabbit.drivelogger.domain.repository.EventRepository
import com.blurabbit.drivelogger.domain.repository.HealthRepository
import com.blurabbit.drivelogger.domain.repository.TripRepository
import com.blurabbit.drivelogger.domain.repository.UploadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class TripRepositoryImpl @Inject constructor(
    private val dao: TripDao,
) : TripRepository {

    override fun observeTrips(): Flow<List<Trip>> = dao.observeAll().map { it.map(TripEntity::toDomain) }
    override fun observeTrip(id: String): Flow<Trip?> = dao.observe(id).map { it?.toDomain() }
    override suspend fun getTrip(id: String): Trip? = dao.get(id)?.toDomain()
    override suspend fun inProgressTrips(): List<Trip> = dao.inProgress().map { it.toDomain() }
    override suspend fun allTripsOnce(): List<Trip> = dao.allOnce().map { it.toDomain() }

    override suspend fun createTrip(profile: TripProfile, startElapsedNs: Long, startWallMs: Long): Trip {
        val entity = TripEntity(
            id = UUID.randomUUID().toString(),
            name = profile.name, vehicleId = profile.vehicleId, vehicleName = profile.vehicleName,
            driverName = profile.driverName, routeName = profile.routeName, weather = profile.weather,
            city = profile.city, notes = profile.notes,
            status = TripStatus.CREATED.name,
            startWallMs = startWallMs, endWallMs = null, startElapsedNs = startElapsedNs,
        )
        dao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun updateStatus(id: String, status: TripStatus, endWallMs: Long?) =
        dao.updateStatus(id, status.name, endWallMs)

    override suspend fun updateStats(id: String, stats: TripStats) = dao.updateStats(
        id, stats.distanceMeters, stats.maxSpeedMps, stats.avgSpeedMps,
        stats.gpsSamples, stats.imuSamples, stats.frameCount, stats.eventCount,
    )

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun addSession(session: RecordingSession): Long = dao.insertSession(
        RecordingSessionEntity(
            tripId = session.tripId, segmentIndex = session.segmentIndex,
            startElapsedNs = session.startElapsedNs, endElapsedNs = session.endElapsedNs,
            mcapPath = session.mcapPath, mp4Path = session.mp4Path,
        ),
    )

    override suspend fun closeSession(sessionId: Long, endElapsedNs: Long, mp4Path: String?) =
        dao.closeSession(sessionId, endElapsedNs, mp4Path)

    override suspend fun sessionsFor(tripId: String): List<RecordingSession> =
        dao.sessionsFor(tripId).map { it.toDomain() }
}

class EventRepositoryImpl @Inject constructor(
    private val dao: EventDao,
) : EventRepository {
    override fun observeEvents(tripId: String): Flow<List<DrivingEvent>> =
        dao.observe(tripId).map { list -> list.map { it.toDomain() } }

    override suspend fun insert(event: DrivingEvent): Long = dao.insert(event.toEntity())
    override suspend fun countFor(tripId: String): Long = dao.countFor(tripId)
}

class UploadRepositoryImpl @Inject constructor(
    private val dao: UploadDao,
) : UploadRepository {
    override fun observeQueue(): Flow<List<UploadTask>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun enqueue(task: UploadTask): Long = dao.insert(task.toEntity())
    override suspend fun update(task: UploadTask) = dao.update(task.toEntity())
    override suspend fun pending(): List<UploadTask> = dao.pending().map { it.toDomain() }
    override suspend fun byId(id: Long): UploadTask? = dao.byId(id)?.toDomain()
    override suspend fun countForTrip(tripId: String): Int = dao.countForTrip(tripId)
    override suspend fun incompleteForTrip(tripId: String): Int = dao.incompleteForTrip(tripId)
}

class HealthRepositoryImpl @Inject constructor(
    private val dao: HealthDao,
) : HealthRepository {
    override suspend fun recordDevice(sample: DeviceHealthSample) = dao.insertDevice(sample.toEntity())
    override suspend fun recordSensor(sample: SensorHealthSample) = dao.insertSensor(sample.toEntity())
    override fun observeLatestDevice(): Flow<DeviceHealthSample?> =
        dao.observeLatestDevice().map { it?.toDomain() }
    override fun observeSensors(tripId: String): Flow<List<SensorHealthSample>> =
        dao.observeSensors(tripId).map { list -> list.map { it.toDomain() } }
}
