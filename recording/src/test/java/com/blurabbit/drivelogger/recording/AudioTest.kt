package com.blurabbit.drivelogger.recording

import com.blurabbit.drivelogger.domain.model.DrivingEventType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioTest {

    @Test
    fun `maps siren and horn labels to event types`() {
        assertThat(labelToEventType("Siren")).isEqualTo(DrivingEventType.SIREN)
        assertThat(labelToEventType("Police car (siren)")).isEqualTo(DrivingEventType.SIREN)
        assertThat(labelToEventType("Emergency vehicle")).isEqualTo(DrivingEventType.SIREN)
        assertThat(labelToEventType("Vehicle horn, car horn, honking")).isEqualTo(DrivingEventType.VEHICLE_HORN)
        assertThat(labelToEventType("Air horn, truck horn")).isEqualTo(DrivingEventType.VEHICLE_HORN)
    }

    @Test
    fun `ignores unrelated and excluded labels`() {
        assertThat(labelToEventType("Speech")).isNull()
        assertThat(labelToEventType("Music")).isNull()
        assertThat(labelToEventType("Foghorn")).isNull()   // not a road horn
        assertThat(labelToEventType("Train horn")).isNull()
    }

    @Test
    fun `least-squares recovers slope and intercept from anchors`() {
        // True line: unified_ns = 62_500 * sample + 1_000_000  (=> 16000.0 Hz). Add small jitter.
        val anchors = listOf(
            longArrayOf(0, 1_000_000),
            longArrayOf(16_000, 1_000_000 + 16_000L * 62_500 + 30_000),  // +30µs jitter
            longArrayOf(32_000, 1_000_000 + 32_000L * 62_500 - 20_000),  // -20µs jitter
            longArrayOf(48_000, 1_000_000 + 48_000L * 62_500 + 10_000),
        )
        val (slope, intercept) = leastSquaresFit(anchors)!!
        assertThat(slope).isWithin(5.0).of(62_500.0)              // ~62500 ns/sample
        assertThat(1e9 / slope).isWithin(2.0).of(16_000.0)        // measured rate ≈ 16 kHz
        assertThat(intercept).isWithin(40_000.0).of(1_000_000.0)
        assertThat(leastSquaresFit(listOf(longArrayOf(0, 0)))).isNull()  // need ≥2 points
    }

    @Test
    fun `sidecar json carries measured rate, offset and anchors`() {
        val anchors = listOf(longArrayOf(0, 1_000_000), longArrayOf(16_000, 1_000_000 + 16_000L * 62_500))
        val json = buildAudioSidecarJson(anchors, nominalRateHz = 16_000, channels = 1, toEpochNs = { it + 1_700_000_000_000_000_000L })!!
        assertThat(json).contains("\"measured_sample_rate\":16000.0")
        assertThat(json).contains("\"device_to_wall_offset_ns\":1700000000000000000")
        assertThat(json).contains("\"anchor_timebase\":\"boottime_ns\"")
        assertThat(json).contains("[16000,1001000000]")  // raw [sample, unified_ns]
    }

    @Test
    fun `wav header has RIFF WAVE data tags and correct rate`() {
        val h = wavHeader(sampleRate = 16_000, channels = 1, dataLen = 0)
        assertThat(h.size).isEqualTo(44)
        assertThat(String(h, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(String(h, 8, 4, Charsets.US_ASCII)).isEqualTo("WAVE")
        assertThat(String(h, 36, 4, Charsets.US_ASCII)).isEqualTo("data")
        // sampleRate is little-endian uint32 at byte offset 24.
        val sr = (h[24].toInt() and 0xFF) or ((h[25].toInt() and 0xFF) shl 8) or
            ((h[26].toInt() and 0xFF) shl 16) or ((h[27].toInt() and 0xFF) shl 24)
        assertThat(sr).isEqualTo(16_000)
    }
}
