package com.blurabbit.drivelogger.ui.tripdetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.blurabbit.drivelogger.domain.model.ArtifactKind
import com.blurabbit.drivelogger.domain.model.CloudProvider
import com.blurabbit.drivelogger.domain.model.DrivingEvent
import com.blurabbit.drivelogger.domain.model.Trip
import com.blurabbit.drivelogger.domain.model.UploadStatus
import com.blurabbit.drivelogger.domain.model.UploadTask
import com.blurabbit.drivelogger.domain.repository.EventRepository
import com.blurabbit.drivelogger.domain.repository.TripRepository
import com.blurabbit.drivelogger.domain.repository.UploadRepository
import com.blurabbit.drivelogger.domain.usecase.DeleteTripUseCase
import com.blurabbit.drivelogger.recording.TripStorage
import com.blurabbit.drivelogger.upload.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    tripRepo: TripRepository,
    eventRepo: EventRepository,
    private val uploadRepo: UploadRepository,
    private val storage: TripStorage,
    private val deleteTrip: DeleteTripUseCase,
) : ViewModel() {

    val tripId: String = checkNotNull(savedStateHandle["tripId"])

    val trip: StateFlow<Trip?> =
        tripRepo.observeTrip(tripId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val events: StateFlow<List<DrivingEvent>> =
        eventRepo.observeEvents(tripId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun artifacts(): List<File> =
        (storage.mcapSegments(tripId) + storage.segMp4Files(tripId) + storage.metadataFile(tripId))
            .filter { it.exists() }

    fun upload(provider: CloudProvider) {
        viewModelScope.launch {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val items = storage.mcapSegments(tripId).map { ArtifactKind.MCAP to it } +
                storage.segMp4Files(tripId).map { ArtifactKind.MP4 to it } +
                listOf(ArtifactKind.METADATA to storage.metadataFile(tripId))
            items.filter { it.second.exists() }.forEach { (kind, file) ->
                uploadRepo.enqueue(
                    UploadTask(
                        tripId = tripId, artifact = kind, provider = provider,
                        localPath = file.absolutePath,
                        remoteKey = "$date/$tripId/${file.name}",
                        status = UploadStatus.PENDING, totalBytes = file.length(),
                    ),
                )
            }
            scheduleUploadWork()
        }
    }

    private fun scheduleUploadWork() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UploadWorker.UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun delete() = viewModelScope.launch { deleteTrip(tripId) }
}
