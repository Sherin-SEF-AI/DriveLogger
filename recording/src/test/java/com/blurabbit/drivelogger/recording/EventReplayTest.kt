package com.blurabbit.drivelogger.recording

import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.core.mcap.McapWriter
import com.blurabbit.drivelogger.core.mcap.McapWriterConfig
import com.blurabbit.drivelogger.proto.DrivingEvent.EventType
import com.blurabbit.drivelogger.proto.GpsExtras
import com.blurabbit.drivelogger.proto.Vector3Stamped
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.MessageLite
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end: synthesize a trip.mcap on a TILTED mount (vehicle up = device +y, forward = device +z),
 * then [EventReplay] it and assert the events fire — proving writer → McapReader → EventDetector with
 * vehicle-frame calibration on real serialized protobuf data, regardless of phone orientation.
 */
class EventReplayTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sec = 1_000_000_000L
    // Realistic unified clock (elapsedRealtimeNanos ~10^15) so per-rule cooldowns behave as in the field.
    private val base = 600_000_000_000_000L

    // Map a vehicle vector (forward,left,up) into the (tilted) device frame: device = (left, up, forward).
    private fun vec(t: Long, fwd: Double, left: Double, up: Double): Vector3Stamped =
        Vector3Stamped.newBuilder().setUnifiedNs(t).setX(left).setY(up).setZ(fwd).build()

    private fun speed(t: Long, mps: Double): GpsExtras =
        GpsExtras.newBuilder().setUnifiedNs(t).setSpeedMps(mps).build()

    @Test
    fun `replays a recorded trip and fires brake, pothole and sharp-turn on a tilted mount`() {
        val msgs = ArrayList<Triple<String, Long, MessageLite>>()
        // Gravity along vehicle-up (device +y) for the whole clip → calibrates the vertical axis.
        var t = base
        while (t <= base + 6 * sec) { msgs += Triple(Topics.IMU_GRAVITY, t, vec(t, 0.0, 0.0, 9.8)); t += sec / 2 }
        // Braking: GPS speed 20 → 14 → 10 (≈ −5 m/s²), then roughly flat.
        listOf(0L to 20.0, 1L to 14.0, 2L to 10.0, 3L to 10.0, 4L to 9.5, 5L to 9.5, 6L to 9.5)
            .forEach { (s, v) -> val ts = base + s * sec; msgs += Triple(Topics.GPS_VELOCITY, ts, speed(ts, v)) }
        // Pothole: pure vehicle-vertical impact (device +y) while moving.
        msgs += Triple(Topics.IMU_LINEAR_ACCEL, base + 25 * sec / 10, vec(base + 25 * sec / 10, 0.0, 0.0, 12.0))
        // Sharp turn: yaw about vehicle-up (device +y); a following speed sample triggers evaluation.
        msgs += Triple(Topics.IMU_GYRO, base + 44 * sec / 10, vec(base + 44 * sec / 10, 0.0, 0.0, 0.8))
        msgs += Triple(Topics.GPS_VELOCITY, base + 45 * sec / 10, speed(base + 45 * sec / 10, 9.5))

        val f = tmp.newFile("trip.mcap")
        McapWriter(f, McapWriterConfig(compression = McapWriterConfig.Compression.LZ4)).use { w ->
            val chans = mapOf(
                Topics.IMU_GRAVITY to w.channelForProto(Topics.IMU_GRAVITY, Vector3Stamped.getDescriptor()),
                Topics.IMU_LINEAR_ACCEL to w.channelForProto(Topics.IMU_LINEAR_ACCEL, Vector3Stamped.getDescriptor()),
                Topics.IMU_GYRO to w.channelForProto(Topics.IMU_GYRO, Vector3Stamped.getDescriptor()),
                Topics.GPS_VELOCITY to w.channelForProto(Topics.GPS_VELOCITY, GpsExtras.getDescriptor()),
            )
            msgs.sortedBy { it.second }.forEach { (topic, ts, m) -> w.writeProto(chans.getValue(topic), ts, m) }
        }

        val result = EventReplay.replay(f)
        val types = result.countsByType.keys

        assertThat(types).contains(EventType.HARD_BRAKING)
        assertThat(types).contains(EventType.POTHOLE_IMPACT)
        assertThat(types).contains(EventType.SHARP_TURN)
        assertThat(result.durationSeconds).isGreaterThan(0.0)
        // Sanity: the data was written with device-z ≈ 0 for the vertical/yaw events — only the
        // vehicle-frame projection recovers them.
        assertThat(result.events).isNotEmpty()
    }
}
