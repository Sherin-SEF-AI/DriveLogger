package com.blurabbit.drivelogger.sensors.impl

import com.blurabbit.drivelogger.proto.Environment
import com.blurabbit.drivelogger.sensors.SensorHealthSnapshot
import com.blurabbit.drivelogger.sensors.SensorRecord
import com.blurabbit.drivelogger.sensors.SensorSource
import com.blurabbit.drivelogger.sensors.TopicDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject

/**
 * EXAMPLE EXTENSIBILITY SEAM — not wired into the recorder by default.
 *
 * Demonstrates how a future external sensor (OBD-II / CAN over Bluetooth/USB, RTK GNSS, LiDAR)
 * plugs in: implement [SensorSource], declare a `.proto` for its topic (e.g. `/vehicle/obd`),
 * publish [SensorRecord]s on the unified clock, and add ONE `@IntoSet` binding in SensorModule.
 * Nothing in the recording pipeline, MCAP writer, or health system changes.
 *
 * [isAvailable] returns false so it is excluded until a real transport is implemented.
 */
class ObdSensorSource @Inject constructor() : SensorSource {
    override val id: String = "obd"

    // Placeholder schema reference; replace with a real blurabbit.ObdFrame descriptor.
    override val topics: List<TopicDescriptor> =
        listOf(TopicDescriptor("/vehicle/obd", Environment.getDescriptor()))

    override fun isAvailable(): Boolean = false // no OBD transport connected yet

    override fun start(scope: CoroutineScope): Flow<SensorRecord> = emptyFlow()
    override fun stop() {}
    override fun health(): SensorHealthSnapshot =
        SensorHealthSnapshot(id, 0.0, 0.0, 0, 0.0, healthy = true)
}
