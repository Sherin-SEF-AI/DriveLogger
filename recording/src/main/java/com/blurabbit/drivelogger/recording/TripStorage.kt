package com.blurabbit.drivelogger.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves per-trip artifact paths under app-private external storage:
 * `…/Android/data/<pkg>/files/trips/<tripId>/{seg_NNN.mcap, seg_NNN.mp4, metadata.json}`.
 * App-private scoped storage needs no extra permission and is cleaned up on uninstall.
 *
 * Long recordings are split into numbered segments (see [RecordingConfig]) so file size and
 * crash-loss are bounded and each segment can upload + be deleted independently.
 */
@Singleton
class TripStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "trips").apply { mkdirs() }

    fun tripDir(tripId: String): File = File(root, tripId).apply { mkdirs() }

    /** Zero-padded segment artifact paths, e.g. `seg_000.mcap`. */
    fun segMcap(tripId: String, index: Int): File = File(tripDir(tripId), "seg_%03d.mcap".format(index))
    fun segMp4(tripId: String, index: Int): File = File(tripDir(tripId), "seg_%03d.mp4".format(index))
    fun metadataFile(tripId: String): File = File(tripDir(tripId), "metadata.json")

    /** All MCAP segments for a trip, ordered by index. */
    fun mcapSegments(tripId: String): List<File> =
        tripDir(tripId).listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".mcap") }
            ?.sortedBy { it.name } ?: emptyList()

    /** All MP4 segments for a trip, ordered by index. */
    fun segMp4Files(tripId: String): List<File> =
        tripDir(tripId).listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".mp4") }
            ?.sortedBy { it.name } ?: emptyList()

    fun freeBytes(): Long = android.os.StatFs(root.absolutePath).availableBytes
    fun dirSizeBytes(tripId: String): Long =
        tripDir(tripId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Delete all local files for a trip (DB rows are kept for history/retention accounting). */
    fun deleteTrip(tripId: String): Boolean = File(root, tripId).deleteRecursively()

    fun allTripDirs(): List<File> = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
}
