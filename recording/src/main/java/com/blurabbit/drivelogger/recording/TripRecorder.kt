package com.blurabbit.drivelogger.recording

import androidx.camera.video.Quality
import androidx.lifecycle.LifecycleOwner
import com.blurabbit.drivelogger.core.clock.ClockSynchronizer
import com.blurabbit.drivelogger.core.clock.DriftMonitor
import com.blurabbit.drivelogger.core.clock.MonotonicClock
import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.core.mcap.McapAsyncWriter
import com.blurabbit.drivelogger.core.mcap.McapWriterConfig
import com.blurabbit.drivelogger.core.mcap.TopicSchema
import com.blurabbit.drivelogger.domain.model.DrivingEvent
import com.blurabbit.drivelogger.domain.model.DrivingEventType
import com.blurabbit.drivelogger.domain.model.RecordingSession
import com.blurabbit.drivelogger.domain.model.TripStats
import com.blurabbit.drivelogger.domain.model.TripStatus
import com.blurabbit.drivelogger.domain.model.ArtifactKind
import com.blurabbit.drivelogger.domain.model.UploadStatus
import com.blurabbit.drivelogger.domain.model.UploadTask
import com.blurabbit.drivelogger.domain.repository.EventRepository
import com.blurabbit.drivelogger.domain.repository.HealthRepository
import com.blurabbit.drivelogger.domain.repository.TripRepository
import com.blurabbit.drivelogger.domain.repository.UploadRepository
import com.blurabbit.drivelogger.domain.repository.UploadTrigger
import com.blurabbit.drivelogger.events.AccelSample
import com.blurabbit.drivelogger.events.EventDetector
import com.blurabbit.drivelogger.events.GyroSample
import com.blurabbit.drivelogger.events.SpeedSample
import com.blurabbit.drivelogger.proto.CameraFrameMeta
import com.blurabbit.drivelogger.proto.DeviceTelemetry
import com.blurabbit.drivelogger.proto.GpsExtras
import com.blurabbit.drivelogger.proto.Vector3Stamped
import com.blurabbit.drivelogger.sensors.SensorRecord
import com.blurabbit.drivelogger.sensors.SensorSource
import com.blurabbit.drivelogger.sensors.impl.GnssSensorSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Owns the live recording pipeline. Merges every available [SensorSource] onto a single consumer
 * coroutine, stamps each sample on the unified clock, writes it to the MCAP actor, feeds the event
 * detector, and maintains live [RecordingState]. Camera frames are offered (lossless-best-effort)
 * directly to the writer.
 */
@Singleton
class TripRecorder @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val sensorSources: Set<@JvmSuppressWildcards SensorSource>,
    private val sync: ClockSynchronizer,
    private val drift: DriftMonitor,
    private val clock: MonotonicClock,
    private val mcapConfig: McapWriterConfig,
    private val config: RecordingConfig,
    private val detector: EventDetector,
    private val storage: TripStorage,
    private val camera: CameraController,
    private val tripRepo: TripRepository,
    private val eventRepo: EventRepository,
    private val healthRepo: HealthRepository,
    private val uploadRepo: UploadRepository,
    private val uploadTrigger: UploadTrigger,
    private val retention: RetentionManager,
    private val metadataGen: MetadataGenerator,
) : RecordingController {

    private val _state = MutableStateFlow(RecordingState())
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val lifecycle = Mutex()
    private val rotateMutex = Mutex()
    private var scope: CoroutineScope? = null
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var writer: McapAsyncWriter? = null
    private var sessionId: Long = -1
    private var lifecycleOwner: LifecycleOwner? = null
    @Volatile private var cameraOn = false

    // Segment state.
    private var segmentIndex = 0
    private var segmentStartNs = 0L
    private var currentSchemas: List<TopicSchema> = emptyList()

    // Counters (some written from the camera thread → atomic).
    private var imuSamples = 0L
    private var gpsSamples = 0L
    private val frameCount = AtomicLong(0)
    private var eventCount = 0L
    private val droppedWrites = AtomicLong(0)
    private var distanceMeters = 0.0
    private var maxSpeedMps = 0.0
    private var currentSpeedMps = 0.0
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTelemetryWriteNs = 0L

    private var tripId: String? = null
    private var startElapsedNs = 0L

    /** The foreground service (a LifecycleOwner) calls this so the camera can bind. */
    fun attachLifecycle(owner: LifecycleOwner?) { lifecycleOwner = owner }

    override suspend fun start(tripId: String) = lifecycle.withLock {
        if (_state.value.phase != RecordingPhase.IDLE) return@withLock
        val trip = tripRepo.getTrip(tripId) ?: return@withLock
        this.tripId = tripId
        startElapsedNs = clock.nowNanos()
        clock.captureEpochAnchor() // anchor monotonic→epoch so MCAP log_time is real wall time
        resetCounters()
        sync.reset(); drift.reset()
        segmentIndex = 0
        segmentStartNs = startElapsedNs

        val available = sensorSources.filter { it.isAvailable() }
        currentSchemas = buildSchemas(available)
        val mcap = storage.segMcap(tripId, segmentIndex)
        val w = McapAsyncWriter(mcap, mcapConfig, currentSchemas)
        writer = w
        writeCalibration(w)

        sessionId = tripRepo.addSession(
            RecordingSession(
                tripId = tripId, segmentIndex = segmentIndex, startElapsedNs = startElapsedNs,
                endElapsedNs = null, mcapPath = mcap.absolutePath, mp4Path = null,
            ),
        )
        tripRepo.updateStatus(tripId, TripStatus.RECORDING)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        launchPipeline(s, available)
        maybeStartCamera(s)
        _state.value = RecordingState(phase = RecordingPhase.RECORDING, tripId = tripId, startElapsedNs = startElapsedNs)
    }

    private suspend fun writeCalibration(w: McapAsyncWriter) {
        // Per-segment camera calibration (intrinsics + distortion) as a portable MCAP attachment.
        CameraCalibration.readBackCameraJson(appContext)?.let { cal ->
            w.attachment("calibration.json", "application/json", cal.toByteArray(), clock.toEpochNanos(clock.nowNanos()))
        }
    }

    private fun launchPipeline(s: CoroutineScope, available: List<SensorSource>) {
        // Single consumer over all sensor flows — keeps the event detector single-threaded.
        val merged = available.map { it.start(s).buffer(256) }
        s.launch { merge(*merged.toTypedArray()).collect { handleRecord(it) } }

        // Driving events → /events topic + Room.
        s.launch {
            detector.events.collect { ev ->
                runCatching { writer?.write(Topics.EVENTS, clock.toEpochNanos(ev.unifiedNs), ev) }
                eventCount++
                eventRepo.insert(
                    DrivingEvent(
                        tripId = tripId!!, type = ev.type.toDomain(), confidence = ev.confidence,
                        unifiedTsNs = ev.unifiedNs, latitude = ev.latitude, longitude = ev.longitude,
                        speedMps = ev.speedMps, evidenceJson = ev.evidenceJson,
                    ),
                )
            }
        }

        // Data-quality warnings.
        s.launch {
            drift.warnings.collect { warn ->
                _state.value = _state.value.copy(
                    warnings = (_state.value.warnings + "${warn.sourceId}: ${warn.kind} ${warn.detail}").takeLast(10),
                )
            }
        }

        // Live state ticker (~2 Hz).
        s.launch { stateTicker(available) }
    }

    private suspend fun handleRecord(record: SensorRecord) {
        drift.validate(record.topic, record.unifiedTsNs, null)
        // log_time = epoch (for tooling); message body keeps raw unified_ns for cross-sensor sync.
        runCatching { writer?.write(record.topic, clock.toEpochNanos(record.unifiedTsNs), record.message) }

        when (record.topic) {
            Topics.IMU_LINEAR_ACCEL -> {
                imuSamples++
                (record.message as? Vector3Stamped)?.let {
                    detector.onAccel(AccelSample(record.unifiedTsNs, it.x, it.y, it.z))
                }
            }
            Topics.IMU_GYRO -> {
                imuSamples++
                (record.message as? Vector3Stamped)?.let {
                    detector.onGyro(GyroSample(record.unifiedTsNs, it.x, it.y, it.z))
                }
            }
            Topics.IMU_ACCEL, Topics.IMU_MAG, Topics.IMU_ROTATION,
            Topics.IMU_GRAVITY -> imuSamples++

            Topics.GPS_FIX -> {
                gpsSamples++
                (record.message as? foxglove.LocationFix)?.let { fix ->
                    updateDistance(fix.latitude, fix.longitude)
                }
            }
            Topics.GNSS_RAW -> (record.message as? GpsExtras)?.let { ex ->
                currentSpeedMps = ex.speedMps
                if (ex.speedMps > maxSpeedMps) maxSpeedMps = ex.speedMps
                detector.onSpeed(SpeedSample(record.unifiedTsNs, ex.speedMps, lastLat ?: 0.0, lastLon ?: 0.0))
            }
            Topics.DEVICE_TELEMETRY -> (record.message as? DeviceTelemetry)?.let { persistTelemetry(it) }
        }
        maybeRotate()
    }

    /** Roll to a new segment when the time or size bound is reached (runs on the consumer coroutine). */
    private suspend fun maybeRotate() {
        val w = writer ?: return
        val now = clock.nowNanos()
        val bySize = w.approxBytes() >= config.segmentBytes
        val byTime = now - segmentStartNs >= config.segmentSeconds * 1_000_000_000L
        if (!bySize && !byTime) return
        if (!rotateMutex.tryLock()) return
        try {
            val id = tripId ?: return
            val closingIndex = segmentIndex
            // Build the new segment writer first, then swap, then close the old one (no write gap).
            segmentIndex += 1
            segmentStartNs = now
            val newMcap = storage.segMcap(id, segmentIndex)
            val nw = McapAsyncWriter(newMcap, mcapConfig, currentSchemas)
            writeCalibration(nw)
            val old = writer
            writer = nw
            sessionId = tripRepo.addSession(
                RecordingSession(
                    tripId = id, segmentIndex = segmentIndex, startElapsedNs = now,
                    endElapsedNs = null, mcapPath = newMcap.absolutePath, mp4Path = null,
                ),
            )
            old?.close()
            tripRepo.closeSession(
                /* old sessionId is now overwritten; close the prior segment via its index */
                sessionForIndex(id, closingIndex), now,
                if (cameraOn) storage.segMp4(id, closingIndex).absolutePath else null,
            )
            if (cameraOn) camera.rotate(storage.segMp4(id, segmentIndex))
            enqueueSegmentUpload(id, closingIndex)
        } finally {
            rotateMutex.unlock()
        }
    }

    private suspend fun sessionForIndex(tripId: String, index: Int): Long =
        tripRepo.sessionsFor(tripId).firstOrNull { it.segmentIndex == index }?.id ?: -1

    private suspend fun maybeStartCamera(s: CoroutineScope) {
        val owner = lifecycleOwner ?: return
        if (!hasCameraPermission()) return
        val mp4 = storage.segMp4(tripId!!, segmentIndex)
        val ok = camera.start(owner, mp4) { meta: CameraFrameMeta ->
            if (writer?.offer(Topics.CAMERA_FRONT, clock.toEpochNanos(meta.unifiedNs), meta) == true) frameCount.incrementAndGet()
            else droppedWrites.incrementAndGet()
        }
        cameraOn = ok
        if (ok) tripRepo.closeSession(sessionId, clock.nowNanos(), mp4.absolutePath)
    }

    override suspend fun pause() = lifecycle.withLock {
        if (_state.value.phase != RecordingPhase.RECORDING) return@withLock
        camera.stop()
        // Cancel the pipeline children but keep the scope + writer open across pause.
        scope?.let { sc -> sc.coroutineContext[Job]?.children?.forEach { it.cancelAndJoin() } }
        writer?.flush()
        sensorSources.forEach { it.stop() }
        _state.value = _state.value.copy(phase = RecordingPhase.PAUSED)
    }

    override suspend fun resume() = lifecycle.withLock {
        if (_state.value.phase != RecordingPhase.PAUSED) return@withLock
        val s = scope ?: return@withLock
        writer ?: return@withLock
        val available = sensorSources.filter { it.isAvailable() }
        launchPipeline(s, available)
        maybeStartCamera(s)
        _state.value = _state.value.copy(phase = RecordingPhase.RECORDING)
    }

    override suspend fun stop() = lifecycle.withLock {
        val id = tripId ?: return@withLock
        _state.value = _state.value.copy(phase = RecordingPhase.STOPPING)
        camera.stop()
        scope?.let { sc -> sc.coroutineContext[Job]?.children?.forEach { runCatching { it.cancelAndJoin() } } }
        sensorSources.forEach { it.stop() }

        val durationNs = clock.nowNanos() - startElapsedNs
        val avgSpeed = if (durationNs > 0) distanceMeters / (durationNs / 1e9) else 0.0
        val stats = TripStats(
            distanceMeters = distanceMeters, maxSpeedMps = maxSpeedMps, avgSpeedMps = avgSpeed,
            gpsSamples = gpsSamples, imuSamples = imuSamples, frameCount = frameCount.get(), eventCount = eventCount,
        )
        val endWallMs = clock.wallEpochNanosNow() / 1_000_000
        tripRepo.updateStats(id, stats)
        // Mark STOPPED + end time BEFORE generating metadata so the manifest captures the real bounds.
        tripRepo.updateStatus(id, TripStatus.STOPPED, endWallMs = endWallMs)

        writer?.let { w ->
            w.metadata("trip", mapOf(
                "trip_id" to id,
                "distance_m" to distanceMeters.toString(),
                "duration_s" to (durationNs / 1e9).toString(),
            ))
            w.close()
        }
        writer = null
        tripRepo.closeSession(sessionId, clock.nowNanos(), if (cameraOn) storage.segMp4(id, segmentIndex).absolutePath else null)

        // metadata.json sidecar over all segments (file sizes + end time now final).
        tripRepo.getTrip(id)?.let { trip ->
            metadataGen.write(
                trip.copy(stats = stats), durationNs,
                storage.mcapSegments(id),
                storage.segMp4Files(id),
                storage.metadataFile(id),
            )
        }

        // Hands-off upload: enqueue every segment + the metadata, then kick the worker.
        enqueueTripUpload(id)
        // Reclaim space from older, fully-uploaded trips (keeps last N).
        runCatching { retention.sweep() }

        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        tripId = null
        cameraOn = false
        _state.value = RecordingState(phase = RecordingPhase.IDLE)
    }

    // ---- auto-upload ---------------------------------------------------------------------

    private suspend fun enqueueSegmentUpload(tripId: String, index: Int) {
        if (!config.autoUpload) return
        val provider = uploadTrigger.defaultProvider() ?: return
        val prefix = remotePrefix(tripId)
        val artifacts = buildList {
            storage.segMcap(tripId, index).takeIf { it.exists() }?.let { add(it to ArtifactKind.MCAP) }
            storage.segMp4(tripId, index).takeIf { it.exists() }?.let { add(it to ArtifactKind.MP4) }
        }
        artifacts.forEach { (file, kind) ->
            uploadRepo.enqueue(
                UploadTask(
                    tripId = tripId, artifact = kind, provider = provider,
                    localPath = file.absolutePath, remoteKey = "$prefix/${file.name}",
                    status = UploadStatus.PENDING, totalBytes = file.length(),
                ),
            )
        }
        uploadTrigger.schedule()
    }

    private suspend fun enqueueTripUpload(tripId: String) {
        val provider = if (config.autoUpload) uploadTrigger.defaultProvider() else null
        if (provider == null) return
        val prefix = remotePrefix(tripId)
        val files = storage.mcapSegments(tripId).map { it to ArtifactKind.MCAP } +
            storage.segMp4Files(tripId).map { it to ArtifactKind.MP4 } +
            (storage.metadataFile(tripId).takeIf { it.exists() }?.let { listOf(it to ArtifactKind.METADATA) } ?: emptyList())
        files.forEach { (file, kind) ->
            uploadRepo.enqueue(
                UploadTask(
                    tripId = tripId, artifact = kind, provider = provider,
                    localPath = file.absolutePath, remoteKey = "$prefix/${file.name}",
                    status = UploadStatus.PENDING, totalBytes = file.length(),
                ),
            )
        }
        uploadTrigger.schedule()
    }

    private fun remotePrefix(tripId: String): String {
        val date = clock.wallEpochNanosNow() / 1_000_000
        // yyyy-MM-dd derived without locale-format dependency on the hot path.
        val d = java.util.Date(date)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return "${fmt.format(d)}/$tripId"
    }

    private suspend fun stateTicker(available: List<SensorSource>) {
        val gnss = available.filterIsInstance<GnssSensorSource>().firstOrNull()
        while (true) {
            val free = storage.freeBytes()
            _state.value = _state.value.copy(
                durationNs = clock.nowNanos() - startElapsedNs,
                currentSpeedMps = currentSpeedMps,
                satellitesTotal = gnss?.satelliteCounts()?.first ?: 0,
                satellitesUsed = gnss?.satelliteCounts()?.second ?: 0,
                distanceMeters = distanceMeters, maxSpeedMps = maxSpeedMps,
                gpsSamples = gpsSamples, imuSamples = imuSamples,
                frameCount = frameCount.get(), eventCount = eventCount,
                storageFreeBytes = free,
                droppedWrites = droppedWrites.get(),
                sensorHealth = available.map { it.health() },
            )
            // Storage watchdog: stop gracefully before the disk fills (the 14 GB problem).
            if (free < config.freeSpaceFloorBytes) {
                pushWarning("low storage (${free / (1024 * 1024)} MB) — stopping")
                triggerGracefulStop(); return
            }
            kotlinx.coroutines.delay(500)
        }
    }

    /** Stop from outside the recording scope so cancelling the scope can't deadlock the caller. */
    private fun triggerGracefulStop() {
        watchdogScope.launch { runCatching { stop() } }
    }

    private fun pushWarning(msg: String) {
        _state.value = _state.value.copy(warnings = (_state.value.warnings + msg).takeLast(10))
    }

    private suspend fun persistTelemetry(t: DeviceTelemetry) {
        // Throttle Room writes to ~ every 10 s; the full-rate stream still lands in MCAP.
        if (t.unifiedNs - lastTelemetryWriteNs < 10_000_000_000L) return
        lastTelemetryWriteNs = t.unifiedNs
        healthRepo.recordDevice(
            com.blurabbit.drivelogger.domain.model.DeviceHealthSample(
                tripId = tripId, unifiedTsNs = t.unifiedNs, batteryPct = t.batteryPct.nanGuard(), charging = t.charging,
                cpuTemperatureC = t.cpuTemperatureC.nanGuard(), ramUsedBytes = t.ramUsedBytes, ramTotalBytes = t.ramTotalBytes,
                storageFreeBytes = t.storageFreeBytes, storageTotalBytes = t.storageTotalBytes,
                networkType = t.networkType, thermalStatus = t.thermalStatus,
            ),
        )
        _state.value = _state.value.copy(batteryPct = t.batteryPct, thermalStatus = t.thermalStatus)

        // Active mitigation: thermal step-down (applied at next segment rotation) + low-battery stop.
        camera.requestQuality(qualityForThermal(t.thermalStatus))
        if (!t.charging && t.batteryPct in 0.0..config.minBatteryPct) {
            pushWarning("battery ${t.batteryPct.toInt()}% — stopping")
            triggerGracefulStop()
        }
    }

    private fun qualityForThermal(status: Int): Quality = when {
        status >= 4 -> Quality.SD      // CRITICAL
        status == 3 -> Quality.HD      // SEVERE
        status == 2 -> Quality.HD      // MODERATE
        else -> Quality.FHD            // NONE / LIGHT
    }

    private fun buildSchemas(available: List<SensorSource>): List<TopicSchema> {
        val map = LinkedHashMap<String, TopicSchema>()
        available.forEach { src ->
            src.topics.forEach { td -> map[td.topic] = TopicSchema(td.topic, td.descriptor, td.metadata) }
        }
        // Always include camera + events channels even before their first message.
        map[Topics.CAMERA_FRONT] = TopicSchema(Topics.CAMERA_FRONT, CameraFrameMeta.getDescriptor())
        map[Topics.EVENTS] = TopicSchema(Topics.EVENTS, com.blurabbit.drivelogger.proto.DrivingEvent.getDescriptor())
        return map.values.toList()
    }

    private fun updateDistance(lat: Double, lon: Double) {
        val pLat = lastLat; val pLon = lastLon
        if (pLat != null && pLon != null) {
            val d = haversine(pLat, pLon, lat, lon)
            if (d in 0.1..500.0) distanceMeters += d // ignore jitter and GPS jumps
        }
        lastLat = lat; lastLon = lon
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun hasCameraPermission(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun resetCounters() {
        imuSamples = 0; gpsSamples = 0; frameCount.set(0); eventCount = 0; droppedWrites.set(0)
        distanceMeters = 0.0; maxSpeedMps = 0.0; currentSpeedMps = 0.0
        lastLat = null; lastLon = null; lastTelemetryWriteNs = 0
    }
}

/** SQLite stores NaN/Infinity as NULL, breaking NOT NULL columns — coerce to a safe sentinel. */
private fun Double.nanGuard(sentinel: Double = -1.0): Double = if (isFinite()) this else sentinel

private fun com.blurabbit.drivelogger.proto.DrivingEvent.EventType.toDomain(): DrivingEventType = when (this) {
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.HARD_BRAKING -> DrivingEventType.HARD_BRAKING
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SUDDEN_ACCELERATION -> DrivingEventType.SUDDEN_ACCELERATION
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SHARP_TURN -> DrivingEventType.SHARP_TURN
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.POTHOLE_IMPACT -> DrivingEventType.POTHOLE_IMPACT
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SPEED_BUMP -> DrivingEventType.SPEED_BUMP
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.RAPID_LANE_CHANGE -> DrivingEventType.RAPID_LANE_CHANGE
    else -> DrivingEventType.AGGRESSIVE_DRIVING
}
