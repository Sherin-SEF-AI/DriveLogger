package com.blurabbit.drivelogger.core.mcap.di

import com.blurabbit.drivelogger.core.mcap.McapWriterConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object McapModule {

    /** Default file tuning; the Settings screen can override chunk size / ZSTD level at runtime. */
    @Provides
    @Singleton
    fun provideMcapWriterConfig(): McapWriterConfig = McapWriterConfig()
}
