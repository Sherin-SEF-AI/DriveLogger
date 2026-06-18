package com.blurabbit.drivelogger.upload

/**
 * Pure-Kotlin (de)serialization of completed multipart [PartRef]s for persistence in the upload
 * queue. Avoids `org.json` so it is unit-testable on the JVM without Robolectric.
 *
 * Format: records joined by `;`, fields by `|` — `partNumber|etag|size|checksum`. ETag quotes are
 * stripped on encode (re-added by the provider's complete() XML). Safe because hex/quoted ETags and
 * base64 SHA-256 checksums never contain `|` or `;`.
 */
internal object PartCodec {
    fun encode(parts: List<PartRef>): String =
        parts.joinToString(";") { p ->
            "${p.partNumber}|${p.etag.trim('"')}|${p.size}|${p.checksumSha256 ?: ""}"
        }

    fun decode(encoded: String?): List<PartRef> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split(";").filter { it.isNotBlank() }.mapNotNull { rec ->
            val f = rec.split("|")
            if (f.size < 3) return@mapNotNull null
            val number = f[0].toIntOrNull() ?: return@mapNotNull null
            val size = f[2].toLongOrNull() ?: return@mapNotNull null
            PartRef(number, f[1], size, f.getOrNull(3)?.takeIf { it.isNotEmpty() })
        }
    }
}
