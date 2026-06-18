package com.blurabbit.drivelogger.sensors.impl

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.blurabbit.drivelogger.core.clock.ClockSynchronizer
import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.proto.GnssRaw
import com.blurabbit.drivelogger.proto.GpsExtras
import com.blurabbit.drivelogger.proto.Satellite
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
    @Volatile private var lastSatTotal = 0
    @Volatile private var lastSatUsed = 0

    override val topics: List<TopicDescriptor> = listOf(
        TopicDescriptor(Topics.GPS_FIX, foxglove.LocationFix.getDescriptor()),
        TopicDescriptor(Topics.GNSS_RAW, GnssRaw.getDescriptor()),
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

        awaitClose { stopInternal() }
    }

    private fun emitFix(producer: kotlinx.coroutines.channels.ProducerScope<SensorRecord>, loc: Location) {
        // elapsedRealtimeNanos() is already on the unified clock (API 17+, always true at minSdk 26).
        val unified = loc.elapsedRealtimeNanos
        fixTracker.onSample(unified)

        val fix = foxglove.LocationFix.newBuilder()
            .setTimestamp(Timestamp.newBuilder().setSeconds(loc.time / 1000).setNanos(((loc.time % 1000) * 1_000_000L).toInt()))
            .setFrameId("gnss")
            .setLatitude(loc.latitude)
            .setLongitude(loc.longitude)
            .setAltitude(if (loc.hasAltitude()) loc.altitude else 0.0)
            .build()
        producer.trySend(SensorRecord(Topics.GPS_FIX, unified, fix))

        val extras = GpsExtras.newBuilder()
            .setUnifiedNs(unified)
            .setSpeedMps(if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
            .setBearingDeg(if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
            .setHorizontalAccuracyM(if (loc.hasAccuracy()) loc.accuracy.toDouble() else 0.0)
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

    override fun stop() = stopInternal()

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        androidListener?.let { runCatching { locationManager.removeUpdates(it) } }
        statusCallback?.let { runCatching { locationManager.unregisterGnssStatusCallback(it) } }
        androidListener = null
        statusCallback = null
        thread?.quitSafely(); thread = null
    }

    /** Latest satellite counts for the dashboard GPS-quality widget. */
    fun satelliteCounts(): Pair<Int, Int> = lastSatTotal to lastSatUsed

    override fun health(): SensorHealthSnapshot = fixTracker.snapshot(id, driftMs = 0.0)
}
