package com.blurabbit.drivelogger.core.clock

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared latch for GNSS-disciplined wall time. The GNSS source publishes the offset between
 * satellite UTC and the unified elapsed-realtime clock on each fix; [MonotonicClock] reads it when
 * anchoring MCAP `log_time`, so timestamps are accurate across a fleet (phone wall clocks drift).
 */
@Singleton
class GnssTimeHolder @Inject constructor() {
    @Volatile private var gnssEpochOffsetNs: Long = 0L
    @Volatile private var updatedElapsedNs: Long = Long.MIN_VALUE

    /** @param locTimeEpochMs `Location.getTime()`; @param locElapsedNs `Location.getElapsedRealtimeNanos()`. */
    fun update(locTimeEpochMs: Long, locElapsedNs: Long) {
        gnssEpochOffsetNs = locTimeEpochMs * 1_000_000L - locElapsedNs
        updatedElapsedNs = locElapsedNs
    }

    /** Epoch-minus-elapsed offset if a fix arrived within [maxAgeNs] of [nowElapsedNs], else null. */
    fun offsetNs(nowElapsedNs: Long, maxAgeNs: Long = 60_000_000_000L): Long? {
        val u = updatedElapsedNs
        if (u == Long.MIN_VALUE || nowElapsedNs - u > maxAgeNs) return null
        return gnssEpochOffsetNs
    }

    fun hasFix(): Boolean = updatedElapsedNs != Long.MIN_VALUE
    fun reset() { updatedElapsedNs = Long.MIN_VALUE }
}
