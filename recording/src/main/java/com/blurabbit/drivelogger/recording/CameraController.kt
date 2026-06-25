package com.blurabbit.drivelogger.recording

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.blurabbit.drivelogger.core.clock.ClockSynchronizer
import com.blurabbit.drivelogger.proto.CameraFrameMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Records the road-facing video to per-segment MP4 files via CameraX [VideoCapture], while a
 * Camera2Interop session capture callback extracts per-frame metadata (exposure, ISO, focal length,
 * sensor timestamp) → [CameraFrameMeta] on `/camera/front`. Frame pixels stay in the MP4; only
 * references + capture params go to MCAP.
 *
 * Supports [rotate] (stop the current MP4, start a new one at a segment boundary) and
 * [requestQuality] (thermal step-down applied at the next rotation, avoiding mid-segment rebinds).
 *
 * NB: "front" here means *front-of-vehicle* (the phone's back camera mounted facing the road).
 */
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ClockSynchronizer,
) {
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null
    private var recorder: Recorder? = null
    private var owner: LifecycleOwner? = null
    private var onFrameMeta: ((CameraFrameMeta) -> Unit)? = null
    private val frameId = AtomicLong(0)
    @Volatile private var currentMp4: File? = null
    @Volatile private var videoUri: String = ""
    @Volatile private var currentQuality: Quality = Quality.FHD
    @Volatile private var targetQuality: Quality = Quality.FHD

    /** Request a quality (e.g. thermal step-down). Applied at the next [rotate]. */
    fun requestQuality(quality: Quality) { targetQuality = quality }

    @SuppressLint("MissingPermission", "RestrictedApi")
    suspend fun start(owner: LifecycleOwner, output: File, onFrameMeta: (CameraFrameMeta) -> Unit): Boolean {
        val cameraProvider = awaitFuture(ProcessCameraProvider.getInstance(context)) ?: return false
        provider = cameraProvider
        this.owner = owner
        this.onFrameMeta = onFrameMeta
        currentQuality = targetQuality
        return bindAndRecord(output)
    }

    /** Close the current MP4 and start a new one (segment boundary); re-binds if quality changed. */
    suspend fun rotate(newFile: File): Boolean {
        stopSegmentMp4()
        return startSegmentMp4(newFile)
    }

    /** Stop + finalize the current segment's MP4 (camera stays bound). Returns the finalized file. */
    suspend fun stopSegmentMp4(): File? {
        val file = currentMp4
        suspendOnMain { runCatching { recording?.stop() }; recording = null; true }
        return file
    }

    /** Start recording a new segment MP4; re-binds the camera if the target quality changed. */
    @SuppressLint("RestrictedApi")
    suspend fun startSegmentMp4(newFile: File): Boolean {
        val rec = recorder
        if (targetQuality != currentQuality || rec == null) {
            currentQuality = targetQuality
            return suspendOnMain { runCatching { provider?.unbindAll() }; bindOnMainAndRecord(newFile) }
        }
        return suspendOnMain {
            currentMp4 = newFile
            videoUri = newFile.name
            recording = rec.prepareRecording(context, FileOutputOptions.Builder(newFile).build())
                .start(ContextCompat.getMainExecutor(context)) { }
            true
        }
    }

    fun frameCount(): Long = frameId.get()

    fun stop() {
        runCatching { recording?.stop() }
        recording = null
        runCatching { provider?.unbindAll() }
        recorder = null
        owner = null
    }

    // ---- internals -----------------------------------------------------------------------

    private suspend fun bindAndRecord(output: File): Boolean = suspendOnMain { bindOnMainAndRecord(output) }

    @OptIn(ExperimentalCamera2Interop::class)
    @SuppressLint("MissingPermission", "RestrictedApi")
    private fun bindOnMainAndRecord(output: File): Boolean = try {
        val cameraProvider = provider!!
        val lifecycleOwner = owner!!
        val cb = onFrameMeta!!
        val (w, h) = dimsFor(currentQuality)

        val rec = Recorder.Builder().setQualitySelector(QualitySelector.from(currentQuality)).build()
        recorder = rec
        val videoBuilder = VideoCapture.Builder(rec)
        Camera2Interop.Extender(videoBuilder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    val unified = sync.observeAndConvert("camera", sensorTs)
                    cb(
                        CameraFrameMeta.newBuilder()
                            .setUnifiedNs(unified)
                            .setFrameId(frameId.getAndIncrement())
                            .setVideoUri(videoUri)
                            .setCameraId("front")
                            .setExposureTimeNs(result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L)
                            .setIso(result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0)
                            .setFocalLengthMm((result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f).toDouble())
                            .setWidth(w).setHeight(h)
                            .setCodec("h264")
                            .build(),
                    )
                }
            },
        )
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, videoBuilder.build())
        currentMp4 = output
        videoUri = output.name
        recording = rec.prepareRecording(context, FileOutputOptions.Builder(output).build())
            .start(ContextCompat.getMainExecutor(context)) { }
        true
    } catch (t: Throwable) {
        false
    }

    private fun dimsFor(q: Quality): Pair<Int, Int> = when (q) {
        Quality.UHD -> 3840 to 2160
        Quality.FHD -> 1920 to 1080
        Quality.HD -> 1280 to 720
        Quality.SD -> 720 to 480
        else -> 1920 to 1080
    }

    private suspend fun suspendOnMain(block: () -> Boolean): Boolean =
        suspendCancellableCoroutine { cont ->
            ContextCompat.getMainExecutor(context).execute { cont.resume(runCatching { block() }.getOrDefault(false)) }
        }

    private suspend fun <T> awaitFuture(future: com.google.common.util.concurrent.ListenableFuture<T>): T? =
        suspendCancellableCoroutine { cont ->
            future.addListener({ cont.resume(runCatching { future.get() }.getOrNull()) }, ContextCompat.getMainExecutor(context))
        }
}
