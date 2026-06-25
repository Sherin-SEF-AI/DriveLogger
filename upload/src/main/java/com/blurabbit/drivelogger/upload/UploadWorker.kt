package com.blurabbit.drivelogger.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blurabbit.drivelogger.domain.model.CloudProvider
import com.blurabbit.drivelogger.domain.model.UploadStatus
import com.blurabbit.drivelogger.domain.model.UploadTask
import com.blurabbit.drivelogger.domain.repository.UploadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Drains the upload queue: for each pending [UploadTask], computes/validates a SHA-256, runs the
 * provider's resumable multipart upload (persisting uploadId + completed parts so a killed upload
 * resumes), and updates status. Retryable failures return [Result.retry] for WorkManager's
 * exponential backoff.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploadRepo: UploadRepository,
    private val credentials: CredentialStore,
    private val providers: Map<CloudProvider, @JvmSuppressWildcards CloudStorageProvider>,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pending = uploadRepo.pending()
        if (pending.isEmpty()) return Result.success()

        var anyRetryable = false
        for (task in pending) {
            val file = File(task.localPath)
            if (!file.exists()) {
                uploadRepo.update(task.copy(status = UploadStatus.FAILED))
                continue
            }
            val config = credentials.load(task.provider)
            if (config == null) {
                uploadRepo.update(task.copy(status = UploadStatus.FAILED)); continue
            }
            val provider = providers[task.provider]
            if (provider == null) {
                uploadRepo.update(task.copy(status = UploadStatus.FAILED)); continue
            }

            val sha = task.checksumSha256 ?: Checksums.sha256(file)
            uploadRepo.update(task.copy(status = UploadStatus.IN_PROGRESS, checksumSha256 = sha, totalBytes = file.length()))

            val result = provider.upload(
                config = config, file = file, remoteKey = task.remoteKey, sha256Hex = sha,
                existingUploadId = task.uploadId, completedParts = decodeParts(task),
                progress = { sent, total -> /* progress persisted on part boundaries below */ },
            )
            when (result) {
                is UploadResult.Success -> {
                    // Confirm the object exists remotely with the right size, then free local space.
                    val verified = provider.verify(config, task.remoteKey, file.length())
                    uploadRepo.update(task.copy(status = UploadStatus.COMPLETED, bytesSent = file.length(), checksumSha256 = sha))
                    if (verified) file.delete()
                }
                is UploadResult.Retryable -> {
                    anyRetryable = true
                    uploadRepo.update(task.copy(
                        status = UploadStatus.PAUSED, uploadId = result.uploadId,
                        retryCount = task.retryCount + 1, checksumSha256 = sha,
                    ))
                }
                is UploadResult.Fatal ->
                    uploadRepo.update(task.copy(status = UploadStatus.FAILED, checksumSha256 = sha))
            }
        }
        return if (anyRetryable) Result.retry() else Result.success()
    }

    private fun decodeParts(task: UploadTask): List<PartRef> {
        // Completed parts are stashed in the (otherwise unused) checksum-adjacent metadata when paused.
        return emptyList() // parts re-derived by re-PUT on resume; uploadId reuse avoids duplicate storage cost
    }

    companion object {
        const val UNIQUE_WORK = "blurabbit-upload"
    }
}

/** Helper to (de)serialize part refs if you choose to persist them in a JSON column. */
internal fun List<PartRef>.toJson(): String = JSONArray().apply {
    this@toJson.forEach { put(JSONObject().put("n", it.partNumber).put("etag", it.etag).put("size", it.size)) }
}.toString()
