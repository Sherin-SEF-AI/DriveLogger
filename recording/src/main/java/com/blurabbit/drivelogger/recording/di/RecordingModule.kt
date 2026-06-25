package com.blurabbit.drivelogger.recording.di

import com.blurabbit.drivelogger.recording.RecordingController
import com.blurabbit.drivelogger.recording.TripRecorder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RecordingModule {
    @Binds @Singleton
    abstract fun bindRecordingController(impl: TripRecorder): RecordingController
    // RecordingConfig is now derived per-trip from SettingsRepository (DataStore) in TripRecorder.
}
