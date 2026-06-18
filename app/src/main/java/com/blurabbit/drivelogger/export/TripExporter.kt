package com.blurabbit.drivelogger.export

import android.content.Context
import com.blurabbit.drivelogger.recording.TripStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.CRC32
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

/**
 * Writes [files] into [dest] as a flat ZIP (one entry per file, keyed by file name) using the
 * **STORED** method (no DEFLATE). The trip artifacts are already compressed (MP4 = H.264, MCAP =
 * LZ4) so deflate buys ~nothing while costing large CPU — and, critically, a deflate stream
 * desyncs on a single corrupted byte, destroying everything downstream. STORED keeps each file's
 * bytes raw: a transfer error damages only its local region (the rest, incl. the MP4 moov, stays
 * recoverable) and the per-entry CRC-32 makes corruption detectable. STORED requires size + CRC up
 * front, so each file is read once to checksum, then once to copy.
 */
internal fun writeZip(files: List<File>, dest: File) {
    ZipOutputStream(dest.outputStream().buffered()).use { zip ->
        zip.setMethod(ZipOutputStream.STORED)
        val buf = ByteArray(1 shl 16)
        for (f in files) {
            val len = f.length()
            val entry = ZipEntry(f.name).apply {
                method = ZipEntry.STORED
                size = len
                compressedSize = len
                crc = crc32(f)
            }
            zip.putNextEntry(entry)
            f.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf); if (n <= 0) break; zip.write(buf, 0, n)
                }
            }
            zip.closeEntry()
        }
    }
}

private fun crc32(f: File): Long {
    val crc = CRC32()
    val buf = ByteArray(1 shl 16)
    f.inputStream().use { input ->
        while (true) { val n = input.read(buf); if (n <= 0) break; crc.update(buf, 0, n) }
    }
    return crc.value
}
