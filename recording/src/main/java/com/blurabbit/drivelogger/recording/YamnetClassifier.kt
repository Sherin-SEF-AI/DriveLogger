package com.blurabbit.drivelogger.recording

import android.content.Context
import com.blurabbit.drivelogger.domain.model.DrivingEventType
import org.tensorflow.lite.task.audio.classifier.AudioClassifier

/**
 * On-device siren/horn detector backed by YAMNet (TFLite). The model lives at
 * `assets/yamnet.tflite` (TF Hub YAMNet *with metadata*). **Runtime-optional:** if the asset is
 * missing or the device can't run it, [available] is false and [classify] returns null — audio still
 * records, only live detection is skipped. Keeps the build green without shipping the binary asset.
 */
class YamnetClassifier(context: Context, scoreThreshold: Float = 0.4f) {

    private val classifier: AudioClassifier? = runCatching {
        AudioClassifier.createFromFile(context, MODEL_ASSET)
    }.getOrNull()

    val available: Boolean get() = classifier != null

    /** Samples the model expects per inference (e.g. 15600 @ 16 kHz); 0 if unavailable. */
    val requiredSamples: Int = runCatching { classifier?.requiredInputBufferSize?.toInt() ?: 0 }.getOrDefault(0)

    val sampleRate: Int = runCatching {
        classifier?.requiredTensorAudioFormat?.sampleRate ?: 16_000
    }.getOrDefault(16_000)

    private val threshold = scoreThreshold

    /** Classify a mono float window; returns the strongest siren/horn detection above threshold, else null. */
    fun classify(pcm: FloatArray): AudioDetection? {
        val c = classifier ?: return null
        return runCatching {
            val tensor = c.createInputTensorAudio()
            tensor.load(pcm)
            c.classify(tensor)
                .flatMap { it.categories }
                .mapNotNull { cat ->
                    val type = labelToEventType(cat.label)
                    if (type != null && cat.score >= threshold) AudioDetection(type, cat.score.toDouble(), cat.label) else null
                }
                .maxByOrNull { it.confidence }
        }.getOrNull()
    }

    private companion object {
        const val MODEL_ASSET = "yamnet.tflite"
    }
}

/** A positive audio classification mapped onto a driving-event type. */
data class AudioDetection(val type: DrivingEventType, val confidence: Double, val label: String)

/**
 * Maps a YAMNet/AudioSet label to a driving-event type. Sirens (incl. emergency-vehicle variants)
 * → SIREN; car/truck/air horns and honking → VEHICLE_HORN. Foghorn/train horn are intentionally
 * excluded. Returns null for unrelated labels. Pure → unit-tested.
 */
internal fun labelToEventType(label: String): DrivingEventType? {
    val l = label.lowercase()
    return when {
        "siren" in l || "emergency vehicle" in l -> DrivingEventType.SIREN
        "honk" in l || "car horn" in l || "truck horn" in l || "air horn" in l -> DrivingEventType.VEHICLE_HORN
        else -> null
    }
}
