package com.blurabbit.drivelogger.domain.model

enum class UploadStatus { PENDING, IN_PROGRESS, PAUSED, COMPLETED, FAILED }

enum class CloudProvider { AWS_S3, MINIO, AZURE_BLOB }

/** Artifact kinds produced per trip. */
enum class ArtifactKind { MCAP, MP4, METADATA }

data class UploadTask(
    val id: Long = 0,
    val tripId: String,
    val artifact: ArtifactKind,
    val provider: CloudProvider,
    val localPath: String,
    val remoteKey: String,
    val status: UploadStatus,
    val uploadId: String? = null,      // provider multipart upload id (for resume)
    val bytesSent: Long = 0,
    val totalBytes: Long = 0,
    val checksumSha256: String? = null,
    val retryCount: Int = 0,
)
