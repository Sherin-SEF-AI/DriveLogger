package com.blurabbit.drivelogger.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import com.blurabbit.drivelogger.domain.model.DrivingEventType
import com.blurabbit.drivelogger.proto.AudioChunkMeta
import com.blurabbit.drivelogger.proto.AudioFrame
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/** A siren/horn detection at a point in time (the recorder attaches GPS/speed before logging). */
data class AudioEvent(val unifiedNs: Long, val type: DrivingEventType, val confidence: Double, val label: String)

/**
 * Records the microphone to a 16 kHz mono PCM `audio.wav` sidecar on a dedicated thread, emits
 * per-chunk [AudioChunkMeta] (level/index) on [meta], and runs [YamnetClassifier] over each chunk to
 * emit siren/horn [AudioEvent]s on [events]. Parallels [CameraController]: pixels/PCM stay in the
 * sidecar; only metadata + detections go to MCAP. Degrades to record-only if the model is absent.
 */
@Singleton
class AudioController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _meta = MutableSharedFlow<AudioChunkMeta>(extraBufferCapacity = 32)
    val meta: SharedFlow<AudioChunkMeta> = _meta
    private val _events = MutableSharedFlow<AudioEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AudioEvent> = _events
    // Timestamped PCM frames for the MCAP (/audio/pcm). Best-effort (audio.wav is the lossless copy).
    private val _frames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<AudioFrame> = _frames

    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // caller guarantees RECORD_AUDIO before start()
    fun start(output: File): Boolean {
        if (running) return false
        val classifier = YamnetClassifier(context)
        val sr = if (classifier.available) classifier.sampleRate else SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr * 2),
            )
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }

        running = true
        thread = Thread({ captureLoop(record, output, sr, classifier) }, "audio-capture").also { it.start() }
        return true
    }

    private fun captureLoop(record: AudioRecord, output: File, sr: Int, classifier: YamnetClassifier) {
        val raf = RandomAccessFile(output, "rw")
        raf.setLength(0)
        raf.write(wavHeader(sr, 1, 0)) // placeholder; sizes rewritten on stop
        var dataBytes = 0L
        var sampleIndex = 0L
        var totalRead = 0L
        var anchorFrame = 0L; var anchorNs = 0L; var haveAnchor = false
        val nsPerSample = 1_000_000_000L / sr
        val chunkTarget = sr // ~1 s
        val chunk = FloatArray(chunkTarget)
        var filled = 0
        val read = ShortArray(minOf(sr, 4096))
        val pcm = ByteArray(read.size * 2)

        runCatching { record.startRecording() }
        try {
            while (running) {
                val n = record.read(read, 0, read.size)
                if (n <= 0) continue
                // Append PCM16-LE to the WAV data section.
                for (i in 0 until n) {
                    val s = read[i].toInt()
                    pcm[i * 2] = (s and 0xFF).toByte()
                    pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                raf.write(pcm, 0, n * 2)
                dataBytes += n * 2

                // Emit a HAL-anchored PCM frame into the MCAP (/audio/pcm). Refresh the HAL anchor,
                // then back-project this block's start sample onto it (jitter-free, sample-exact).
                val blockStart = totalRead
                totalRead += n
                if (record.getTimestamp(audioTs, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS && audioTs.framePosition > 0) {
                    anchorFrame = audioTs.framePosition; anchorNs = audioTs.nanoTime; haveAnchor = true
                }
                val frameNs = if (haveAnchor) anchorNs + (blockStart - anchorFrame) * nsPerSample else SystemClock.elapsedRealtimeNanos()
                _frames.tryEmit(
                    AudioFrame.newBuilder()
                        .setUnifiedNs(frameNs).setSampleIndex(blockStart)
                        .setSampleRateHz(sr).setChannels(1)
                        .setPcmS16Le(ByteString.copyFrom(pcm, 0, n * 2))
                        .build(),
                )

                // Accumulate a ~1 s analysis window.
                var i = 0
                while (i < n) {
                    chunk[filled++] = read[i] / 32768f
                    i++
                    if (filled == chunkTarget) {
                        emitChunk(record, chunk, sampleIndex, sr, classifier)
                        sampleIndex += chunkTarget
                        filled = 0
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            // Finalize the WAV header now that data length is known.
            runCatching {
                raf.seek(0); raf.write(wavHeader(sr, 1, dataBytes)); raf.close()
            }
        }
    }

    private val audioTs = AudioTimestamp()

    private fun emitChunk(record: AudioRecord, chunk: FloatArray, fallbackSample: Long, sr: Int, classifier: YamnetClassifier) {
        // Anchor on the audio HAL clock (frame position ↔ boottime ns) — jitter-free, unlike the
        // flush/emit time. BOOTTIME shares the unified elapsedRealtime base. Fall back if unavailable.
        val (sampleIndex, unified) =
            if (record.getTimestamp(audioTs, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS && audioTs.framePosition > 0)
                audioTs.framePosition to audioTs.nanoTime
            else
                fallbackSample to SystemClock.elapsedRealtimeNanos()

        var sumSq = 0.0; var peak = 0f
        for (v in chunk) { sumSq += v * v; val a = if (v < 0) -v else v; if (a > peak) peak = a }
        val rms = sqrt(sumSq / chunk.size)
        _meta.tryEmit(
            AudioChunkMeta.newBuilder()
                .setUnifiedNs(unified)
                .setSampleIndex(sampleIndex)
                .setRmsDbfs(dbfs(rms))
                .setPeakDbfs(dbfs(peak.toDouble()))
                .setSampleRateHz(sr)
                .setChannels(1)
                .build(),
        )
        classifier.classify(chunk)?.let { d ->
            _events.tryEmit(AudioEvent(unified, d.type, d.confidence, d.label))
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(2_000) }
        thread = null
    }

    private fun dbfs(amplitude: Double): Double = if (amplitude <= 1e-7) -160.0 else 20.0 * log10(amplitude)

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** Canonical 44-byte PCM WAV header. [dataLen] is the byte length of the audio samples. */
internal fun wavHeader(sampleRate: Int, channels: Int, dataLen: Long): ByteArray {
    val byteRate = sampleRate * channels * 2
    val blockAlign = channels * 2
    val riffLen = 36 + dataLen
    val h = java.io.ByteArrayOutputStream(44)
    fun str(s: String) = h.write(s.toByteArray(Charsets.US_ASCII))
    fun u32(v: Long) { for (i in 0 until 4) h.write(((v shr (8 * i)) and 0xFF).toInt()) }
    fun u16(v: Int) { h.write(v and 0xFF); h.write((v shr 8) and 0xFF) }
    str("RIFF"); u32(riffLen); str("WAVE")
    str("fmt "); u32(16); u16(1); u16(channels); u32(sampleRate.toLong()); u32(byteRate.toLong()); u16(blockAlign); u16(16)
    str("data"); u32(dataLen)
    return h.toByteArray()
}
