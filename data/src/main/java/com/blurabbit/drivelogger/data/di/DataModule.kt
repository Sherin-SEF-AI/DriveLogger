package com.blurabbit.drivelogger.data.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.blurabbit.drivelogger.data.db.AppDatabase
import com.blurabbit.drivelogger.data.db.EventDao
import com.blurabbit.drivelogger.data.db.HealthDao
import com.blurabbit.drivelogger.data.db.TripDao
import com.blurabbit.drivelogger.data.db.UploadDao
import com.blurabbit.drivelogger.data.repository.EventRepositoryImpl
import com.blurabbit.drivelogger.data.repository.HealthRepositoryImpl
import com.blurabbit.drivelogger.data.repository.TripRepositoryImpl
import com.blurabbit.drivelogger.data.repository.UploadRepositoryImpl
import com.blurabbit.drivelogger.domain.repository.EventRepository
import com.blurabbit.drivelogger.domain.repository.HealthRepository
import com.blurabbit.drivelogger.domain.repository.TripRepository
import com.blurabbit.drivelogger.domain.repository.UploadRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // WAL must be set on the builder — running `PRAGMA journal_mode=WAL` via execSQL throws
            // ("queries can be performed using query/rawQuery only") because it returns a row.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys=ON;") // no result row → execSQL is fine
                }
            })
            // Production migrations are added per schema version; never destructive in the field.
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

    @Provides fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
    @Provides fun provideEventDao(db: AppDatabase): EventDao = db.eventDao()
    @Provides fun provideUploadDao(db: AppDatabase): UploadDao = db.uploadDao()
    @Provides fun provideHealthDao(db: AppDatabase): HealthDao = db.healthDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun bindTripRepository(impl: TripRepositoryImpl): TripRepository
    @Binds @Singleton abstract fun bindEventRepository(impl: EventRepositoryImpl): EventRepository
    @Binds @Singleton abstract fun bindUploadRepository(impl: UploadRepositoryImpl): UploadRepository
    @Binds @Singleton abstract fun bindHealthRepository(impl: HealthRepositoryImpl): HealthRepository
}
