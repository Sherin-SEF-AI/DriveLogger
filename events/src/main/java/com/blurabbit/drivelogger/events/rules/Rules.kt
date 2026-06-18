package com.blurabbit.drivelogger.events.rules

import com.blurabbit.drivelogger.events.EventRule
import com.blurabbit.drivelogger.events.SensorWindow
import com.blurabbit.drivelogger.proto.DrivingEvent
import com.blurabbit.drivelogger.proto.DrivingEvent.EventType
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

/** Shared helper: build a DrivingEvent stamped at [nowNs] with the latest GPS context. */
private fun SensorWindow.event(
    type: EventType, nowNs: Long, confidence: Double, evidence: String,
): DrivingEvent {
    val s = latestSpeed()
    return DrivingEvent.newBuilder()
        .setUnifiedNs(nowNs).setType(type)
        .setConfidence(confidence.coerceIn(0.0, 1.0))
        .setEvidenceJson(evidence)
        .setLatitude(s?.lat ?: 0.0).setLongitude(s?.lon ?: 0.0)
        .setSpeedMps(s?.speedMps ?: 0.0)
        .build()
}

/** Strong sustained deceleration (GPS-speed based — mounting-independent). */
class HardBrakingRule @Inject constructor() : EventRule {
    override val type = EventType.HARD_BRAKING
    override fun evaluate(window: SensorWindow, nowNs: Long): DrivingEvent? {
        val a = window.speedSlope() ?: return null
        if (a > -THRESHOLD) return null
        val confidence = min(1.0, (-a - THRESHOLD) / THRESHOLD + 0.5)
        return window.event(type, nowNs, confidence, """{"decel_mps2":${"%.2f".format(a)}}""")
    }
    private companion object { const val THRESHOLD = 4.0 } // m/s²
}

/** Aggressive throttle (GPS-speed based — mounting-independent). */
class SuddenAccelerationRule @Inject constructor() : EventRule {
    override val type = EventType.SUDDEN_ACCELERATION
    override fun evaluate(window: SensorWindow, nowNs: Long): DrivingEvent? {
        val a = window.speedSlope() ?: return null
        if (a < THRESHOLD) return null
        val confidence = min(1.0, (a - THRESHOLD) / THRESHOLD + 0.5)
        return window.event(type, nowNs, confidence, """{"accel_mps2":${"%.2f".format(a)}}""")
    }
    private companion object { const val THRESHOLD = 3.5 }
}

/** High yaw rate about the vehicle's vertical axis while moving. Needs the gravity-derived up axis. */
class SharpTurnRule @Inject constructor() : EventRule {
    override val type = EventType.SHARP_TURN
    override fun evaluate(window: SensorWindow, nowNs: Long): DrivingEvent? {
        if (!window.gravityReady()) return null
        val yaw = window.peakYawRate()
        val speed = window.latestSpeed()?.speedMps ?: 0.0
        if (yaw < YAW_THRESHOLD || speed < MIN_SPEED) return null
        val confidence = min(1.0, yaw / (YAW_THRESHOLD * 2))
        return window.event(type, nowNs, confidence, """{"yaw_rate":${"%.2f".format(yaw)},"speed":${"%.1f".format(speed)}}""")
    }
    private companion object { const val YAW_THRESHOLD = 0.6; const val MIN_SPEED = 5.0 }
}

/** Sharp, brief vehicle-vertical impact while moving. Needs the gravity-derived up axis. */
class PotholeImpactRule @Inject constructor() : EventRule {
    override val type = EventType.POTHOLE_IMPACT
    override val cooldownMs = 1_500L
    override fun evaluate(window: SensorWindow, nowNs: Long): DrivingEvent? {
        if (!window.gravityReady()) return null                     // need the vehicle vertical axis
        val speed = window.latestSpeed()?.speedMps ?: return null   // need a GPS fix to confirm motion
        if (speed < MIN_SPEED) return null                          // gate out stationary hand-jolts
        val v = window.peakVertical()
        if (v < THRESHOLD) return null
        val confidence = min(1.0, v / (THRESHOLD * 2))
        return window.event(type, nowNs, confidence, """{"vertical_mps2":${"%.2f".format(v)},"speed":${"%.1f".format(speed)}}""")
    }
    private companion object { const val THRESHOLD = 9.0; const val MIN_SPEED = 2.5 }
}

/** Moderate, broader vehicle-vertical disturbance crossed at low-to-moderate speed. */
class SpeedBumpRule @Inject constructor() : EventRule {
    override val type = EventType.SPEED_BUMP
    override val cooldownMs = 2_000L
    override fun evaluate(window: SensorWindow, nowNs: Long): DrivingEvent? {
        if (!window.gravityReady()) return null
        val speed = window.latestSpeed()?.speedMps ?: return null
        val v = window.peakVertical()
        if (v < LOW || v >= HIGH || speed < MIN_SPEED || speed > MAX_SPEED) return null
        return window.event(type, nowNs, 0.6, """{"vertical_mps2":${"%.2f".format(v)},"speed":${"%.1f".format(speed)}}""")
    }
    private companion object { const val LOW = 4.0; const val HIGH = 9.0; const val MIN_SPEED = 2.5; const val MAX_SPEED = 15.0 }
}

/** Lateral oscillation suggesting a quick lane change. Needs full vehicle-frame calibration. */
class RapidLaneChangeRule @Inject constructor() : EventRule {
    override val type = EventType.RAPID_LANE_CHANGE
    override fun evaluate(window: SensorWindow, nowNs: Long): DrivingEvent? {
        if (!window.calibrated()) return null                       // lateral axis needs forward too
        val lat = window.peakLateral()
        val yaw = window.peakYawRate()
        val speed = window.latestSpeed()?.speedMps ?: 0.0
        if (lat < LAT_THRESHOLD || yaw > MAX_YAW || speed < MIN_SPEED) return null
        return window.event(type, nowNs, min(1.0, lat / (LAT_THRESHOLD * 2)),
            """{"lateral_mps2":${"%.2f".format(lat)},"yaw":${"%.2f".format(yaw)}}""")
    }
    private companion object { const val LAT_THRESHOLD = 3.0; const val MAX_YAW = 0.6; const val MIN_SPEED = 8.0 }
}
