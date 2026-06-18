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
import java.io.File

/**
 * Drains the upload queue: for each pending [UploadTask], computes a SHA-256, runs the provider's
 * resumable multipart upload, and updates status. On a retryable failure the uploadId and the set
 * of already-completed parts are persisted (as [PartCodec]-encoded `completedPartsJson`) so the next
 * run skips re-uploading them. Retryable failures return [Result.retry] for WorkManager's
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
                is UploadResult.Success ->
                    uploadRepo.update(task.copy(
                        status = UploadStatus.COMPLETED, bytesSent = file.length(),
                        checksumSha256 = sha, completedPartsJson = null,
                    ))
                is UploadResult.Retryable -> {
                    anyRetryable = true
                    uploadRepo.update(task.copy(
                        status = UploadStatus.PAUSED, uploadId = result.uploadId,
                        retryCount = task.retryCount + 1, checksumSha256 = sha,
                        completedPartsJson = PartCodec.encode(result.completedParts),
                    ))
                }
                is UploadResult.Fatal ->
                    uploadRepo.update(task.copy(status = UploadStatus.FAILED, checksumSha256 = sha))
            }
        }
        return if (anyRetryable) Result.retry() else Result.success()
    }

    private fun decodeParts(task: UploadTask): List<PartRef> = PartCodec.decode(task.completedPartsJson)

    companion object {
        const val UNIQUE_WORK = "blurabbit-upload"
    }
}
