package com.blurabbit.drivelogger.hdmap

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs [HdMapEnricher] for one trip on a worker thread (network). Retries on transient failure so
 * Overpass rate-limits resolve on WorkManager's backoff.
 */
@HiltWorker
class HdMapWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val enricher: HdMapEnricher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val tripId = inputData.getString(KEY_TRIP_ID) ?: return Result.failure()
        val ok = withContext(Dispatchers.IO) { enricher.enrich(tripId) }
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_WORK = "hdmap"
        private const val KEY_TRIP_ID = "tripId"
        fun input(tripId: String): Data = workDataOf(KEY_TRIP_ID to tripId)
    }
}
