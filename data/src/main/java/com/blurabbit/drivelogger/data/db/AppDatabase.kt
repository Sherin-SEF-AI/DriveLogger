package com.blurabbit.drivelogger.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class,
        RecordingSessionEntity::class,
        EventEntity::class,
        UploadEntity::class,
        DeviceHealthEntity::class,
        SensorHealthEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun eventDao(): EventDao
    abstract fun uploadDao(): UploadDao
    abstract fun healthDao(): HealthDao

    companion object {
        const val NAME = "drivelogger.db"
    }
}
