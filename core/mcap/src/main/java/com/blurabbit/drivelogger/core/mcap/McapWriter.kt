package com.blurabbit.drivelogger.core.mcap

import com.google.protobuf.Descriptors
import com.google.protobuf.MessageLite
import net.jpountz.lz4.LZ4FrameOutputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Standards-compliant, single-threaded MCAP writer.
 *
 * Layout produced:
 * ```
 * <magic><Header>
 *   <Schema*><Channel*>                       (written to the data section on first use)
 *   ( <Chunk><MessageIndex*> )*               (messages batched, ZSTD-compressed, indexed)
 * <DataEnd>
 *   <Schema*><Channel*><ChunkIndex*><Statistics>   (summary section)
 *   <SummaryOffset*>                                (summary offset section)
 * <Footer><magic>
 * ```
 *
 * Crash safety: chunks + message indexes are flushed incrementally, so the data section on disk
 * is always valid up to the last flush. If [close] never runs (process killed), the file has no
 * summary/footer — [McapRecoveryTool] rebuilds them by re-scanning the data section.
 *
 * Not thread-safe by design: drive it from a single thread (see [McapAsyncWriter]).
 */
class McapWriter(
    file: File,
    private val config: McapWriterConfig = McapWriterConfig(),
) : AutoCloseable {

    private class Sink(out: OutputStream) {
        private val buffered = BufferedOutputStream(out, 1 shl 16)
        private val crc = CRC32()
        @Volatile var position: Long = 0L; private set // read cross-thread for segment-size checks
        fun write(b: ByteArray) { buffered.write(b); position += b.size; crc.update(b) }
        fun runningCrc(): Long = crc.value
        fun flush() = buffered.flush()
        fun close() { buffered.flush(); buffered.close() }
    }

    private data class SchemaRec(val id: Int, val name: String, val encoding: String, val data: ByteArray)
    private data class ChannelRec(val id: Int, val schemaId: Int, val topic: String, val encoding: String, val metadata: Map<String, String>)
    private data class ChunkIndexRec(
        val msgStart: Long, val msgEnd: Long, val chunkStartOffset: Long, val chunkLength: Long,
        val messageIndexOffsets: Map<Int, Long>, val messageIndexLength: Long,
        val compression: String, val compressedSize: Long, val uncompressedSize: Long,
    )

    private val sink = Sink(file.outputStream())

    private var nextSchemaId = 1
    private var nextChannelId = 1
    private val schemasById = LinkedHashMap<Int, SchemaRec>()
    private val schemaIdByName = HashMap<String, Int>()
    private val channelsById = LinkedHashMap<Int, ChannelRec>()
    private val channelIdByTopic = HashMap<String, Int>()
    private val sequenceByChannel = HashMap<Int, Long>()

    private val chunkBuf = ByteArrayOutputStream(config.chunkTargetBytes + (1 shl 16))
    private val pendingIndex = LinkedHashMap<Int, MutableList<LongArray>>() // channelId -> [ [logTime, offset] ]
    private var chunkMsgStart = Long.MAX_VALUE
    private var chunkMsgEnd = Long.MIN_VALUE

    private data class AttachmentIndexRec(
        val offset: Long, val length: Long, val logTime: Long, val createTime: Long,
        val dataSize: Long, val name: String, val mediaType: String,
    )
    private data class MetadataIndexRec(val offset: Long, val length: Long, val name: String)

    private val chunkIndexes = ArrayList<ChunkIndexRec>()
    private val attachmentIndexes = ArrayList<AttachmentIndexRec>()
    private val metadataIndexes = ArrayList<MetadataIndexRec>()
    private val channelMessageCounts = HashMap<Int, Long>()
    private var messageCount = 0L
    private var fileMsgStart = Long.MAX_VALUE
    private var fileMsgEnd = Long.MIN_VALUE
    private var attachmentCount = 0
    private var metadataCount = 0
    private var closed = false

    init {
        sink.write(MCAP_MAGIC)
        val header = Buf().str(config.profile).str(config.library).toByteArray()
        sink.write(record(Op.HEADER, header))
    }

    // ---- registration --------------------------------------------------------------------

    /** Register (or reuse) a protobuf channel for [topic]; returns its channel id. */
    fun channelForProto(
        topic: String,
        descriptor: Descriptors.Descriptor,
        metadata: Map<String, String> = emptyMap(),
    ): Int {
        channelIdByTopic[topic]?.let { return it }
        val schemaId = schemaIdByName.getOrPut(ProtoSchemas.schemaName(descriptor)) {
            registerSchema(
                name = ProtoSchemas.schemaName(descriptor),
                encoding = "protobuf",
                data = ProtoSchemas.fileDescriptorSet(descriptor),
            )
        }
        return registerChannel(topic, schemaId, "protobuf", metadata)
    }

    /**
     * Generic channel registration for any encoding (e.g. "jsonschema", "ros2msg") — also the
     * seam future external sensors use when they don't carry a protobuf [Descriptors.Descriptor].
     */
    fun channel(
        topic: String,
        schemaName: String,
        schemaEncoding: String,
        schemaData: ByteArray,
        messageEncoding: String,
        metadata: Map<String, String> = emptyMap(),
    ): Int {
        channelIdByTopic[topic]?.let { return it }
        val schemaId = schemaIdByName.getOrPut(schemaName) { registerSchema(schemaName, schemaEncoding, schemaData) }
        return registerChannel(topic, schemaId, messageEncoding, metadata)
    }

    private fun registerSchema(name: String, encoding: String, data: ByteArray): Int {
        val id = nextSchemaId++
        val rec = SchemaRec(id, name, encoding, data)
        schemasById[id] = rec
        sink.write(record(Op.SCHEMA, schemaBody(rec)))
        return id
    }

    private fun registerChannel(topic: String, schemaId: Int, encoding: String, metadata: Map<String, String>): Int {
        val id = nextChannelId++
        val rec = ChannelRec(id, schemaId, topic, encoding, metadata)
        channelsById[id] = rec
        channelIdByTopic[topic] = id
        sink.write(record(Op.CHANNEL, channelBody(rec)))
        return id
    }

    // ---- messages ------------------------------------------------------------------------

    /** Approximate bytes written to disk so far (for segment-size rotation). Thread-safe to read. */
    fun approxBytes(): Long = sink.position

    fun writeProto(channelId: Int, logTimeNs: Long, message: MessageLite) =
        writeMessage(channelId, logTimeNs, message.toByteArray())

    /** Append a message; flushes a chunk automatically when the buffer reaches the target size. */
    @Synchronized
    fun writeMessage(channelId: Int, logTimeNs: Long, data: ByteArray, publishTimeNs: Long = logTimeNs) {
        check(!closed) { "writer is closed" }
        val seq = (sequenceByChannel[channelId] ?: 0L).also { sequenceByChannel[channelId] = it + 1 }
        val body = Buf().u16(channelId).u32(seq).u64(logTimeNs).u64(publishTimeNs).raw(data).toByteArray()
        val rec = record(Op.MESSAGE, body)
        val offsetInChunk = chunkBuf.size().toLong()
        chunkBuf.write(rec)

        pendingIndex.getOrPut(channelId) { ArrayList() }.add(longArrayOf(logTimeNs, offsetInChunk))
        if (logTimeNs < chunkMsgStart) chunkMsgStart = logTimeNs
        if (logTimeNs > chunkMsgEnd) chunkMsgEnd = logTimeNs

        messageCount++
        channelMessageCounts.merge(channelId, 1L) { a, b -> a + b }
        if (logTimeNs < fileMsgStart) fileMsgStart = logTimeNs
        if (logTimeNs > fileMsgEnd) fileMsgEnd = logTimeNs

        if (chunkBuf.size() >= config.chunkTargetBytes) flushChunk()
    }

    /** Force the in-memory chunk to disk (used for periodic durability / pause boundaries). */
    @Synchronized
    fun flush() { flushChunk(); sink.flush() }

    private fun flushChunk() {
        if (chunkBuf.size() == 0) return
        val uncompressed = chunkBuf.toByteArray()
        val uncompressedCrc = CRC32().apply { update(uncompressed) }.value

        val payload: ByteArray = when (config.compression) {
            McapWriterConfig.Compression.LZ4 -> lz4Frame(uncompressed)
            McapWriterConfig.Compression.NONE -> uncompressed
        }

        val chunkBody = Buf()
            .u64(chunkMsgStart).u64(chunkMsgEnd)
            .u64(uncompressed.size.toLong()).u32(uncompressedCrc)
            .str(config.compression.token).bytesU64(payload)
            .toByteArray()
        val chunkRec = record(Op.CHUNK, chunkBody)
        val chunkStartOffset = sink.position
        sink.write(chunkRec)

        // Message Index records immediately follow the chunk; capture their absolute offsets.
        val miOffsets = LinkedHashMap<Int, Long>()
        var miTotal = 0L
        for ((channelId, entries) in pendingIndex) {
            val arr = Buf()
            for (e in entries) arr.u64(e[0]).u64(e[1])
            val arrBytes = arr.toByteArray()
            val miBody = Buf().u16(channelId).u32(arrBytes.size.toLong()).raw(arrBytes).toByteArray()
            val miRec = record(Op.MESSAGE_INDEX, miBody)
            miOffsets[channelId] = sink.position
            sink.write(miRec)
            miTotal += miRec.size
        }

        chunkIndexes += ChunkIndexRec(
            msgStart = chunkMsgStart, msgEnd = chunkMsgEnd,
            chunkStartOffset = chunkStartOffset, chunkLength = chunkRec.size.toLong(),
            messageIndexOffsets = miOffsets, messageIndexLength = miTotal,
            compression = config.compression.token,
            compressedSize = payload.size.toLong(), uncompressedSize = uncompressed.size.toLong(),
        )

        chunkBuf.reset()
        pendingIndex.clear()
        chunkMsgStart = Long.MAX_VALUE
        chunkMsgEnd = Long.MIN_VALUE
    }

    // ---- attachments / metadata ----------------------------------------------------------

    /** Embed a file (e.g. camera calibration, metadata.json) as an MCAP attachment. */
    @Synchronized
    fun addAttachment(name: String, mediaType: String, data: ByteArray, logTimeNs: Long, createTimeNs: Long = logTimeNs) {
        flushChunk() // attachments live outside chunks
        val pre = Buf().u64(logTimeNs).u64(createTimeNs).str(name).str(mediaType).bytesU64(data).toByteArray()
        val crc = CRC32().apply { update(pre) }.value
        val rec = record(Op.ATTACHMENT, Buf().raw(pre).u32(crc).toByteArray())
        val offset = sink.position
        sink.write(rec)
        attachmentIndexes += AttachmentIndexRec(offset, rec.size.toLong(), logTimeNs, createTimeNs, data.size.toLong(), name, mediaType)
        attachmentCount++
    }

    @Synchronized
    fun addMetadata(name: String, entries: Map<String, String>) {
        flushChunk()
        val rec = record(Op.METADATA, Buf().str(name).stringMap(entries).toByteArray())
        val offset = sink.position
        sink.write(rec)
        metadataIndexes += MetadataIndexRec(offset, rec.size.toLong(), name)
        metadataCount++
    }

    // ---- finalize ------------------------------------------------------------------------

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        flushChunk()

        // DataEnd: CRC of the entire data section (all bytes so far, including magic + header).
        val dataCrc = sink.runningCrc()
        sink.write(record(Op.DATA_END, Buf().u32(dataCrc).toByteArray()))

        val summaryStart = sink.position

        // Summary section: repeat schemas + channels, then chunk indexes, then statistics.
        val summary = Buf()
        val schemaGroupStart = summary.size()
        schemasById.values.forEach { summary.raw(record(Op.SCHEMA, schemaBody(it))) }
        val schemaGroupLen = summary.size() - schemaGroupStart

        val channelGroupStart = summary.size()
        channelsById.values.forEach { summary.raw(record(Op.CHANNEL, channelBody(it))) }
        val channelGroupLen = summary.size() - channelGroupStart

        val chunkIdxGroupStart = summary.size()
        chunkIndexes.forEach { summary.raw(record(Op.CHUNK_INDEX, chunkIndexBody(it))) }
        val chunkIdxGroupLen = summary.size() - chunkIdxGroupStart

        val attIdxGroupStart = summary.size()
        attachmentIndexes.forEach { summary.raw(record(Op.ATTACHMENT_INDEX, attachmentIndexBody(it))) }
        val attIdxGroupLen = summary.size() - attIdxGroupStart

        val metaIdxGroupStart = summary.size()
        metadataIndexes.forEach { summary.raw(record(Op.METADATA_INDEX, metadataIndexBody(it))) }
        val metaIdxGroupLen = summary.size() - metaIdxGroupStart

        val statsGroupStart = summary.size()
        summary.raw(record(Op.STATISTICS, statisticsBody()))
        val statsGroupLen = summary.size() - statsGroupStart

        val summaryBytes = summary.toByteArray()
        val summaryOffsetStart = summaryStart + summaryBytes.size

        // Summary offset section: one SummaryOffset per non-empty group (absolute offsets).
        val offsets = Buf()
        fun summaryOffset(opcode: Int, relStart: Int, len: Int) {
            if (len <= 0) return
            val b = Buf().u8(opcode).u64(summaryStart + relStart).u64(len.toLong()).toByteArray()
            offsets.raw(record(Op.SUMMARY_OFFSET, b))
        }
        summaryOffset(Op.SCHEMA, schemaGroupStart, schemaGroupLen)
        summaryOffset(Op.CHANNEL, channelGroupStart, channelGroupLen)
        summaryOffset(Op.CHUNK_INDEX, chunkIdxGroupStart, chunkIdxGroupLen)
        summaryOffset(Op.ATTACHMENT_INDEX, attIdxGroupStart, attIdxGroupLen)
        summaryOffset(Op.METADATA_INDEX, metaIdxGroupStart, metaIdxGroupLen)
        summaryOffset(Op.STATISTICS, statsGroupStart, statsGroupLen)
        val offsetBytes = offsets.toByteArray()

        // Footer: opcode + length(20) + summary_start + summary_offset_start, then summary_crc.
        val footerPrefix = Buf().u8(Op.FOOTER).u64(20L).u64(summaryStart).u64(summaryOffsetStart).toByteArray()
        val summaryCrc = CRC32().apply {
            update(summaryBytes); update(offsetBytes); update(footerPrefix)
        }.value

        sink.write(summaryBytes)
        sink.write(offsetBytes)
        sink.write(footerPrefix)
        sink.write(Buf().u32(summaryCrc).toByteArray())
        sink.write(MCAP_MAGIC)
        sink.close()
    }

    // ---- record bodies -------------------------------------------------------------------

    private fun schemaBody(s: SchemaRec): ByteArray =
        Buf().u16(s.id).str(s.name).str(s.encoding).bytesU32(s.data).toByteArray()

    private fun channelBody(c: ChannelRec): ByteArray =
        Buf().u16(c.id).u16(c.schemaId).str(c.topic).str(c.encoding).stringMap(c.metadata).toByteArray()

    private fun chunkIndexBody(ci: ChunkIndexRec): ByteArray {
        val mi = Buf()
        for ((ch, off) in ci.messageIndexOffsets) mi.u16(ch).u64(off)
        val miBytes = mi.toByteArray()
        return Buf()
            .u64(ci.msgStart).u64(ci.msgEnd)
            .u64(ci.chunkStartOffset).u64(ci.chunkLength)
            .u32(miBytes.size.toLong()).raw(miBytes)
            .u64(ci.messageIndexLength)
            .str(ci.compression).u64(ci.compressedSize).u64(ci.uncompressedSize)
            .toByteArray()
    }

    private fun attachmentIndexBody(a: AttachmentIndexRec): ByteArray =
        Buf().u64(a.offset).u64(a.length).u64(a.logTime).u64(a.createTime).u64(a.dataSize)
            .str(a.name).str(a.mediaType).toByteArray()

    private fun metadataIndexBody(m: MetadataIndexRec): ByteArray =
        Buf().u64(m.offset).u64(m.length).str(m.name).toByteArray()

    /** LZ4 frame-format compression. lz4-java auto-falls back to a pure-Java codec on Android. */
    private fun lz4Frame(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size / 2 + 64)
        LZ4FrameOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun statisticsBody(): ByteArray {
        val counts = Buf()
        for ((ch, n) in channelMessageCounts) counts.u16(ch).u64(n)
        val countBytes = counts.toByteArray()
        val start = if (fileMsgStart == Long.MAX_VALUE) 0L else fileMsgStart
        val end = if (fileMsgEnd == Long.MIN_VALUE) 0L else fileMsgEnd
        return Buf()
            .u64(messageCount)
            .u16(schemasById.size)
            .u32(channelsById.size.toLong())
            .u32(attachmentCount.toLong())
            .u32(metadataCount.toLong())
            .u32(chunkIndexes.size.toLong())
            .u64(start).u64(end)
            .u32(countBytes.size.toLong()).raw(countBytes)
            .toByteArray()
    }
}
