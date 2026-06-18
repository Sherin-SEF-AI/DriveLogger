package com.blurabbit.drivelogger.sensors

import com.google.protobuf.Descriptors
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** A topic this source publishes, paired with the protobuf type used to (de)serialize it. */
data class TopicDescriptor(
    val topic: String,
    val descriptor: Descriptors.Descriptor,
    val metadata: Map<String, String> = emptyMap(),
)

/** One timestamped sample ready for the MCAP writer. */
data class SensorRecord(
    val topic: String,
    val unifiedTsNs: Long,
    val message: MessageLite,
)

/** Rolling data-quality snapshot for a source, surfaced to SensorHealth + the dashboard. */
data class SensorHealthSnapshot(
    val sourceId: String,
    val expectedHz: Double,
    val actualHz: Double,
    val droppedSamples: Long,
    val driftMs: Double,
    val healthy: Boolean,
)

/**
 * The single seam every data producer implements. New hardware (RTK GNSS, OBD-II, CAN, LiDAR,
 * USB camera) is added by writing a [SensorSource], declaring a `.proto`, and binding it
 * `@IntoSet` — the recorder, MCAP writer, and health pipeline pick it up with no other changes.
 */
interface SensorSource {
    /** Stable identifier, e.g. "imu", "gnss", "obd". */
    val id: String

    /** Topics + schemas this source emits; used to pre-register MCAP channels. */
    val topics: List<TopicDescriptor>

    /** Whether the underlying hardware exists on this device (skip binding channels if false). */
    fun isAvailable(): Boolean = true

    /** Begin producing records on [scope]; the flow completes when [stop] is called. */
    fun start(scope: CoroutineScope): Flow<SensorRecord>

    fun stop()

    fun health(): SensorHealthSnapshot
}
