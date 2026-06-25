package com.blurabbit.drivelogger.recording.di

import com.blurabbit.drivelogger.recording.RecordingConfig
import com.blurabbit.drivelogger.recording.RecordingController
import com.blurabbit.drivelogger.recording.TripRecorder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RecordingModule {
    @Binds @Singleton
    abstract fun bindRecordingController(impl: TripRecorder): RecordingController

    companion object {
        // Tier 3 replaces this with a DataStore-backed value from the Settings screen.
        @Provides @Singleton
        fun provideRecordingConfig(): RecordingConfig = RecordingConfig()
    }
}
