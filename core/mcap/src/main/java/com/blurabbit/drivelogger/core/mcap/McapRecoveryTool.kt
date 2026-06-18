package com.blurabbit.drivelogger.core.mcap

import java.io.BufferedOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Rebuilds a valid MCAP file from one whose process was killed mid-recording (no DataEnd /
 * summary / footer, possibly a truncated trailing record).
 *
 * Strategy: scan the data section record-by-record, keeping only fully-readable records; then
 * append a freshly computed DataEnd + summary section + summary offset section + footer. Chunk
 * payloads are never decompressed — only their headers are parsed — so recovery is fast and
 * memory-light even for multi-GB trips.
 */
object McapRecoveryTool {

    data class Result(val messageCount: Long, val chunkCount: Int, val recoveredBytes: Long)

    private class ChunkInfo(
        val startOffset: Long, val length: Long,
        val msgStart: Long, val msgEnd: Long,
        val compression: String, val compressedSize: Long, val uncompressedSize: Long,
    ) {
        val messageIndexOffsets = LinkedHashMap<Int, Long>()
        var messageIndexLength = 0L
    }

    /** @return recovery stats, or null if [source] is not a recoverable MCAP file. */
    fun recover(source: java.io.File, dest: java.io.File): Result? {
        RandomAccessFile(source, "r").use { raf ->
            val fileLen = raf.length()
            if (fileLen < MCAP_MAGIC.size) return null
            val magic = ByteArray(MCAP_MAGIC.size).also { raf.seek(0); raf.readFully(it) }
            if (!magic.contentEquals(MCAP_MAGIC)) return null

            val schemaBodies = ArrayList<ByteArray>()
            val channelBodies = ArrayList<ByteArray>()
            val chunks = ArrayList<ChunkInfo>()
            val channelMessageCounts = HashMap<Int, Long>()
            var messageCount = 0L
            var attachmentCount = 0
            var metadataCount = 0
            var current: ChunkInfo? = null

            var pos = MCAP_MAGIC.size.toLong()
            var validEnd = pos
            while (pos + 9 <= fileLen) {
                raf.seek(pos)
                val op = raf.read()
                val len = readU64(raf)
                val bodyStart = pos + 9
                val recEnd = bodyStart + len
                if (recEnd > fileLen) break // truncated trailing record — drop it

                when (op) {
                    Op.HEADER -> { /* preserved verbatim in the copied prefix */ }
                    Op.SCHEMA -> schemaBodies += readBytes(raf, bodyStart, len.toInt())
                    Op.CHANNEL -> channelBodies += readBytes(raf, bodyStart, len.toInt())
                    Op.CHUNK -> {
                        current = parseChunkHeader(raf, bodyStart, pos, recEnd - pos)
                        chunks += current
                    }
                    Op.MESSAGE_INDEX -> {
                        raf.seek(bodyStart)
                        val channelId = readU16(raf)
                        val arrLen = readU32(raf)
                        val count = arrLen / 16 // each entry = u64 time + u64 offset
                        channelMessageCounts.merge(channelId, count) { a, b -> a + b }
                        messageCount += count
                        current?.let {
                            it.messageIndexOffsets[channelId] = pos
                            it.messageIndexLength += (recEnd - pos)
                        }
                    }
                    Op.ATTACHMENT -> attachmentCount++
                    Op.METADATA -> metadataCount++
                    Op.DATA_END, Op.FOOTER, Op.SUMMARY_OFFSET,
                    Op.CHUNK_INDEX, Op.STATISTICS, Op.ATTACHMENT_INDEX, Op.METADATA_INDEX -> {
                        // We've reached an already-written (or partial) summary — rebuild from here.
                        validEnd = pos
                        return writeRecovered(
                            source, dest, validEnd, schemaBodies, channelBodies, chunks,
                            channelMessageCounts, messageCount, attachmentCount, metadataCount,
                        )
                    }
                }
                pos = recEnd
                validEnd = pos
            }

            return writeRecovered(
                source, dest, validEnd, schemaBodies, channelBodies, chunks,
                channelMessageCounts, messageCount, attachmentCount, metadataCount,
            )
        }
    }

    private fun writeRecovered(
        source: java.io.File, dest: java.io.File, validEnd: Long,
        schemaBodies: List<ByteArray>, channelBodies: List<ByteArray>, chunks: List<ChunkInfo>,
        channelMessageCounts: Map<Int, Long>, messageCount: Long,
        attachmentCount: Int, metadataCount: Int,
    ): Result {
        val crc = CRC32()
        BufferedOutputStream(dest.outputStream(), 1 shl 16).use { out ->
            // Copy the valid data-section prefix verbatim, accumulating its CRC.
            RandomAccessFile(source, "r").use { src ->
                src.seek(0)
                val buf = ByteArray(1 shl 16)
                var remaining = validEnd
                while (remaining > 0) {
                    val n = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n); crc.update(buf, 0, n); remaining -= n
                }
            }

            out.write(record(Op.DATA_END, Buf().u32(crc.value).toByteArray()))
            val summaryStart = validEnd + 9 + 4 // DataEnd record size = op(1)+len(8)+crc(4)

            val summary = Buf()
            val schemaStart = summary.size()
            schemaBodies.forEach { summary.raw(record(Op.SCHEMA, it)) }
            val schemaLen = summary.size() - schemaStart
            val channelStart = summary.size()
            channelBodies.forEach { summary.raw(record(Op.CHANNEL, it)) }
            val channelLen = summary.size() - channelStart
            val chunkIdxStart = summary.size()
            chunks.forEach { summary.raw(record(Op.CHUNK_INDEX, chunkIndexBody(it))) }
            val chunkIdxLen = summary.size() - chunkIdxStart
            val statsStart = summary.size()
            summary.raw(record(Op.STATISTICS, statisticsBody(
                messageCount, schemaBodies.size, channelBodies.size,
                attachmentCount, metadataCount, chunks, channelMessageCounts,
            )))
            val statsLen = summary.size() - statsStart

            val summaryBytes = summary.toByteArray()
            val summaryOffsetStart = summaryStart + summaryBytes.size

            val offsets = Buf()
            fun so(op: Int, rel: Int, len: Int) {
                if (len <= 0) return
                offsets.raw(record(Op.SUMMARY_OFFSET,
                    Buf().u8(op).u64(summaryStart + rel).u64(len.toLong()).toByteArray()))
            }
            so(Op.SCHEMA, schemaStart, schemaLen)
            so(Op.CHANNEL, channelStart, channelLen)
            so(Op.CHUNK_INDEX, chunkIdxStart, chunkIdxLen)
            so(Op.STATISTICS, statsStart, statsLen)
            val offsetBytes = offsets.toByteArray()

            val footerPrefix = Buf().u8(Op.FOOTER).u64(20L).u64(summaryStart).u64(summaryOffsetStart).toByteArray()
            val summaryCrc = CRC32().apply { update(summaryBytes); update(offsetBytes); update(footerPrefix) }.value

            out.write(summaryBytes)
            out.write(offsetBytes)
            out.write(footerPrefix)
            out.write(Buf().u32(summaryCrc).toByteArray())
            out.write(MCAP_MAGIC)
        }
        return Result(messageCount, chunks.size, dest.length())
    }

    private fun chunkIndexBody(ci: ChunkInfo): ByteArray {
        val mi = Buf()
        for ((ch, off) in ci.messageIndexOffsets) mi.u16(ch).u64(off)
        val miBytes = mi.toByteArray()
        return Buf()
            .u64(ci.msgStart).u64(ci.msgEnd)
            .u64(ci.startOffset).u64(ci.length)
            .u32(miBytes.size.toLong()).raw(miBytes)
            .u64(ci.messageIndexLength)
            .str(ci.compression).u64(ci.compressedSize).u64(ci.uncompressedSize)
            .toByteArray()
    }

    private fun statisticsBody(
        messageCount: Long, schemaCount: Int, channelCount: Int,
        attachmentCount: Int, metadataCount: Int,
        chunks: List<ChunkInfo>, channelMessageCounts: Map<Int, Long>,
    ): ByteArray {
        val counts = Buf()
        for ((ch, n) in channelMessageCounts) counts.u16(ch).u64(n)
        val countBytes = counts.toByteArray()
        val start = chunks.minOfOrNull { it.msgStart } ?: 0L
        val end = chunks.maxOfOrNull { it.msgEnd } ?: 0L
        return Buf()
            .u64(messageCount).u16(schemaCount).u32(channelCount.toLong())
            .u32(attachmentCount.toLong()).u32(metadataCount.toLong()).u32(chunks.size.toLong())
            .u64(start).u64(end)
            .u32(countBytes.size.toLong()).raw(countBytes)
            .toByteArray()
    }

    private fun parseChunkHeader(raf: RandomAccessFile, bodyStart: Long, recStart: Long, recLen: Long): ChunkInfo {
        raf.seek(bodyStart)
        val msgStart = readU64(raf)
        val msgEnd = readU64(raf)
        val uncompressedSize = readU64(raf)
        readU32(raf) // uncompressed_crc
        val compLen = readU32(raf).toInt()
        val comp = ByteArray(compLen).also { raf.readFully(it) }.toString(Charsets.UTF_8)
        val compressedSize = readU64(raf) // records (payload) length prefix
        return ChunkInfo(recStart, recLen, msgStart, msgEnd, comp, compressedSize, uncompressedSize)
    }

    // ---- little-endian readers -----------------------------------------------------------

    private fun readBytes(raf: RandomAccessFile, at: Long, len: Int): ByteArray {
        raf.seek(at); return ByteArray(len).also { raf.readFully(it) }
    }

    private fun readU16(raf: RandomAccessFile): Int {
        val a = raf.read(); val b = raf.read(); return (a and 0xFF) or ((b and 0xFF) shl 8)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        var v = 0L; for (i in 0 until 4) v = v or ((raf.read().toLong() and 0xFF) shl (8 * i)); return v
    }

    private fun readU64(raf: RandomAccessFile): Long {
        var v = 0L; for (i in 0 until 8) v = v or ((raf.read().toLong() and 0xFF) shl (8 * i)); return v
    }
}
