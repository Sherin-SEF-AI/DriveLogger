package com.blurabbit.drivelogger.domain.repository

import com.blurabbit.drivelogger.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/** Persistent app/recording settings (DataStore-backed). */
interface SettingsRepository {
    fun observe(): Flow<AppSettings>
    suspend fun get(): AppSettings
    suspend fun update(settings: AppSettings)
}
