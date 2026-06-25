package com.blurabbit.drivelogger.recording

import com.blurabbit.drivelogger.core.mcap.McapRecoveryTool
import com.blurabbit.drivelogger.domain.model.TripStatus
import com.blurabbit.drivelogger.domain.repository.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On startup, finalizes any trip left in RECORDING/PAUSED (the process was killed mid-write):
 * runs [McapRecoveryTool] to rebuild a valid MCAP, then marks the trip STOPPED. Idempotent.
 */
@Singleton
class RecoveryManager @Inject constructor(
    private val storage: TripStorage,
    private val tripRepo: TripRepository,
) {
    suspend fun recoverInterruptedTrips(): Int = withContext(Dispatchers.IO) {
        var recovered = 0
        tripRepo.inProgressTrips().forEach { trip ->
            val segments = storage.mcapSegments(trip.id)
            if (segments.isEmpty()) {
                tripRepo.updateStatus(trip.id, TripStatus.FAILED)
                return@forEach
            }
            // Only the last segment can be truncated (a crash mid-write); earlier ones are finalized.
            segments.forEach { mcap ->
                if (mcap.length() > 0 && !hasValidFooter(mcap)) {
                    val tmp = File(mcap.parentFile, mcap.name + ".recovered")
                    val result = McapRecoveryTool.recover(mcap, tmp)
                    if (result != null && tmp.exists()) {
                        if (mcap.delete()) tmp.renameTo(mcap) else tmp.delete()
                        recovered++
                    }
                }
            }
            tripRepo.updateStatus(trip.id, TripStatus.STOPPED)
        }
        recovered
    }

    /** Quick check: a finalized MCAP ends with the 8-byte magic. */
    private fun hasValidFooter(file: File): Boolean {
        if (file.length() < 8) return false
        val tail = ByteArray(8)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - 8); raf.readFully(tail)
        }
        // \x89 M C A P 0 \r \n
        return tail[0] == 0x89.toByte() && tail[1] == 'M'.code.toByte() && tail[6] == '\r'.code.toByte()
    }
}
