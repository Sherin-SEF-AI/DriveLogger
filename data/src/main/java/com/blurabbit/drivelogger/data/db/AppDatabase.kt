package com.blurabbit.drivelogger.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

        /** v2: persist completed multipart parts so a killed upload resumes without re-PUTting them. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE uploads ADD COLUMN completedPartsJson TEXT")
            }
        }
    }
}
