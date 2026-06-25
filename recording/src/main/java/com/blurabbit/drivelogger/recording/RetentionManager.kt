package com.blurabbit.drivelogger.recording

import com.blurabbit.drivelogger.domain.model.TripStatus
import com.blurabbit.drivelogger.domain.repository.SettingsRepository
import com.blurabbit.drivelogger.domain.repository.TripRepository
import com.blurabbit.drivelogger.domain.repository.UploadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frees local storage by deleting trips that are safely backed up. A trip's local files are removed
 * only when it is finished, **all of its uploads are COMPLETED**, and it falls outside the most
 * recent [RecordingConfig.keepLastNTrips] (a safety buffer). DB rows are kept for history.
 *
 * The primary space win is per-artifact deletion in [UploadWorker] right after a verified upload;
 * this sweep mops up leftovers (metadata, fully-uploaded older trips) on app start and after stop.
 */
@Singleton
class RetentionManager @Inject constructor(
    private val storage: TripStorage,
    private val tripRepo: TripRepository,
    private val uploadRepo: UploadRepository,
    private val settingsRepo: SettingsRepository,
) {
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        val keepLastN = settingsRepo.get().keepLastNTrips
        val finished = tripRepo.allTripsOnce().filter {
            it.status == TripStatus.STOPPED || it.status == TripStatus.EXPORTED
        }
        // Keep the most-recent N locally regardless of upload state.
        finished.drop(keepLastN).forEach { trip ->
            val total = uploadRepo.countForTrip(trip.id)
            val incomplete = uploadRepo.incompleteForTrip(trip.id)
            // Only delete if it was enqueued for upload and everything finished.
            if (total > 0 && incomplete == 0) {
                if (storage.dirSizeBytes(trip.id) > 0 && storage.deleteTrip(trip.id)) deleted++
            }
        }
        deleted
    }
}
