package com.blurabbit.drivelogger.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blurabbit.drivelogger.domain.model.AppSettings
import com.blurabbit.drivelogger.domain.model.VideoQuality
import com.blurabbit.drivelogger.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "drivelogger_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val quality = stringPreferencesKey("video_quality")
        val segSeconds = longPreferencesKey("segment_seconds")
        val segMb = intPreferencesKey("segment_mb")
        val floorMb = intPreferencesKey("free_space_floor_mb")
        val keepN = intPreferencesKey("keep_last_n")
        val compression = booleanPreferencesKey("compression_enabled")
        val autoUpload = booleanPreferencesKey("auto_upload")
        val embedVideo = booleanPreferencesKey("embed_video")
        val consent = booleanPreferencesKey("consent_given")
        val anonymize = booleanPreferencesKey("anonymize_pii")
    }

    override fun observe(): Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }
    override suspend fun get(): AppSettings = context.dataStore.data.first().toSettings()

    override suspend fun update(settings: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.quality] = settings.videoQuality.name
            p[Keys.segSeconds] = settings.segmentSeconds
            p[Keys.segMb] = settings.segmentMb
            p[Keys.floorMb] = settings.freeSpaceFloorMb
            p[Keys.keepN] = settings.keepLastNTrips
            p[Keys.compression] = settings.compressionEnabled
            p[Keys.autoUpload] = settings.autoUpload
            p[Keys.embedVideo] = settings.embedVideo
            p[Keys.consent] = settings.consentGiven
            p[Keys.anonymize] = settings.anonymizePii
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            videoQuality = this[Keys.quality]?.let { runCatching { VideoQuality.valueOf(it) }.getOrNull() } ?: d.videoQuality,
            segmentSeconds = this[Keys.segSeconds] ?: d.segmentSeconds,
            segmentMb = this[Keys.segMb] ?: d.segmentMb,
            freeSpaceFloorMb = this[Keys.floorMb] ?: d.freeSpaceFloorMb,
            keepLastNTrips = this[Keys.keepN] ?: d.keepLastNTrips,
            compressionEnabled = this[Keys.compression] ?: d.compressionEnabled,
            autoUpload = this[Keys.autoUpload] ?: d.autoUpload,
            embedVideo = this[Keys.embedVideo] ?: d.embedVideo,
            consentGiven = this[Keys.consent] ?: d.consentGiven,
            anonymizePii = this[Keys.anonymize] ?: d.anonymizePii,
        )
    }
}
