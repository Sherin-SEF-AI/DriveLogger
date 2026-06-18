package com.blurabbit.drivelogger.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves per-trip artifact paths under app-private external storage:
 * `…/Android/data/<pkg>/files/trips/<tripId>/{trip.mcap, trip.mp4, metadata.json}`.
 * App-private scoped storage needs no extra permission and is cleaned up on uninstall.
 */
@Singleton
class TripStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "trips").apply { mkdirs() }

    fun tripDir(tripId: String): File = File(root, tripId).apply { mkdirs() }
    fun mcapFile(tripId: String): File = File(tripDir(tripId), "trip.mcap")
    fun mp4File(tripId: String): File = File(tripDir(tripId), "trip.mp4")
    fun metadataFile(tripId: String): File = File(tripDir(tripId), "metadata.json")
    fun audioFile(tripId: String): File = File(tripDir(tripId), "audio.wav")
    fun trackFile(tripId: String): File = File(tripDir(tripId), "track.json")
    fun hdMapFile(tripId: String): File = File(tripDir(tripId), "hdmap.json")

    fun freeBytes(): Long = android.os.StatFs(root.absolutePath).availableBytes

    /** Trip ids whose directory exists but whose MCAP lacks a valid footer (crashed mid-record). */
    fun allTripDirs(): List<File> = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
}
