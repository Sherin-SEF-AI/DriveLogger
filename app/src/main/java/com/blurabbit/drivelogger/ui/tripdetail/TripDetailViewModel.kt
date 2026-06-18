package com.blurabbit.drivelogger.ui.tripdetail

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
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
import com.blurabbit.drivelogger.export.TripExporter
import com.blurabbit.drivelogger.hdmap.HdMapWorker
import com.blurabbit.drivelogger.recording.TripStorage
import com.blurabbit.drivelogger.upload.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val exporter: TripExporter,
) : ViewModel() {

    val tripId: String = checkNotNull(savedStateHandle["tripId"])

    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri

    val trip: StateFlow<Trip?> =
        tripRepo.observeTrip(tripId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val events: StateFlow<List<DrivingEvent>> =
        eventRepo.observeEvents(tripId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun artifacts(): List<File> = listOf(
        storage.mcapFile(tripId), storage.mp4File(tripId), storage.metadataFile(tripId),
    ).filter { it.exists() }

    fun upload(provider: CloudProvider) {
        viewModelScope.launch {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            listOf(
                ArtifactKind.MCAP to storage.mcapFile(tripId),
                ArtifactKind.MP4 to storage.mp4File(tripId),
                ArtifactKind.METADATA to storage.metadataFile(tripId),
            ).filter { it.second.exists() }.forEach { (kind, file) ->
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

    /** Build the share zip off the main thread, then publish a content:// uri for the chooser. */
    fun export(includeVideo: Boolean) {
        viewModelScope.launch {
            val zip = withContext(Dispatchers.IO) { exporter.export(tripId, includeVideo) }
            _shareUri.value = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        }
    }

    fun shareConsumed() { _shareUri.value = null }

    /** Enrich the trip with OSM road context (offline) → hdmap.json, included in the next export. */
    fun enrichHdMap() {
        val request = OneTimeWorkRequestBuilder<HdMapWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(HdMapWorker.input(tripId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("${HdMapWorker.UNIQUE_WORK}-$tripId", ExistingWorkPolicy.REPLACE, request)
    }

    fun delete() = viewModelScope.launch { deleteTrip(tripId) }
}
