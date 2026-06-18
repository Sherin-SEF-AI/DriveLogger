package com.blurabbit.drivelogger.upload

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.blurabbit.drivelogger.domain.model.CloudProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores cloud credentials in [EncryptedSharedPreferences] backed by an AndroidKeystore master
 * key — secrets never touch plaintext prefs, logs, or VCS.
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "cloud_credentials", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(config: CloudConfig) {
        prefs.edit().apply {
            val p = config.provider.name
            putString("$p.endpoint", config.endpoint)
            putString("$p.region", config.region)
            putString("$p.bucket", config.bucket)
            putString("$p.accessKey", config.accessKey)
            putString("$p.secretKey", config.secretKey)
            putBoolean("$p.pathStyle", config.pathStyle)
        }.apply()
    }

    fun load(provider: CloudProvider): CloudConfig? {
        val p = provider.name
        val endpoint = prefs.getString("$p.endpoint", null) ?: return null
        return CloudConfig(
            provider = provider,
            endpoint = endpoint,
            region = prefs.getString("$p.region", "us-east-1")!!,
            bucket = prefs.getString("$p.bucket", "")!!,
            accessKey = prefs.getString("$p.accessKey", "")!!,
            secretKey = prefs.getString("$p.secretKey", "")!!,
            pathStyle = prefs.getBoolean("$p.pathStyle", false),
        )
    }
}

/** Streaming SHA-256 of a file for end-to-end upload integrity verification. */
object Checksums {
    fun sha256(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Base64 SHA-256 of a `[offset, offset+size)` window of [file] — the value S3 expects in
     * `x-amz-checksum-sha256` for a multipart part. Streamed in 64 KiB blocks (no full-part buffering).
     */
    fun sha256Base64(file: java.io.File, offset: Long, size: Long): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            var remaining = size
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return android.util.Base64.encodeToString(md.digest(), android.util.Base64.NO_WRAP)
    }
}
