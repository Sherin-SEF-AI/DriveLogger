package com.blurabbit.drivelogger.core.mcap

import com.google.common.truth.Truth.assertThat
import net.jpountz.lz4.LZ4FrameInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Structural round-trip tests for the MCAP writer. Most cases use NONE compression so message
 * records can be parsed straight out of the chunk payload; one case exercises the real LZ4 path.
 */
class McapWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dummySchema = "x".toByteArray()

    @Test
    fun `file has leading and trailing magic`() {
        val f = tmp.newFile("a.mcap")
        McapWriter(f, McapWriterConfig(compression = McapWriterConfig.Compression.NONE)).use { w ->
            val ch = w.channel("/t", "T", "raw", dummySchema, "raw")
            w.writeMessage(ch, 1_000, byteArrayOf(1, 2, 3))
        }
        val bytes = f.readBytes()
        assertThat(bytes.copyOfRange(0, 8)).isEqualTo(MCAP_MAGIC)
        assertThat(bytes.copyOfRange(bytes.size - 8, bytes.size)).isEqualTo(MCAP_MAGIC)
    }

    @Test
    fun `all written messages are present in chunk payloads`() {
        val f = tmp.newFile("b.mcap")
        val n = 250
        McapWriter(f, McapWriterConfig(compression = McapWriterConfig.Compression.NONE)).use { w ->
            val ch = w.channel("/imu", "Imu", "raw", dummySchema, "raw")
            repeat(n) { i -> w.writeMessage(ch, 1_000L + i, byteArrayOf(i.toByte())) }
        }
        assertThat(countMessages(f)).isEqualTo(n)
    }

    @Test
    fun `lz4 chunks decompress and preserve every message`() {
        val f = tmp.newFile("lz4.mcap")
        val n = 500
        McapWriter(f, McapWriterConfig(compression = McapWriterConfig.Compression.LZ4, chunkTargetBytes = 4096)).use { w ->
            val ch = w.channel("/imu", "Imu", "raw", dummySchema, "raw")
            repeat(n) { i -> w.writeMessage(ch, 1_000L + i, byteArrayOf((i and 0xFF).toByte())) }
        }
        // Forces real LZ4 frame decode in the assertion path (proves the compressed write path works).
        assertThat(countMessagesAnyCompression(f)).isEqualTo(n)
    }

    @Test
    fun `recovery preserves all messages when no summary was written`() {
        val f = tmp.newFile("crash.mcap")
        // Small chunks so messages flush to disk as chunks; never call close() → no DataEnd/summary/footer.
        val w = McapWriter(f, McapWriterConfig(compression = McapWriterConfig.Compression.NONE, chunkTargetBytes = 256))
        val ch = w.channel("/imu", "Imu", "raw", dummySchema, "raw")
        repeat(100) { i -> w.writeMessage(ch, 1_000L + i, byteArrayOf((i and 0xFF).toByte())) }
        w.flush() // push the final partial chunk to disk, then simulate a process kill (no close())

        val recovered = tmp.newFile("crash_recovered.mcap")
        val result = McapRecoveryTool.recover(f, recovered)
        assertThat(result).isNotNull()
        assertThat(result!!.messageCount).isEqualTo(100)
        assertThat(countMessages(recovered)).isEqualTo(100)
        val rb = recovered.readBytes()
        assertThat(rb.copyOfRange(rb.size - 8, rb.size)).isEqualTo(MCAP_MAGIC)
    }

    @Test
    fun `recovery rebuilds a file truncated before the footer`() {
        val f = tmp.newFile("c.mcap")
        McapWriter(f, McapWriterConfig(compression = McapWriterConfig.Compression.NONE)).use { w ->
            val ch = w.channel("/imu", "Imu", "raw", dummySchema, "raw")
            repeat(100) { i -> w.writeMessage(ch, 1_000L + i, byteArrayOf(i.toByte())) }
        }
        // Simulate a crash: lop off the summary + footer + trailing magic.
        val full = f.readBytes()
        val truncated = tmp.newFile("c_trunc.mcap")
        truncated.writeBytes(full.copyOfRange(0, full.size / 2))

        val recovered = tmp.newFile("c_recovered.mcap")
        val result = McapRecoveryTool.recover(truncated, recovered)
        assertThat(result).isNotNull()
        val rb = recovered.readBytes()
        assertThat(rb.copyOfRange(rb.size - 8, rb.size)).isEqualTo(MCAP_MAGIC)
    }

    /** Minimal forward parser: walk records, decode CHUNK payloads (NONE), count MESSAGE ops. */
    private fun countMessages(f: File): Int {
        val b = f.readBytes()
        var pos = 8 // skip magic
        var count = 0
        while (pos + 9 <= b.size) {
            val op = b[pos].toInt() and 0xFF
            val len = readU64(b, pos + 1)
            val bodyStart = pos + 9
            if (op == Op.CHUNK) {
                // body: u64 start, u64 end, u64 uncompressedSize, u32 crc, str compression, u64 records
                var p = bodyStart + 8 + 8 + 8 + 4
                val compLen = readU32(b, p).toInt(); p += 4 + compLen
                val recordsLen = readU64(b, p).toInt(); p += 8
                val end = p + recordsLen
                while (p + 9 <= end) {
                    val innerOp = b[p].toInt() and 0xFF
                    val innerLen = readU64(b, p + 1)
                    if (innerOp == Op.MESSAGE) count++
                    p += 9 + innerLen.toInt()
                }
            }
            pos = (bodyStart + len).toInt()
        }
        return count
    }

    /** Like [countMessages] but decompresses LZ4 chunk payloads before counting inner MESSAGE ops. */
    private fun countMessagesAnyCompression(f: File): Int {
        val b = f.readBytes()
        var pos = 8
        var count = 0
        while (pos + 9 <= b.size) {
            val op = b[pos].toInt() and 0xFF
            val len = readU64(b, pos + 1)
            val bodyStart = pos + 9
            if (op == Op.CHUNK) {
                var p = bodyStart + 8 + 8 + 8 + 4
                val compLen = readU32(b, p).toInt(); p += 4
                val comp = String(b, p, compLen, Charsets.UTF_8); p += compLen
                val payloadLen = readU64(b, p).toInt(); p += 8
                val payload = b.copyOfRange(p, p + payloadLen)
                val records = if (comp == "lz4") {
                    LZ4FrameInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
                } else {
                    payload
                }
                var q = 0
                while (q + 9 <= records.size) {
                    val innerOp = records[q].toInt() and 0xFF
                    val innerLen = readU64(records, q + 1)
                    if (innerOp == Op.MESSAGE) count++
                    q += 9 + innerLen.toInt()
                }
            }
            pos = (bodyStart + len).toInt()
        }
        return count
    }

    private fun readU32(b: ByteArray, at: Int): Long {
        var v = 0L; for (i in 0 until 4) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i)); return v
    }

    private fun readU64(b: ByteArray, at: Int): Long {
        var v = 0L; for (i in 0 until 8) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i)); return v
    }
}
