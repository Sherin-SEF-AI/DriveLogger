package com.blurabbit.drivelogger.core.clock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ClockSynchronizerTest {

    private class FakeTime(var nanos: Long) : TimeProvider {
        override fun elapsedRealtimeNanos(): Long = nanos
        override fun currentTimeMillis(): Long = nanos / 1_000_000
    }

    @Test
    fun `identity source maps timestamps unchanged`() {
        val time = FakeTime(1_000_000_000)
        val sync = ClockSynchronizer(MonotonicClock(time, GnssTimeHolder()))
        sync.registerIdentitySource("gnss")

        assertThat(sync.convert("gnss", 42L)).isEqualTo(42L)
    }

    @Test
    fun `offset converges toward latency-free minimum`() {
        val time = FakeTime(0)
        val sync = ClockSynchronizer(MonotonicClock(time, GnssTimeHolder()))

        // Source clock starts 1_000ns behind unified; each delivery adds jittery latency.
        val sourceEpochSkew = 1_000L
        val latencies = longArrayOf(50, 80, 5, 200, 30, 5, 120, 5)
        var unifiedNow = 10_000L
        var lastConverted = 0L
        latencies.forEach { latency ->
            time.nanos = unifiedNow + latency
            val sourceTs = unifiedNow - sourceEpochSkew
            lastConverted = sync.observeAndConvert("imu", sourceTs)
            unifiedNow += 1_000
        }

        // Estimated offset should approach the true skew (1_000), within smoothing tolerance.
        val offset = sync.currentOffsetNs("imu")!!
        assertThat(offset).isAtLeast(1_000L)
        assertThat(offset).isAtMost(1_000L + 60L)
        assertThat(lastConverted).isGreaterThan(0L)
    }
}
