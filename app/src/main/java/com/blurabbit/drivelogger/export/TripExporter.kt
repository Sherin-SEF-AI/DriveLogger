package com.blurabbit.drivelogger.export

import android.content.Context
import com.blurabbit.drivelogger.recording.TripStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Bundles a trip's artifacts into a single `trip_<id>.zip` under the app cache for sharing via the
 * Android chooser. Data files (MCAP, metadata, audio, HD-map) are always included; the large MP4 is
 * added only when [includeVideo] is set.
 */
class TripExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: TripStorage,
) {
    fun export(tripId: String, includeVideo: Boolean): File {
        val files = buildList {
            add(storage.mcapFile(tripId))
            add(storage.metadataFile(tripId))
            add(storage.audioFile(tripId))
            add(storage.audioMetaFile(tripId))
            add(storage.hdMapFile(tripId))
            if (includeVideo) add(storage.mp4File(tripId))
        }.filter { it.exists() }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val dest = File(dir, "trip_$tripId.zip")
        writeZip(files, dest)
        return dest
    }
}

/** Writes [files] into [dest] as a flat ZIP (one entry per file, keyed by file name). */
internal fun writeZip(files: List<File>, dest: File) {
    ZipOutputStream(dest.outputStream().buffered()).use { zip ->
        val buf = ByteArray(64 * 1024)
        for (f in files) {
            zip.putNextEntry(ZipEntry(f.name))
            f.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf); if (n <= 0) break; zip.write(buf, 0, n)
                }
            }
            zip.closeEntry()
        }
    }
}
