package com.blurabbit.drivelogger.sensors.impl

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.blurabbit.drivelogger.core.clock.ClockSynchronizer
import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.proto.GnssClockSample
import com.blurabbit.drivelogger.proto.GnssMeasurements
import com.blurabbit.drivelogger.proto.GnssRaw
import com.blurabbit.drivelogger.proto.GpsExtras
import com.blurabbit.drivelogger.proto.Satellite
import com.blurabbit.drivelogger.proto.GnssMeasurement as ProtoGnssMeasurement
import com.blurabbit.drivelogger.sensors.RateTracker
import com.blurabbit.drivelogger.sensors.SensorHealthSnapshot
import com.blurabbit.drivelogger.sensors.SensorRecord
import com.blurabbit.drivelogger.sensors.SensorSource
import com.blurabbit.drivelogger.sensors.TopicDescriptor
import com.google.protobuf.Timestamp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * GNSS source. Publishes:
 *  - `/gps/fix`  → foxglove.LocationFix (renders in Foxglove Studio)
 *  - `/gps/raw`  → blurabbit.GpsExtras (speed/bearing/accuracies) and blurabbit.GnssRaw
 *                  (satellite status + per-constellation counts incl. NavIC/IRNSS).
 *
 * Uses `Location.getElapsedRealtimeNanos()`, which is already on the unified timebase, so the
 * source is registered as an identity source (zero offset).
 */
class GnssSensorSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: ClockSynchronizer,
) : SensorSource {

    override val id: String = "gnss"

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val fixTracker = RateTracker(expectedHz = 1.0)
    private var thread: HandlerThread? = null
    private var statusCallback: GnssStatus.Callback? = null
    private var androidListener: android.location.LocationListener? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var measurementsCallback: GnssMeasurementsEvent.Callback? = null
    private val measurementsExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "gnss-measurements") }
    @Volatile private var lastSatTotal = 0
    @Volatile private var lastSatUsed = 0
    // Latest DOP parsed from NMEA GSA (0 = not yet observed).
    @Volatile private var pdop = 0.0
    @Volatile private var hdop = 0.0
    @Volatile private var vdop = 0.0

    override val topics: List<TopicDescriptor> = listOf(
        TopicDescriptor(Topics.GPS_FIX, foxglove.LocationFix.getDescriptor()),
        TopicDescriptor(Topics.GNSS_RAW, GnssRaw.getDescriptor()),
        TopicDescriptor(Topics.GNSS_MEASUREMENTS, GnssMeasurements.getDescriptor()),
    )

    override fun isAvailable(): Boolean =
        locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission") // caller guarantees ACCESS_FINE_LOCATION before start()
    override fun start(scope: CoroutineScope): Flow<SensorRecord> = callbackFlow {
        sync.registerIdentitySource("gnss")
        val producer = this // explicit ProducerScope captured for the platform callbacks below
        val ht = HandlerThread("gnss").apply { start() }
        thread = ht
        val handler = Handler(ht.looper)

        // High-rate location updates from GPS provider (1 Hz here; RTK sources can raise this).
        val listener = android.location.LocationListener { loc -> emitFix(producer, loc) }
        androidListener = listener
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, ht.looper,
            )
        } catch (_: SecurityException) { /* permission revoked mid-run */ }

        // Satellite status (counts + per-constellation, including NavIC = CONSTELLATION_IRNSS).
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) = emitSatellites(producer, status)
        }
        statusCallback = cb
        try {
            locationManager.registerGnssStatusCallback(cb, handler)
        } catch (_: SecurityException) {}

        // NMEA → DOP (PDOP/HDOP/VDOP from the GSA sentence); not exposed by the Location API.
        val nmea = OnNmeaMessageListener { message, _ ->
            parseGsaDop(message)?.let { (p, h, v) -> pdop = p; hdop = h; vdop = v }
        }
        nmeaListener = nmea
        try {
            locationManager.addNmeaListener(nmea, handler)
        } catch (_: SecurityException) {}

        // Raw GNSS measurements (clock + pseudorange/carrier-phase) for offline PPK/RTK.
        registerMeasurements(producer)

        awaitClose { stopInternal() }
    }

    @SuppressLint("MissingPermission")
    private fun registerMeasurements(producer: kotlinx.coroutines.channels.ProducerScope<SensorRecord>) {
        val cb = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) = emitMeasurements(producer, event)
        }
        measurementsCallback = cb
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Full tracking defeats GNSS duty-cycling, which otherwise corrupts continuous raw data.
                locationManager.registerGnssMeasurementsCallback(
                    GnssMeasurementRequest.Builder().setFullTracking(true).build(), measurementsExecutor, cb,
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssMeasurementsCallback(cb)
            }
        } catch (_: SecurityException) {} catch (_: IllegalArgumentException) { /* unsupported device */ }
    }

    private fun emitFix(producer: kotlinx.coroutines.channels.ProducerScope<SensorRecord>, loc: Location) {
        // elapsedRealtimeNanos() is already on the unified clock (API 17+, always true at minSdk 26).
        val unified = loc.elapsedRealtimeNanos
        fixTracker.onSample(unified)

        val hAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0
        val vAcc = if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters.toDouble() else hAcc
        val fixBuilder = foxglove.LocationFix.newBuilder()
            .setTimestamp(Timestamp.newBuilder().setSeconds(loc.time / 1000).setNanos(((loc.time % 1000) * 1_000_000L).toInt()))
            .setFrameId("gnss")
            .setLatitude(loc.latitude)
            .setLongitude(loc.longitude)
            .setAltitude(if (loc.hasAltitude()) loc.altitude else 0.0)
        if (hAcc > 0.0) {
            // Diagonal ENU covariance (m^2) approximated from the reported 1-sigma accuracies.
            val ev = hAcc * hAcc; val nv = hAcc * hAcc; val uv = vAcc * vAcc
            fixBuilder.addAllPositionCovariance(listOf(ev, 0.0, 0.0, 0.0, nv, 0.0, 0.0, 0.0, uv))
                .setPositionCovarianceType(foxglove.LocationFix.PositionCovarianceType.APPROXIMATED)
        }
        producer.trySend(SensorRecord(Topics.GPS_FIX, unified, fixBuilder.build()))

        val extras = GpsExtras.newBuilder()
            .setUnifiedNs(unified)
            .setSpeedMps(if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
            .setBearingDeg(if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
            .setHorizontalAccuracyM(hAcc)
            .setPdop(pdop).setHdop(hdop).setVdop(vdop)
            .apply {
                if (Build.VERSION.SDK_INT >= 26) {
                    if (loc.hasVerticalAccuracy()) verticalAccuracyM = loc.verticalAccuracyMeters.toDouble()
                    if (loc.hasSpeedAccuracy()) speedAccuracyMps = loc.speedAccuracyMetersPerSecond.toDouble()
                    if (loc.hasBearingAccuracy()) bearingAccuracyDeg = loc.bearingAccuracyDegrees.toDouble()
                }
            }
            .build()
        producer.trySend(SensorRecord(Topics.GNSS_RAW, unified, extras))
    }

    private fun emitSatellites(producer: kotlinx.coroutines.channels.ProducerScope<SensorRecord>, status: GnssStatus) {
        // Satellite status has no per-sample timestamp; stamp with the unified clock directly.
        val unified = android.os.SystemClock.elapsedRealtimeNanos()
        val builder = GnssRaw.newBuilder().setUnifiedNs(unified)
        val counts = HashMap<Int, Int>()
        var used = 0
        for (i in 0 until status.satelliteCount) {
            val constellation = status.getConstellationType(i)
            counts[constellation] = (counts[constellation] ?: 0) + 1
            val inFix = status.usedInFix(i)
            if (inFix) used++
            builder.addSatellites(
                Satellite.newBuilder()
                    .setConstellation(constellation)
                    .setSvid(status.getSvid(i))
                    .setCn0Dbhz(status.getCn0DbHz(i).toDouble())
                    .setElevationDeg(status.getElevationDegrees(i).toDouble())
                    .setAzimuthDeg(status.getAzimuthDegrees(i).toDouble())
                    .setUsedInFix(inFix)
                    .setCarrierFreqHz(if (Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i).toDouble() else 0.0)
                    .build(),
            )
        }
        lastSatTotal = status.satelliteCount
        lastSatUsed = used
        builder.satellitesTotal = status.satelliteCount
        builder.satellitesUsed = used
        builder.putAllConstellationCounts(counts)
        producer.trySend(SensorRecord(Topics.GNSS_RAW, unified, builder.build()))
    }

    private fun emitMeasurements(producer: kotlinx.coroutines.channels.ProducerScope<SensorRecord>, event: GnssMeasurementsEvent) {
        val unified = android.os.SystemClock.elapsedRealtimeNanos()
        val c = event.clock
        val clock = GnssClockSample.newBuilder()
            .setTimeNanos(c.timeNanos)
            .apply {
                if (c.hasFullBiasNanos()) fullBiasNanos = c.fullBiasNanos
                if (c.hasBiasNanos()) biasNanos = c.biasNanos
                if (c.hasBiasUncertaintyNanos()) biasUncertaintyNanos = c.biasUncertaintyNanos
                if (c.hasDriftNanosPerSecond()) driftNanosPerSecond = c.driftNanosPerSecond
                if (c.hasDriftUncertaintyNanosPerSecond()) driftUncertaintyNps = c.driftUncertaintyNanosPerSecond
                hardwareClockDiscontinuityCount = c.hardwareClockDiscontinuityCount
                if (c.hasLeapSecond()) { leapSecond = c.leapSecond; hasLeapSecond = true }
            }
            .build()
        val builder = GnssMeasurements.newBuilder().setUnifiedNs(unified).setClock(clock)
        for (m in event.measurements) {
            builder.addMeasurements(
                ProtoGnssMeasurement.newBuilder()
                    .setSvid(m.svid)
                    .setConstellation(m.constellationType)
                    .setTimeOffsetNanos(m.timeOffsetNanos)
                    .setState(m.state)
                    .setReceivedSvTimeNanos(m.receivedSvTimeNanos)
                    .setReceivedSvTimeUncertaintyNanos(m.receivedSvTimeUncertaintyNanos)
                    .setCn0Dbhz(m.cn0DbHz)
                    .setPseudorangeRateMps(m.pseudorangeRateMetersPerSecond)
                    .setPseudorangeRateUncertaintyMps(m.pseudorangeRateUncertaintyMetersPerSecond)
                    .setAccumulatedDeltaRangeState(m.accumulatedDeltaRangeState)
                    .setAccumulatedDeltaRangeMeters(m.accumulatedDeltaRangeMeters)
                    .setAccumulatedDeltaRangeUncertaintyM(m.accumulatedDeltaRangeUncertaintyMeters)
                    .setCarrierFrequencyHz(if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else 0.0)
                    .setMultipathIndicator(m.multipathIndicator)
                    .build(),
            )
        }
        producer.trySend(SensorRecord(Topics.GNSS_MEASUREMENTS, unified, builder.build()))
    }

    override fun stop() = stopInternal()

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        androidListener?.let { runCatching { locationManager.removeUpdates(it) } }
        statusCallback?.let { runCatching { locationManager.unregisterGnssStatusCallback(it) } }
        nmeaListener?.let { runCatching { locationManager.removeNmeaListener(it) } }
        measurementsCallback?.let { runCatching { locationManager.unregisterGnssMeasurementsCallback(it) } }
        androidListener = null
        statusCallback = null
        nmeaListener = null
        measurementsCallback = null
        thread?.quitSafely(); thread = null
    }

    /** Latest satellite counts for the dashboard GPS-quality widget. */
    fun satelliteCounts(): Pair<Int, Int> = lastSatTotal to lastSatUsed

    override fun health(): SensorHealthSnapshot = fixTracker.snapshot(id, driftMs = 0.0)
}

/**
 * Parses PDOP/HDOP/VDOP from an NMEA GSA sentence (`$GPGSA`/`$GNGSA`). Returns null for non-GSA or
 * malformed sentences. DOP occupies fields 15/16/17 in both legacy NMEA and 4.10 (system-id) forms.
 */
internal fun parseGsaDop(nmea: String): Triple<Double, Double, Double>? {
    val f = nmea.substringBefore('*').split(',')
    if (f.size < 18 || !f[0].endsWith("GSA")) return null
    val p = f[15].toDoubleOrNull() ?: return null
    val h = f[16].toDoubleOrNull() ?: return null
    val v = f[17].toDoubleOrNull() ?: return null
    return Triple(p, h, v)
}
