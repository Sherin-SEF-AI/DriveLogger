package com.blurabbit.drivelogger.core.mcap

import java.io.ByteArrayOutputStream

/**
 * MCAP binary constants and low-level little-endian primitives.
 * Spec: https://mcap.dev/spec
 */
internal object Op {
    const val HEADER = 0x01
    const val FOOTER = 0x02
    const val SCHEMA = 0x03
    const val CHANNEL = 0x04
    const val MESSAGE = 0x05
    const val CHUNK = 0x06
    const val MESSAGE_INDEX = 0x07
    const val CHUNK_INDEX = 0x08
    const val ATTACHMENT = 0x09
    const val ATTACHMENT_INDEX = 0x0A
    const val STATISTICS = 0x0B
    const val METADATA = 0x0C
    const val METADATA_INDEX = 0x0D
    const val SUMMARY_OFFSET = 0x0E
    const val DATA_END = 0x0F
}

/** `\x89MCAP0\r\n` — written at the start and end of every file. */
internal val MCAP_MAGIC = byteArrayOf(
    0x89.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(),
    'P'.code.toByte(), '0'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(),
)

/** Growable little-endian byte buffer used to assemble records. */
internal class Buf {
    private val bos = ByteArrayOutputStream(256)

    fun size(): Int = bos.size()

    fun u8(v: Int): Buf { bos.write(v and 0xFF); return this }

    fun u16(v: Int): Buf {
        bos.write(v and 0xFF); bos.write((v ushr 8) and 0xFF); return this
    }

    fun u32(v: Long): Buf {
        bos.write((v and 0xFF).toInt())
        bos.write(((v ushr 8) and 0xFF).toInt())
        bos.write(((v ushr 16) and 0xFF).toInt())
        bos.write(((v ushr 24) and 0xFF).toInt())
        return this
    }

    fun u64(v: Long): Buf {
        var x = v
        repeat(8) { bos.write((x and 0xFF).toInt()); x = x ushr 8 }
        return this
    }

    /** uint32 length-prefixed UTF-8 string. */
    fun str(s: String): Buf {
        val b = s.toByteArray(Charsets.UTF_8)
        u32(b.size.toLong()); bos.write(b); return this
    }

    /** uint32 length-prefixed byte blob. */
    fun bytesU32(b: ByteArray): Buf { u32(b.size.toLong()); bos.write(b); return this }

    /** uint64 length-prefixed byte blob. */
    fun bytesU64(b: ByteArray): Buf { u64(b.size.toLong()); bos.write(b); return this }

    fun raw(b: ByteArray): Buf { bos.write(b); return this }

    /** Map<string,string> — uint32 total-byte-length prefix then key/value string pairs. */
    fun stringMap(map: Map<String, String>): Buf {
        val inner = Buf()
        for ((k, v) in map) { inner.str(k); inner.str(v) }
        val bytes = inner.toByteArray()
        u32(bytes.size.toLong()); bos.write(bytes); return this
    }

    fun toByteArray(): ByteArray = bos.toByteArray()
}

/** Wrap a record body with `opcode (u8) | length (u64) | body`. */
internal fun record(opcode: Int, body: ByteArray): ByteArray =
    Buf().u8(opcode).u64(body.size.toLong()).raw(body).toByteArray()

/** Tunables for a single MCAP file. */
data class McapWriterConfig(
    val profile: String = "",                       // "" = no specific ROS/x profile
    val library: String = "blurabbit-drivelogger/0.1",
    val chunkTargetBytes: Int = 4 * 1024 * 1024,    // flush a chunk at ~4 MiB uncompressed
    val compression: Compression = Compression.LZ4,
) {
    // LZ4 has a pure-Java implementation (works on Android); MCAP/Foxglove decode it natively.
    enum class Compression(val token: String) { NONE(""), LZ4("lz4") }
}

/** A topic and the proto message type that flows on it — used to pre-register channels. */
data class TopicSchema(
    val topic: String,
    val descriptor: com.google.protobuf.Descriptors.Descriptor,
    val metadata: Map<String, String> = emptyMap(),
)
