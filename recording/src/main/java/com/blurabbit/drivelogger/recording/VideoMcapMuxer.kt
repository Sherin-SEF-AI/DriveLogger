package com.blurabbit.drivelogger.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.core.mcap.McapAsyncWriter
import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import foxglove.CompressedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * EXPERIMENTAL (opt-in, default off). After a segment's MP4 is finalized, demux its compressed
 * frames and write them into the same segment's still-open MCAP as `foxglove.CompressedVideo` on
 * `/camera/front/video`, so the video plays on the Foxglove Studio timeline alongside sensors.
 *
 * Converts mp4 AVCC (length-prefixed NAL units) to Annex-B (start codes) and prepends parameter
 * sets (SPS/PPS, or VPS/SPS/PPS for HEVC) to key frames so each is independently decodable.
 *
 * Not yet verified against Foxglove playback on this hardware — hence opt-in.
 */
object VideoMcapMuxer {

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    suspend fun muxInto(writer: McapAsyncWriter, mp4: File, segmentStartEpochNs: Long): Int =
        withContext(Dispatchers.IO) {
            if (!mp4.exists() || mp4.length() == 0L) return@withContext 0
            val extractor = MediaExtractor()
            var written = 0
            try {
                extractor.setDataSource(mp4.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return@withContext 0
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
                val fmtToken = if (mime.contains("hevc")) "h265" else "h264"
                val paramSets = parameterSetsAnnexB(format)
                extractor.selectTrack(track)

                val buf = ByteBuffer.allocate(4 * 1024 * 1024)
                var frameId = 0L
                while (true) {
                    buf.clear()
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) break
                    val avcc = ByteArray(size).also { buf.get(it, 0, size) }
                    val isKey = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    val annexb = avccToAnnexB(avcc, if (isKey) paramSets else ByteArray(0))
                    val epochNs = segmentStartEpochNs + extractor.sampleTime * 1_000L
                    writer.write(
                        Topics.CAMERA_FRONT_VIDEO, epochNs,
                        CompressedVideo.newBuilder()
                            .setTimestamp(Timestamp.newBuilder()
                                .setSeconds(epochNs / 1_000_000_000L)
                                .setNanos((epochNs % 1_000_000_000L).toInt()))
                            .setFrameId("front")
                            .setData(ByteString.copyFrom(annexb))
                            .setFormat(fmtToken)
                            .build(),
                    )
                    written++; frameId++
                    extractor.advance()
                }
            } catch (_: Throwable) {
                // Best-effort: a muxing failure must never break the recording/finalize path.
            } finally {
                extractor.release()
            }
            written
        }

    /** SPS/PPS (+VPS for HEVC) from the track format, concatenated as Annex-B with start codes. */
    private fun parameterSetsAnnexB(format: MediaFormat): ByteArray {
        val out = ArrayList<Byte>()
        listOf("csd-0", "csd-1", "csd-2").forEach { key ->
            val bb = runCatching { format.getByteBuffer(key) }.getOrNull() ?: return@forEach
            val csd = ByteArray(bb.remaining()).also { bb.get(it) }
            // csd may already be Annex-B (contains start codes) — pass through; else wrap.
            if (containsStartCode(csd)) out.addAll(csd.toList())
            else { out.addAll(START_CODE.toList()); out.addAll(csd.toList()) }
        }
        return out.toByteArray()
    }

    /** Convert 4-byte-length-prefixed AVCC NAL units to Annex-B, prepending [prefix] (param sets). */
    private fun avccToAnnexB(avcc: ByteArray, prefix: ByteArray): ByteArray {
        val out = ArrayList<Byte>(avcc.size + prefix.size + 16)
        out.addAll(prefix.toList())
        var i = 0
        while (i + 4 <= avcc.size) {
            val nalLen = ((avcc[i].toInt() and 0xFF) shl 24) or ((avcc[i + 1].toInt() and 0xFF) shl 16) or
                ((avcc[i + 2].toInt() and 0xFF) shl 8) or (avcc[i + 3].toInt() and 0xFF)
            i += 4
            if (nalLen <= 0 || i + nalLen > avcc.size) break // not AVCC-4 as assumed; bail safely
            out.addAll(START_CODE.toList())
            for (j in 0 until nalLen) out.add(avcc[i + j])
            i += nalLen
        }
        return out.toByteArray()
    }

    private fun containsStartCode(b: ByteArray): Boolean =
        b.size >= 4 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() == 1
}
