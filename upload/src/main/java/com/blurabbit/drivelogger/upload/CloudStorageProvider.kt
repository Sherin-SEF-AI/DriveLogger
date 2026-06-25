package com.blurabbit.drivelogger.upload

import com.blurabbit.drivelogger.domain.model.CloudProvider
import java.io.File

/** Connection + credential settings for an object-storage backend (S3, MinIO, Azure). */
data class CloudConfig(
    val provider: CloudProvider,
    val endpoint: String,        // e.g. https://s3.us-east-1.amazonaws.com or http://minio.local:9000
    val region: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val pathStyle: Boolean = false, // MinIO typically needs path-style addressing
)

/** Progress callback: bytes sent so far / total. */
fun interface UploadProgress { fun onProgress(sent: Long, total: Long) }

sealed interface UploadResult {
    data class Success(val remoteKey: String, val etag: String?) : UploadResult
    /** Resumable failure — [uploadId] + [completedParts] should be persisted to resume later. */
    data class Retryable(val reason: String, val uploadId: String?, val completedParts: List<PartRef>) : UploadResult
    data class Fatal(val reason: String) : UploadResult
}

/** A completed multipart part (number + ETag) used to resume / complete an upload. */
data class PartRef(val partNumber: Int, val etag: String, val size: Long)

/**
 * Backend-agnostic upload contract. Implementations perform resumable, checksum-validated,
 * multipart uploads. New backends implement this and are bound `@IntoMap` keyed by [CloudProvider].
 */
interface CloudStorageProvider {
    val provider: CloudProvider

    suspend fun upload(
        config: CloudConfig,
        file: File,
        remoteKey: String,
        sha256Hex: String,
        existingUploadId: String?,
        completedParts: List<PartRef>,
        progress: UploadProgress,
    ): UploadResult

    /** Confirm the object exists remotely with the expected size (HTTP HEAD). Gates local deletion. */
    suspend fun verify(config: CloudConfig, remoteKey: String, expectedBytes: Long): Boolean
}
