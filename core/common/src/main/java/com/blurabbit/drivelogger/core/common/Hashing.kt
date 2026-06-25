package com.blurabbit.drivelogger.core.common

import java.io.File
import java.security.MessageDigest

/** Streaming file hashing for dataset-integrity manifests and upload verification. */
object Hashing {
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
