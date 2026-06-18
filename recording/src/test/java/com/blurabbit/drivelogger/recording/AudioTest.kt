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
