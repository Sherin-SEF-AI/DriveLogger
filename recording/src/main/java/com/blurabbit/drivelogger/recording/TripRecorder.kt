package com.blurabbit.drivelogger.recording

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
import com.blurabbit.drivelogger.domain.repository.EventRepository
import com.blurabbit.drivelogger.domain.repository.HealthRepository
import com.blurabbit.drivelogger.domain.repository.TripRepository
import com.blurabbit.drivelogger.events.AccelSample
import com.blurabbit.drivelogger.events.EventDetector
import com.blurabbit.drivelogger.events.GyroSample
import com.blurabbit.drivelogger.events.SpeedSample
import com.blurabbit.drivelogger.proto.AudioChunkMeta
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
    private val detector: EventDetector,
    private val storage: TripStorage,
    private val camera: CameraController,
    private val audio: AudioController,
    private val tripRepo: TripRepository,
    private val eventRepo: EventRepository,
    private val healthRepo: HealthRepository,
    private val metadataGen: MetadataGenerator,
) : RecordingController {

    private val _state = MutableStateFlow(RecordingState())
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val lifecycle = Mutex()
    private var scope: CoroutineScope? = null
    private var writer: McapAsyncWriter? = null
    private var sessionId: Long = -1
    private var lifecycleOwner: LifecycleOwner? = null

    // Counters. frameCount/droppedWrites are touched by the camera thread → atomic. The rest each
    // have a single writer coroutine but are read by the stateTicker on another Default thread, so
    // they are @Volatile for cross-thread visibility (single writer ⇒ no read-modify-write race).
    @Volatile private var imuSamples = 0L
    @Volatile private var gpsSamples = 0L
    private val frameCount = AtomicLong(0)
    private val eventCount = AtomicLong(0) // written by both the detector and the audio-events collector
    @Volatile private var audioSamples = 0L
    private val droppedWrites = AtomicLong(0)
    @Volatile private var distanceMeters = 0.0
    @Volatile private var maxSpeedMps = 0.0
    @Volatile private var currentSpeedMps = 0.0
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTelemetryWriteNs = 0L
    private val trackPoints = ArrayList<DoubleArray>() // [lat, lon] polyline (written by the sensor consumer)

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

        val available = sensorSources.filter { it.isAvailable() }
        val schemas = buildSchemas(available)
        val mcap = storage.mcapFile(tripId)
        val w = McapAsyncWriter(mcap, mcapConfig, schemas)
        writer = w

        // Per-trip camera calibration (intrinsics + distortion) as a portable MCAP attachment.
        CameraCalibration.readBackCameraJson(appContext)?.let { cal ->
            w.attachment("calibration.json", "application/json", cal.toByteArray(), clock.toEpochNanos(startElapsedNs))
        }

        sessionId = tripRepo.addSession(
            RecordingSession(
                tripId = tripId, segmentIndex = 0, startElapsedNs = startElapsedNs,
                endElapsedNs = null, mcapPath = mcap.absolutePath, mp4Path = null,
            ),
        )
        tripRepo.updateStatus(tripId, TripStatus.RECORDING)

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        launchPipeline(s, available, w)
        maybeStartCamera(s, w)
        maybeStartAudio(s, w)
        _state.value = RecordingState(phase = RecordingPhase.RECORDING, tripId = tripId, startElapsedNs = startElapsedNs)
    }

    private fun launchPipeline(s: CoroutineScope, available: List<SensorSource>, w: McapAsyncWriter) {
        // Single consumer over all sensor flows — keeps the event detector single-threaded.
        val merged = available.map { it.start(s).buffer(256) }
        s.launch { merge(*merged.toTypedArray()).collect { handleRecord(it, w) } }

        // Driving events → /events topic + Room.
        s.launch {
            detector.events.collect { ev ->
                w.write(Topics.EVENTS, clock.toEpochNanos(ev.unifiedNs), ev)
                eventCount.incrementAndGet()
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

    private suspend fun handleRecord(record: SensorRecord, w: McapAsyncWriter) {
        drift.validate(record.topic, record.unifiedTsNs, null)
        // log_time = epoch (for tooling); message body keeps raw unified_ns for cross-sensor sync.
        w.write(record.topic, clock.toEpochNanos(record.unifiedTsNs), record.message)

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
    }

    private suspend fun maybeStartCamera(s: CoroutineScope, w: McapAsyncWriter) {
        val owner = lifecycleOwner ?: return
        if (!hasCameraPermission()) return
        val mp4 = storage.mp4File(tripId!!)
        val ok = camera.start(owner, mp4) { meta: CameraFrameMeta ->
            if (w.offer(Topics.CAMERA_FRONT, clock.toEpochNanos(meta.unifiedNs), meta)) frameCount.incrementAndGet()
            else droppedWrites.incrementAndGet()
        }
        if (ok) tripRepo.closeSession(sessionId, clock.nowNanos(), mp4.absolutePath)
    }

    private fun maybeStartAudio(s: CoroutineScope, w: McapAsyncWriter) {
        if (!hasAudioPermission()) return
        // Subscribe before start() so the first chunk's meta/detection aren't dropped by the hot flow.
        s.launch { audio.meta.collect { m -> w.write(Topics.AUDIO_MICROPHONE, clock.toEpochNanos(m.unifiedNs), m); audioSamples++ } }
        s.launch { audio.events.collect { ev -> writeAudioEvent(w, ev) } }
        audio.start(storage.audioFile(tripId!!))
    }

    private suspend fun writeAudioEvent(w: McapAsyncWriter, ev: AudioEvent) {
        val proto = com.blurabbit.drivelogger.proto.DrivingEvent.newBuilder()
            .setUnifiedNs(ev.unifiedNs)
            .setType(ev.type.toProtoEventType())
            .setConfidence(ev.confidence)
            .setEvidenceJson("{\"label\":\"${ev.label}\"}")
            .setLatitude(lastLat ?: 0.0).setLongitude(lastLon ?: 0.0).setSpeedMps(currentSpeedMps)
            .build()
        w.write(Topics.EVENTS, clock.toEpochNanos(ev.unifiedNs), proto)
        eventCount.incrementAndGet()
        eventRepo.insert(
            DrivingEvent(
                tripId = tripId!!, type = ev.type, confidence = ev.confidence, unifiedTsNs = ev.unifiedNs,
                latitude = lastLat, longitude = lastLon, speedMps = currentSpeedMps,
                evidenceJson = "{\"label\":\"${ev.label}\"}",
            ),
        )
    }

    override suspend fun pause() = lifecycle.withLock {
        if (_state.value.phase != RecordingPhase.RECORDING) return@withLock
        camera.stop()
        audio.stop()
        // Cancel the pipeline children but keep the scope + writer open across pause.
        scope?.let { sc -> sc.coroutineContext[Job]?.children?.forEach { it.cancelAndJoin() } }
        writer?.flush()
        sensorSources.forEach { it.stop() }
        _state.value = _state.value.copy(phase = RecordingPhase.PAUSED)
    }

    override suspend fun resume() = lifecycle.withLock {
        if (_state.value.phase != RecordingPhase.PAUSED) return@withLock
        val s = scope ?: return@withLock
        val w = writer ?: return@withLock
        val available = sensorSources.filter { it.isAvailable() }
        launchPipeline(s, available, w)
        maybeStartCamera(s, w)
        maybeStartAudio(s, w)
        _state.value = _state.value.copy(phase = RecordingPhase.RECORDING)
    }

    override suspend fun stop() = lifecycle.withLock {
        val id = tripId ?: return@withLock
        _state.value = _state.value.copy(phase = RecordingPhase.STOPPING)
        camera.stop()
        audio.stop()
        scope?.let { sc -> sc.coroutineContext[Job]?.children?.forEach { runCatching { it.cancelAndJoin() } } }
        sensorSources.forEach { it.stop() }

        val durationNs = clock.nowNanos() - startElapsedNs
        val avgSpeed = if (durationNs > 0) distanceMeters / (durationNs / 1e9) else 0.0
        val stats = TripStats(
            distanceMeters = distanceMeters, maxSpeedMps = maxSpeedMps, avgSpeedMps = avgSpeed,
            gpsSamples = gpsSamples, imuSamples = imuSamples, frameCount = frameCount.get(), eventCount = eventCount.get(),
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

        // metadata.json sidecar (file sizes + end time now final).
        tripRepo.getTrip(id)?.let { trip ->
            metadataGen.write(
                trip.copy(stats = stats), durationNs,
                storage.mcapFile(id), storage.mp4File(id).takeIf { it.exists() },
                storage.metadataFile(id),
            )
        }
        writeTrackJson(id) // downsampled GPS polyline → track.json (input for HD-map enrichment)

        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null
        tripId = null
        _state.value = RecordingState(phase = RecordingPhase.IDLE)
    }

    private suspend fun stateTicker(available: List<SensorSource>) {
        val gnss = available.filterIsInstance<GnssSensorSource>().firstOrNull()
        while (true) {
            val (satTotal, satUsed) = gnss?.satelliteCounts() ?: (0 to 0)
            _state.value = _state.value.copy(
                durationNs = clock.nowNanos() - startElapsedNs,
                currentSpeedMps = currentSpeedMps,
                satellitesTotal = satTotal, satellitesUsed = satUsed,
                distanceMeters = distanceMeters, maxSpeedMps = maxSpeedMps,
                gpsSamples = gpsSamples, imuSamples = imuSamples,
                frameCount = frameCount.get(), eventCount = eventCount.get(), audioSamples = audioSamples,
                storageFreeBytes = storage.freeBytes(),
                droppedWrites = droppedWrites.get(),
                sensorHealth = available.map { it.health() },
            )
            kotlinx.coroutines.delay(500)
        }
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
    }

    private fun buildSchemas(available: List<SensorSource>): List<TopicSchema> {
        val map = LinkedHashMap<String, TopicSchema>()
        available.forEach { src ->
            src.topics.forEach { td -> map[td.topic] = TopicSchema(td.topic, td.descriptor, td.metadata) }
        }
        // Always include camera + audio + events channels even before their first message.
        map[Topics.CAMERA_FRONT] = TopicSchema(Topics.CAMERA_FRONT, CameraFrameMeta.getDescriptor())
        map[Topics.AUDIO_MICROPHONE] = TopicSchema(Topics.AUDIO_MICROPHONE, AudioChunkMeta.getDescriptor())
        map[Topics.EVENTS] = TopicSchema(Topics.EVENTS, com.blurabbit.drivelogger.proto.DrivingEvent.getDescriptor())
        return map.values.toList()
    }

    private fun updateDistance(lat: Double, lon: Double) {
        val pLat = lastLat; val pLon = lastLon
        if (pLat != null && pLon != null) {
            val d = haversine(pLat, pLon, lat, lon)
            if (d in 0.1..500.0) distanceMeters += d // ignore jitter and GPS jumps
        }
        // Downsample to ~1 point / 50 m for a compact polyline (HD-map enrichment input).
        val last = trackPoints.lastOrNull()
        if (last == null || haversine(last[0], last[1], lat, lon) >= 50.0) {
            trackPoints += doubleArrayOf(lat, lon)
        }
        lastLat = lat; lastLon = lon
    }

    private fun writeTrackJson(id: String) {
        if (trackPoints.isEmpty()) return
        val json = buildString {
            append("[")
            trackPoints.forEachIndexed { i, p ->
                if (i > 0) append(",")
                append("[").append(p[0]).append(",").append(p[1]).append("]")
            }
            append("]")
        }
        runCatching { storage.trackFile(id).writeText(json) }
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

    private fun hasAudioPermission(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun resetCounters() {
        imuSamples = 0; gpsSamples = 0; frameCount.set(0); eventCount.set(0); audioSamples = 0; droppedWrites.set(0)
        distanceMeters = 0.0; maxSpeedMps = 0.0; currentSpeedMps = 0.0
        lastLat = null; lastLon = null; lastTelemetryWriteNs = 0; trackPoints.clear()
    }
}

/** SQLite stores NaN/Infinity as NULL, breaking NOT NULL columns — coerce to a safe sentinel. */
private fun Double.nanGuard(sentinel: Double = -1.0): Double = if (isFinite()) this else sentinel

/** Domain → proto event type (audio classifier emits SIREN / VEHICLE_HORN). */
private fun DrivingEventType.toProtoEventType(): com.blurabbit.drivelogger.proto.DrivingEvent.EventType = when (this) {
    DrivingEventType.HARD_BRAKING -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.HARD_BRAKING
    DrivingEventType.SUDDEN_ACCELERATION -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SUDDEN_ACCELERATION
    DrivingEventType.SHARP_TURN -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SHARP_TURN
    DrivingEventType.POTHOLE_IMPACT -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.POTHOLE_IMPACT
    DrivingEventType.SPEED_BUMP -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SPEED_BUMP
    DrivingEventType.RAPID_LANE_CHANGE -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.RAPID_LANE_CHANGE
    DrivingEventType.AGGRESSIVE_DRIVING -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.AGGRESSIVE_DRIVING
    DrivingEventType.SIREN -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SIREN
    DrivingEventType.VEHICLE_HORN -> com.blurabbit.drivelogger.proto.DrivingEvent.EventType.VEHICLE_HORN
}

private fun com.blurabbit.drivelogger.proto.DrivingEvent.EventType.toDomain(): DrivingEventType = when (this) {
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.HARD_BRAKING -> DrivingEventType.HARD_BRAKING
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SUDDEN_ACCELERATION -> DrivingEventType.SUDDEN_ACCELERATION
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SHARP_TURN -> DrivingEventType.SHARP_TURN
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.POTHOLE_IMPACT -> DrivingEventType.POTHOLE_IMPACT
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.SPEED_BUMP -> DrivingEventType.SPEED_BUMP
    com.blurabbit.drivelogger.proto.DrivingEvent.EventType.RAPID_LANE_CHANGE -> DrivingEventType.RAPID_LANE_CHANGE
    else -> DrivingEventType.AGGRESSIVE_DRIVING
}
