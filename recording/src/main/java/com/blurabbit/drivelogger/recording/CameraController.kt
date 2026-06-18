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
import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.proto.CameraFrameMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Records the road-facing video to an MP4 sidecar via CameraX [VideoCapture], while a
 * Camera2Interop session capture callback extracts per-frame metadata (exposure, ISO, focal
 * length, sensor timestamp) → [CameraFrameMeta] on `/camera/front`. Frame pixels stay in the MP4;
 * only references + capture params go to MCAP, keeping the log small.
 *
 * NB: "front" here means *front-of-vehicle* (the phone's back camera mounted facing the road).
 */
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ClockSynchronizer,
) {
    private var recording: Recording? = null
    private var provider: ProcessCameraProvider? = null
    private val frameId = AtomicLong(0)
    @Volatile private var videoUri: String = "trip.mp4"

    @OptIn(ExperimentalCamera2Interop::class)
    @SuppressLint("MissingPermission", "RestrictedApi")
    suspend fun start(
        owner: LifecycleOwner,
        output: File,
        onFrameMeta: (CameraFrameMeta) -> Unit,
    ): Boolean {
        val cameraProvider = awaitFuture(ProcessCameraProvider.getInstance(context)) ?: return false
        provider = cameraProvider
        videoUri = output.name

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD)) // 1080p
            .build()

        // Attach the per-frame metadata callback to the SAME builder we bind.
        val videoBuilder = VideoCapture.Builder(recorder)
        Camera2Interop.Extender(videoBuilder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    val unified = sync.observeAndConvert("camera", sensorTs)
                    val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    val focal = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f
                    onFrameMeta(
                        CameraFrameMeta.newBuilder()
                            .setUnifiedNs(unified)
                            .setFrameId(frameId.getAndIncrement())
                            .setVideoUri(videoUri)
                            .setCameraId("front")
                            .setExposureTimeNs(exposure)
                            .setIso(iso)
                            .setFocalLengthMm(focal.toDouble())
                            .setWidth(1920).setHeight(1080)
                            .setCodec("h264")
                            .build(),
                    )
                }
            },
        )
        val videoCapture = videoBuilder.build()

        return suspendCancellableCoroutine { cont ->
            ContextCompat.getMainExecutor(context).execute {
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)
                    recording = recorder
                        .prepareRecording(context, FileOutputOptions.Builder(output).build())
                        .start(ContextCompat.getMainExecutor(context)) { /* VideoRecordEvent stream */ }
                    cont.resume(true)
                } catch (t: Throwable) {
                    cont.resume(false)
                }
            }
        }
    }

    fun frameCount(): Long = frameId.get()

    fun stop() {
        runCatching { recording?.stop() }
        recording = null
        runCatching { provider?.unbindAll() }
        provider = null
    }

    private suspend fun <T> awaitFuture(future: com.google.common.util.concurrent.ListenableFuture<T>): T? =
        suspendCancellableCoroutine { cont ->
            future.addListener({
                cont.resume(runCatching { future.get() }.getOrNull())
            }, ContextCompat.getMainExecutor(context))
        }
}
