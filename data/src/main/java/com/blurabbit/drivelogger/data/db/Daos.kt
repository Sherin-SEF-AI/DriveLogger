package com.blurabbit.drivelogger.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY COALESCE(startWallMs, 0) DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: String): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: String): TripEntity?

    @Query("SELECT * FROM trips WHERE status IN ('RECORDING','PAUSED')")
    suspend fun inProgress(): List<TripEntity>

    @Query("SELECT * FROM trips ORDER BY COALESCE(startWallMs, 0) DESC")
    suspend fun allOnce(): List<TripEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trip: TripEntity)

    @Query("UPDATE trips SET status = :status, endWallMs = COALESCE(:endWallMs, endWallMs) WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, endWallMs: Long?)

    @Query(
        """UPDATE trips SET distanceMeters = :distance, maxSpeedMps = :maxSpeed, avgSpeedMps = :avgSpeed,
           gpsSamples = :gps, imuSamples = :imu, frameCount = :frames, eventCount = :events WHERE id = :id""",
    )
    suspend fun updateStats(
        id: String, distance: Double, maxSpeed: Double, avgSpeed: Double,
        gps: Long, imu: Long, frames: Long, events: Long,
    )

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)

    @Insert
    suspend fun insertSession(session: RecordingSessionEntity): Long

    @Query("UPDATE recording_sessions SET endElapsedNs = :endNs, mp4Path = :mp4 WHERE id = :id")
    suspend fun closeSession(id: Long, endNs: Long, mp4: String?)

    @Query("SELECT * FROM recording_sessions WHERE tripId = :tripId ORDER BY segmentIndex")
    suspend fun sessionsFor(tripId: String): List<RecordingSessionEntity>
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE tripId = :tripId ORDER BY unifiedTsNs")
    fun observe(tripId: String): Flow<List<EventEntity>>

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT COUNT(*) FROM events WHERE tripId = :tripId")
    suspend fun countFor(tripId: String): Long
}

@Dao
interface UploadDao {
    @Query("SELECT * FROM uploads ORDER BY id DESC")
    fun observeAll(): Flow<List<UploadEntity>>

    @Insert
    suspend fun insert(task: UploadEntity): Long

    @Update
    suspend fun update(task: UploadEntity)

    @Query("SELECT * FROM uploads WHERE status IN ('PENDING','PAUSED','IN_PROGRESS') ORDER BY id")
    suspend fun pending(): List<UploadEntity>

    @Query("SELECT * FROM uploads WHERE id = :id")
    suspend fun byId(id: Long): UploadEntity?

    @Query("SELECT COUNT(*) FROM uploads WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: String): Int

    @Query("SELECT COUNT(*) FROM uploads WHERE tripId = :tripId AND status != 'COMPLETED'")
    suspend fun incompleteForTrip(tripId: String): Int
}

@Dao
interface HealthDao {
    @Insert
    suspend fun insertDevice(sample: DeviceHealthEntity)

    @Insert
    suspend fun insertSensor(sample: SensorHealthEntity)

    @Query("SELECT * FROM device_health ORDER BY unifiedTsNs DESC LIMIT 1")
    fun observeLatestDevice(): Flow<DeviceHealthEntity?>

    @Query("SELECT * FROM sensor_health WHERE tripId = :tripId ORDER BY unifiedTsNs DESC")
    fun observeSensors(tripId: String): Flow<List<SensorHealthEntity>>
}
