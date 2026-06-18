package com.blurabbit.drivelogger.upload

import com.blurabbit.drivelogger.domain.model.CloudProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject

/**
 * Resumable, checksum-validated S3 multipart upload. Works against AWS S3 and any S3-compatible
 * store (MinIO via path-style). Resume: skip parts already present in [completedParts]; reuse the
 * provided multipart [existingUploadId].
 */
class S3Provider @Inject constructor(
    private val client: OkHttpClient,
) : CloudStorageProvider {

    override val provider = CloudProvider.AWS_S3

    override suspend fun upload(
        config: CloudConfig,
        file: File,
        remoteKey: String,
        sha256Hex: String,
        existingUploadId: String?,
        completedParts: List<PartRef>,
        progress: UploadProgress,
    ): UploadResult {
        val signer = AwsV4Signer(config.accessKey, config.secretKey, config.region)
        val total = file.length()
        val parts = completedParts.toMutableList()

        return try {
            val uploadId = existingUploadId ?: initiate(config, signer, remoteKey)
                ?: return UploadResult.Retryable("initiate failed", null, parts)

            val done = parts.associateBy { it.partNumber }
            var offset = 0L
            var partNumber = 1
            var sent = parts.sumOf { it.size }
            while (offset < total) {
                val size = minOf(PART_SIZE, total - offset)
                val existing = done[partNumber]
                if (existing == null) {
                    val checksum = Checksums.sha256Base64(file, offset, size)
                    val etag = uploadPart(config, signer, remoteKey, uploadId, partNumber, file, offset, size, checksum)
                        ?: return UploadResult.Retryable("part $partNumber failed", uploadId, parts)
                    parts += PartRef(partNumber, etag, size, checksum)
                    sent += size
                    progress.onProgress(sent, total)
                }
                offset += size
                partNumber++
            }

            val etag = complete(config, signer, remoteKey, uploadId, parts.sortedBy { it.partNumber })
            if (etag != null) UploadResult.Success(remoteKey, etag)
            else UploadResult.Retryable("complete failed", uploadId, parts)
        } catch (t: Throwable) {
            UploadResult.Retryable(t.message ?: "io error", existingUploadId, parts)
        }
    }

    private fun objectUrl(config: CloudConfig, key: String, query: String = ""): String {
        val base = config.endpoint.trimEnd('/')
        val keyPath = key.trimStart('/')
        val url = if (config.pathStyle) "$base/${config.bucket}/$keyPath" else {
            val u = base.toHttpUrl()
            "${u.scheme}://${config.bucket}.${u.host}${if (u.port in listOf(80, 443)) "" else ":${u.port}"}/$keyPath"
        }
        return if (query.isEmpty()) url else "$url?$query"
    }

    private fun initiate(config: CloudConfig, signer: AwsV4Signer, key: String): String? {
        val req = signer.sign(
            Request.Builder().url(objectUrl(config, key, "uploads"))
                .post(ByteArray(0).toRequestBody()).build(),
            System.currentTimeMillis(),
            mapOf("x-amz-checksum-algorithm" to "SHA256"), // each part will carry x-amz-checksum-sha256
        )
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return null
            if (!resp.isSuccessful) return null
            return UPLOAD_ID.find(body)?.groupValues?.get(1)
        }
    }

    private fun uploadPart(
        config: CloudConfig, signer: AwsV4Signer, key: String, uploadId: String,
        partNumber: Int, file: File, offset: Long, size: Long, checksumB64: String,
    ): String? {
        val req = signer.sign(
            Request.Builder().url(objectUrl(config, key, "partNumber=$partNumber&uploadId=$uploadId"))
                .put(fileSegmentBody(file, offset, size)).build(),
            System.currentTimeMillis(),
            mapOf("x-amz-checksum-sha256" to checksumB64), // S3/MinIO rejects the part on mismatch (BadDigest)
        )
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.header("ETag")
        }
    }

    private fun complete(
        config: CloudConfig, signer: AwsV4Signer, key: String, uploadId: String, parts: List<PartRef>,
    ): String? {
        val xml = buildString {
            append("<CompleteMultipartUpload>")
            parts.forEach { p ->
                append("<Part><PartNumber>${p.partNumber}</PartNumber>")
                append("<ETag>\"${p.etag.trim('"')}\"</ETag>")
                p.checksumSha256?.let { append("<ChecksumSHA256>$it</ChecksumSHA256>") }
                append("</Part>")
            }
            append("</CompleteMultipartUpload>")
        }
        val req = signer.sign(
            Request.Builder().url(objectUrl(config, key, "uploadId=$uploadId"))
                .post(xml.toRequestBody("application/xml".toMediaType())).build(),
            System.currentTimeMillis(),
        )
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null
            // S3 can return HTTP 200 and then stream an <Error> in the body for this call.
            if (isS3Error(body)) return null
            return ETAG_XML.find(body)?.groupValues?.get(1)?.trim('"') ?: resp.header("ETag") ?: "completed"
        }
    }

    /** Streams a [size]-byte window of [file] starting at [offset] without buffering it in memory. */
    private fun fileSegmentBody(file: File, offset: Long, size: Long): RequestBody = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = size
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                var remaining = size
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
    }

    private companion object {
        const val PART_SIZE = 8L * 1024 * 1024 // 8 MiB (S3 minimum part size is 5 MiB)
        val UPLOAD_ID = Regex("<UploadId>(.*?)</UploadId>")
        val ETAG_XML = Regex("<ETag>(.*?)</ETag>")
    }
}

/**
 * True if an S3 XML response body carries an `<Error>` element. CompleteMultipartUpload is the
 * notorious case: S3 may answer HTTP 200 and then stream `<Error>...</Error>` instead of a result.
 */
internal fun isS3Error(body: String): Boolean = body.contains("<Error>") || body.contains("<Error ")

/** Azure Blob upload — intentionally a stub per the plan (S3/MinIO are the shipped backends). */
class AzureBlobProvider @Inject constructor() : CloudStorageProvider {
    override val provider = CloudProvider.AZURE_BLOB
    override suspend fun upload(
        config: CloudConfig, file: File, remoteKey: String, sha256Hex: String,
        existingUploadId: String?, completedParts: List<PartRef>, progress: UploadProgress,
    ): UploadResult = UploadResult.Fatal("Azure Blob provider not yet implemented")
}
