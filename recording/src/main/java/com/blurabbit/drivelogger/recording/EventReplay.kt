package com.blurabbit.drivelogger.recording

import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.core.mcap.McapReader
import com.blurabbit.drivelogger.events.AccelSample
import com.blurabbit.drivelogger.events.EventDetector
import com.blurabbit.drivelogger.events.EventRule
import com.blurabbit.drivelogger.events.GravitySample
import com.blurabbit.drivelogger.events.GyroSample
import com.blurabbit.drivelogger.events.SpeedSample
import com.blurabbit.drivelogger.events.rules.HardBrakingRule
import com.blurabbit.drivelogger.events.rules.PotholeImpactRule
import com.blurabbit.drivelogger.events.rules.RapidLaneChangeRule
import com.blurabbit.drivelogger.events.rules.SharpTurnRule
import com.blurabbit.drivelogger.events.rules.SpeedBumpRule
import com.blurabbit.drivelogger.events.rules.SuddenAccelerationRule
import com.blurabbit.drivelogger.proto.DrivingEvent
import com.blurabbit.drivelogger.proto.GpsExtras
import com.blurabbit.drivelogger.proto.Vector3Stamped
import java.io.File

/**
 * Offline event replay: streams a recorded `trip.mcap` through the **same** [EventDetector] + rules
 * the live pipeline uses, deterministically (no coroutines/flows — a synchronous sink), and returns
 * the events that fire. Lets you validate and tune event thresholds against real drives without
 * re-recording, and is the natural companion to the vehicle-frame calibration.
 *
 * Feeds samples in file (arrival) order — exactly how the live recorder fed them — using each
 * message's own `unified_ns` for detector timing.
 */
object EventReplay {

    data class Result(
        val events: List<DrivingEvent>,
        val countsByType: Map<DrivingEvent.EventType, Int>,
        val topicCounts: Map<String, Long>,
        val durationSeconds: Double,
    )

    fun defaultRules(): Set<EventRule> = setOf(
        HardBrakingRule(), SuddenAccelerationRule(), SharpTurnRule(),
        PotholeImpactRule(), SpeedBumpRule(), RapidLaneChangeRule(),
    )

    fun replay(mcap: File, rules: Set<EventRule> = defaultRules()): Result {
        val fired = ArrayList<DrivingEvent>()
        val detector = EventDetector(rules).apply { sink = { fired += it } }
        var lastLat = 0.0; var lastLon = 0.0
        var minNs = Long.MAX_VALUE; var maxNs = Long.MIN_VALUE
        fun mark(ns: Long) { if (ns < minNs) minNs = ns; if (ns > maxNs) maxNs = ns }

        McapReader(mcap).forEachMessage(
            setOf(Topics.IMU_LINEAR_ACCEL, Topics.IMU_GYRO, Topics.IMU_GRAVITY, Topics.GPS_FIX, Topics.GPS_VELOCITY),
        ) { m ->
            runCatching {
                when (m.topic) {
                    Topics.IMU_LINEAR_ACCEL -> Vector3Stamped.parseFrom(m.data).let { v ->
                        mark(v.unifiedNs); detector.onAccel(AccelSample(v.unifiedNs, v.x, v.y, v.z))
                    }
                    Topics.IMU_GYRO -> Vector3Stamped.parseFrom(m.data).let { v ->
                        detector.onGyro(GyroSample(v.unifiedNs, v.x, v.y, v.z))
                    }
                    Topics.IMU_GRAVITY -> Vector3Stamped.parseFrom(m.data).let { v ->
                        detector.onGravity(GravitySample(v.unifiedNs, v.x, v.y, v.z))
                    }
                    Topics.GPS_FIX -> foxglove.LocationFix.parseFrom(m.data).let { lastLat = it.latitude; lastLon = it.longitude }
                    Topics.GPS_VELOCITY -> GpsExtras.parseFrom(m.data).let { ex ->
                        mark(ex.unifiedNs); detector.onSpeed(SpeedSample(ex.unifiedNs, ex.speedMps, lastLat, lastLon))
                    }
                }
            }
        }

        val counts = fired.groupingBy { it.type }.eachCount()
        val durS = if (maxNs >= minNs) (maxNs - minNs) / 1e9 else 0.0
        return Result(fired, counts, McapReader(mcap).topicCounts(), durS)
    }
}
