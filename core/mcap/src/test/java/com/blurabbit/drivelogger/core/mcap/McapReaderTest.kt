package com.blurabbit.drivelogger.core.mcap

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Round-trip: write with [McapWriter], read it back with [McapReader]. */
class McapReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `reads back topics, counts, timestamps and payloads from lz4 chunks`() {
        val f = tmp.newFile("rt.mcap")
        McapWriter(f, McapWriterConfig(compression = McapWriterConfig.Compression.LZ4, chunkTargetBytes = 4096)).use { w ->
            val imu = w.channel("/imu", "Imu", "raw", "x".toByteArray(), "raw")
            val gps = w.channel("/gps", "Gps", "raw", "y".toByteArray(), "raw")
            repeat(300) { i -> w.writeMessage(imu, 1_000L + i, byteArrayOf(i.toByte(), (i + 1).toByte())) }
            repeat(50) { i -> w.writeMessage(gps, 5_000L + i, byteArrayOf((i * 2).toByte())) }
        }

        val reader = McapReader(f)

        assertThat(reader.topicCounts()).containsExactly("/imu", 300L, "/gps", 50L)

        val imu = reader.readMessages(setOf("/imu"))
        assertThat(imu).hasSize(300)
        assertThat(imu.map { it.topic }.toSet()).containsExactly("/imu")
        assertThat(imu[0].schemaName).isEqualTo("Imu")
        assertThat(imu[0].logTimeNs).isEqualTo(1_000L)
        assertThat(imu[0].sequence).isEqualTo(0L)
        assertThat(imu[0].data.toList()).isEqualTo(byteArrayOf(0, 1).toList())
        assertThat(imu[299].logTimeNs).isEqualTo(1_299L)
        assertThat(imu[299].data.toList()).isEqualTo(byteArrayOf(299.toByte(), 300.toByte()).toList())

        // schemas/channels surfaced after a pass
        assertThat(reader.channels.values.map { it.topic }).containsAtLeast("/imu", "/gps")
        assertThat(reader.schemas.values.map { it.name }).containsAtLeast("Imu", "Gps")
    }

    @Test
    fun `reads uncompressed (NONE) chunks too`() {
        val f = tmp.newFile("none.mcap")
        McapWriter(f, McapWriterConfig(compression = McapWriterConfig.Compression.NONE)).use { w ->
            val ch = w.channel("/t", "T", "raw", "z".toByteArray(), "raw")
            repeat(120) { i -> w.writeMessage(ch, 10L + i, byteArrayOf(i.toByte())) }
        }
        assertThat(McapReader(f).topicCounts()["/t"]).isEqualTo(120L)
    }
}
