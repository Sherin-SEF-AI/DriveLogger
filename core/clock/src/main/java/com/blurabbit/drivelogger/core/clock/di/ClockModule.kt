package com.blurabbit.drivelogger.core.clock.di

import com.blurabbit.drivelogger.core.clock.SystemTimeProvider
import com.blurabbit.drivelogger.core.clock.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider
}
