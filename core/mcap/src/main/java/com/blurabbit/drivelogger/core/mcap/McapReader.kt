package com.blurabbit.drivelogger.core.mcap

import net.jpountz.lz4.LZ4FrameInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Forward, streaming reader for the MCAP files this app writes (the counterpart to [McapWriter]).
 * Walks the data section record-by-record, decodes schemas/channels, decompresses LZ4 chunks, and
 * surfaces each message with its topic, schema name, timestamps, and raw payload bytes (decode with
 * the matching protobuf type). Resilient to a missing summary/footer (crash-truncated files): it
 * stops cleanly at DataEnd/Footer or EOF.
 *
 * ```
 * McapReader(file).forEachMessage(setOf("/gps/fix")) { m ->
 *     val fix = foxglove.LocationFix.parseFrom(m.data)
 * }
 * ```
 */
class McapReader(private val file: File) {

    data class Schema(val id: Int, val name: String, val encoding: String, val data: ByteArray)
    data class Channel(
        val id: Int, val topic: String, val schemaId: Int,
        val messageEncoding: String, val metadata: Map<String, String>,
    )
    data class Message(
        val channelId: Int, val topic: String, val schemaName: String,
        val sequence: Long, val logTimeNs: Long, val publishTimeNs: Long, val data: ByteArray,
    )

    private val schemasById = LinkedHashMap<Int, Schema>()
    private val channelsById = LinkedHashMap<Int, Channel>()

    /** Schemas seen so far (fully populated after a complete [forEachMessage] pass). */
    val schemas: Map<Int, Schema> get() = schemasById
    /** Channels seen so far (fully populated after a complete [forEachMessage] pass). */
    val channels: Map<Int, Channel> get() = channelsById

    /** Streams every message (optionally only those on [topics]) to [action], in file order. */
    fun forEachMessage(topics: Set<String>? = null, action: (Message) -> Unit) {
        file.inputStream().buffered(1 shl 16).use { input ->
            val magic = input.readExactly(MCAP_MAGIC.size)
            require(magic != null && magic.contentEquals(MCAP_MAGIC)) { "not an MCAP file (bad leading magic)" }
            while (true) {
                val op = input.read()
                if (op < 0) break
                val len = readU64(input) ?: break
                when (op) {
                    Op.SCHEMA -> registerSchema(Cursor(input.readExactly(len.toInt()) ?: break))
                    Op.CHANNEL -> registerChannel(Cursor(input.readExactly(len.toInt()) ?: break))
                    Op.MESSAGE -> emit(Cursor(input.readExactly(len.toInt()) ?: break), topics, action)
                    Op.CHUNK -> readChunk(input.readExactly(len.toInt()) ?: break, topics, action)
                    Op.DATA_END, Op.FOOTER -> return // summary section (or footer) — nothing more to read
                    else -> if (input.skipFully(len) == null) break // header, indexes, attachments, metadata, summary
                }
            }
        }
    }

    /** Convenience: collect all matching messages into a list (small/medium logs). */
    fun readMessages(topics: Set<String>? = null): List<Message> =
        ArrayList<Message>().also { out -> forEachMessage(topics) { out += it } }

    /** Message counts keyed by topic — a quick `mcap info`-style summary via a full pass. */
    fun topicCounts(): Map<String, Long> {
        val counts = LinkedHashMap<String, Long>()
        forEachMessage { counts.merge(it.topic, 1L) { a, b -> a + b } }
        return counts
    }

    // ---- record decoding -----------------------------------------------------------------

    private fun registerSchema(c: Cursor) {
        val id = c.u16(); val name = c.str(); val encoding = c.str(); val data = c.bytesU32()
        schemasById[id] = Schema(id, name, encoding, data)
    }

    private fun registerChannel(c: Cursor) {
        val id = c.u16(); val schemaId = c.u16(); val topic = c.str()
        val encoding = c.str(); val metadata = c.stringMap()
        channelsById[id] = Channel(id, topic, schemaId, encoding, metadata)
    }

    private fun readChunk(body: ByteArray, topics: Set<String>?, action: (Message) -> Unit) {
        val c = Cursor(body)
        c.u64(); c.u64(); c.u64(); c.u32() // msgStart, msgEnd, uncompressedSize, uncompressedCrc
        val compression = c.str()
        val payload = c.bytesU64()
        val records: ByteArray = when (compression) {
            // NB: use copyTo, not readBytes() — readBytes() calls available(), which NPEs on
            // LZ4FrameInputStream before its buffer is lazily initialized by the first read().
            "lz4" -> LZ4FrameInputStream(ByteArrayInputStream(payload)).use { lz4 ->
                java.io.ByteArrayOutputStream(maxOf(64, payload.size * 2)).also { lz4.copyTo(it) }.toByteArray()
            }
            "", "none" -> payload
            else -> error("unsupported chunk compression: $compression")
        }
        val rc = Cursor(records)
        while (rc.remaining() >= 9) {
            val op = rc.u8()
            val len = rc.u64()
            val end = rc.pos + len.toInt()
            if (op == Op.MESSAGE) emit(Cursor(records, rc.pos, end), topics, action)
            rc.pos = end
        }
    }

    private fun emit(c: Cursor, topics: Set<String>?, action: (Message) -> Unit) {
        val channelId = c.u16()
        val ch = channelsById[channelId] ?: return
        if (topics != null && ch.topic !in topics) return
        val sequence = c.u32()
        val logTime = c.u64()
        val publishTime = c.u64()
        val data = c.rest()
        action(Message(channelId, ch.topic, schemasById[ch.schemaId]?.name ?: "", sequence, logTime, publishTime, data))
    }

    /** Little-endian cursor over a record body. */
    private class Cursor(private val b: ByteArray, var pos: Int = 0, private val limit: Int = b.size) {
        fun remaining(): Int = limit - pos
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun u16(): Int { val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
        fun u32(): Long { var v = 0L; for (i in 0 until 4) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i)); pos += 4; return v }
        fun u64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i)); pos += 8; return v }
        fun str(): String { val n = u32().toInt(); val s = String(b, pos, n, Charsets.UTF_8); pos += n; return s }
        fun bytesU32(): ByteArray { val n = u32().toInt(); val out = b.copyOfRange(pos, pos + n); pos += n; return out }
        fun bytesU64(): ByteArray { val n = u64().toInt(); val out = b.copyOfRange(pos, pos + n); pos += n; return out }
        fun rest(): ByteArray = b.copyOfRange(pos, limit).also { pos = limit }
        fun stringMap(): Map<String, String> {
            val n = u32().toInt(); val mapEnd = pos + n; val m = LinkedHashMap<String, String>()
            while (pos < mapEnd) { val k = str(); val v = str(); m[k] = v }
            return m
        }
    }
}

private fun readU64(input: InputStream): Long? {
    var v = 0L
    for (i in 0 until 8) { val x = input.read(); if (x < 0) return null; v = v or ((x.toLong() and 0xFF) shl (8 * i)) }
    return v
}

/** Reads exactly [n] bytes, or null on EOF/truncation. (Avoids API-33-only InputStream.readNBytes.) */
private fun InputStream.readExactly(n: Int): ByteArray? {
    val out = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(out, off, n - off)
        if (r < 0) return null
        off += r
    }
    return out
}

/** Skips exactly [n] bytes, or null on EOF/truncation. */
private fun InputStream.skipFully(n: Long): Unit? {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s > 0) { left -= s } else { if (read() < 0) return null; left-- }
    }
    return Unit
}
