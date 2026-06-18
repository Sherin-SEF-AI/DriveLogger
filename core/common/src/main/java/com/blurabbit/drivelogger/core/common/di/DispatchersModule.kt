package com.blurabbit.drivelogger.core.common.di

import com.blurabbit.drivelogger.core.common.dispatchers.AppDispatchers
import com.blurabbit.drivelogger.core.common.dispatchers.DefaultDispatcher
import com.blurabbit.drivelogger.core.common.dispatchers.IoDispatcher
import com.blurabbit.drivelogger.core.common.dispatchers.MainDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides @Singleton
    fun provideAppDispatchers(): AppDispatchers = AppDispatchers()

    @Provides @IoDispatcher
    fun provideIo(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun provideDefault(): CoroutineDispatcher = Dispatchers.Default

    @Provides @MainDispatcher
    fun provideMain(): CoroutineDispatcher = Dispatchers.Main
}
