package com.blurabbit.drivelogger.core.clock

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single, app-wide source of synchronized time. Every MCAP `log_time` ultimately
 * derives from [nowNanos]. Backed by `SystemClock.elapsedRealtimeNanos()`.
 */
@Singleton
class MonotonicClock @Inject constructor(
    private val timeProvider: TimeProvider,
    private val gnssTime: GnssTimeHolder,
) {
    /** boot epoch in ns: epoch_now − elapsed_now. Captured per trip so log_time → real wall time. */
    @Volatile private var bootEpochNanos: Long = Long.MIN_VALUE

    /** True once the anchor is derived from GNSS UTC (vs the phone wall clock). */
    @Volatile var anchoredToGnss: Boolean = false
        private set

    /** Current unified time in nanoseconds (elapsed realtime since boot). */
    fun nowNanos(): Long = timeProvider.elapsedRealtimeNanos()

    /**
     * Snapshot the offset between real wall time and the unified clock. Prefers GNSS UTC (accurate
     * across a fleet) when a recent fix exists; otherwise falls back to the phone wall clock. Call at
     * the start of a recording and again once the first GNSS fix lands ([anchoredToGnss] flips true).
     */
    fun captureEpochAnchor() {
        val now = timeProvider.elapsedRealtimeNanos()
        val gnssOffset = gnssTime.offsetNs(now)
        if (gnssOffset != null) {
            bootEpochNanos = gnssOffset
            anchoredToGnss = true
        } else {
            bootEpochNanos = timeProvider.currentTimeMillis() * 1_000_000L - now
            anchoredToGnss = false
        }
    }

    /**
     * Convert a unified (elapsed-realtime) nanosecond timestamp to **epoch nanoseconds** for MCAP
     * `log_time`. Sensor messages still carry their raw `unified_ns` for drift-free cross-sensor
     * sync; this only fixes the absolute time axis tools display.
     */
    fun toEpochNanos(unifiedElapsedNanos: Long): Long {
        if (bootEpochNanos == Long.MIN_VALUE) captureEpochAnchor()
        return bootEpochNanos + unifiedElapsedNanos
    }

    /** Wall-clock epoch nanoseconds for "now" — human-readable trip metadata only. */
    fun wallEpochNanosNow(): Long = timeProvider.currentTimeMillis() * 1_000_000L
}
