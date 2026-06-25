package com.blurabbit.drivelogger.core.mcap

import com.google.protobuf.MessageLite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Actor wrapper around [McapWriter]: all encoding + disk I/O happen on one dedicated thread fed
 * by a bounded [Channel]. Sensor producers call [write] from any thread and are back-pressured
 * (suspended) when the queue is full — guaranteeing lossless, in-order writes without locks on
 * the hot path. Dropped/enqueued counters feed the data-quality monitor.
 */
class McapAsyncWriter(
    file: File,
    config: McapWriterConfig,
    private val schemas: List<TopicSchema>,
    queueCapacity: Int = 2048,
) {
    private sealed interface Cmd {
        class Write(val topic: String, val logTimeNs: Long, val bytes: ByteArray) : Cmd
        class Attachment(val name: String, val mediaType: String, val data: ByteArray, val ts: Long) : Cmd
        class Metadata(val name: String, val entries: Map<String, String>) : Cmd
        class Flush(val ack: CompletableDeferred<Unit>) : Cmd
        class Close(val ack: CompletableDeferred<Unit>) : Cmd
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mcap-writer").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val commands = Channel<Cmd>(capacity = queueCapacity)

    val enqueued = AtomicLong(0)
    val written = AtomicLong(0)

    @Volatile private var writerRef: McapWriter? = null

    /** Approximate bytes written to disk so far — drives size-based segment rotation. */
    fun approxBytes(): Long = writerRef?.approxBytes() ?: 0L

    init {
        scope.launch {
            val writer = McapWriter(file, config)
            writerRef = writer
            val channelByTopic = HashMap<String, Int>()
            // Pre-register every known topic so channel ids are stable from the first message.
            schemas.forEach { s ->
                channelByTopic[s.topic] = writer.channelForProto(s.topic, s.descriptor, s.metadata)
            }
            var closeAck: CompletableDeferred<Unit>? = null
            writer.use { w ->
                loop@ for (cmd in commands) {
                    when (cmd) {
                        is Cmd.Write -> {
                            val ch = channelByTopic[cmd.topic]
                                ?: error("unregistered topic ${cmd.topic}")
                            w.writeMessage(ch, cmd.logTimeNs, cmd.bytes)
                            written.incrementAndGet()
                        }
                        is Cmd.Attachment -> w.addAttachment(cmd.name, cmd.mediaType, cmd.data, cmd.ts)
                        is Cmd.Metadata -> w.addMetadata(cmd.name, cmd.entries)
                        is Cmd.Flush -> { w.flush(); cmd.ack.complete(Unit) }
                        is Cmd.Close -> { closeAck = cmd.ack; break@loop }
                    }
                }
            } // writer.close() here writes the summary + footer + trailing magic
            closeAck?.complete(Unit) // only now is the file fully finalized
        }
    }

    suspend fun write(topic: String, logTimeNs: Long, message: MessageLite) {
        enqueued.incrementAndGet()
        commands.send(Cmd.Write(topic, logTimeNs, message.toByteArray()))
    }

    /** Non-suspending offer; returns false if the queue is full (caller may count a drop). */
    fun offer(topic: String, logTimeNs: Long, message: MessageLite): Boolean {
        val ok = commands.trySend(Cmd.Write(topic, logTimeNs, message.toByteArray())).isSuccess
        if (ok) enqueued.incrementAndGet()
        return ok
    }

    suspend fun attachment(name: String, mediaType: String, data: ByteArray, ts: Long) =
        commands.send(Cmd.Attachment(name, mediaType, data, ts))

    suspend fun metadata(name: String, entries: Map<String, String>) =
        commands.send(Cmd.Metadata(name, entries))

    suspend fun flush() {
        val ack = CompletableDeferred<Unit>()
        commands.send(Cmd.Flush(ack)); ack.await()
    }

    /** Flush, write the summary/footer, and release the writer thread. */
    suspend fun close() {
        val ack = CompletableDeferred<Unit>()
        commands.send(Cmd.Close(ack))
        ack.await()
        commands.close()
        dispatcher.close()
        executor.shutdown()
    }
}
