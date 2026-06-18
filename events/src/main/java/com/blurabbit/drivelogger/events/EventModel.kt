package com.blurabbit.drivelogger.events

import com.blurabbit.drivelogger.proto.DrivingEvent

/** Accelerometer sample (m/s²) on the unified clock. Prefer linear acceleration (gravity removed). */
data class AccelSample(val unifiedNs: Long, val x: Double, val y: Double, val z: Double)

/** Gyroscope sample (rad/s) on the unified clock. */
data class GyroSample(val unifiedNs: Long, val x: Double, val y: Double, val z: Double)

/** Speed / position sample derived from GNSS. */
data class SpeedSample(val unifiedNs: Long, val speedMps: Double, val lat: Double, val lon: Double)

/**
 * Bounded sliding window of recent inertial + speed samples shared by all [EventRule]s.
 * Lock-light: appends happen on the single detector coroutine; rules read synchronously.
 */
class SensorWindow(private val horizonNs: Long = 4_000_000_000L) {
    private val accel = ArrayDeque<AccelSample>()
    private val gyro = ArrayDeque<GyroSample>()
    private val speed = ArrayDeque<SpeedSample>()

    fun add(s: AccelSample) { accel.addLast(s); trim(accel, s.unifiedNs) { it.unifiedNs } }
    fun add(s: GyroSample) { gyro.addLast(s); trim(gyro, s.unifiedNs) { it.unifiedNs } }
    fun add(s: SpeedSample) { speed.addLast(s); trim(speed, s.unifiedNs) { it.unifiedNs } }

    fun accels(): List<AccelSample> = accel.toList()
    fun gyros(): List<GyroSample> = gyro.toList()
    fun speeds(): List<SpeedSample> = speed.toList()
    fun latestSpeed(): SpeedSample? = speed.lastOrNull()

    /** Longitudinal acceleration (dv/dt, m/s²) from the two most recent speed samples. */
    fun speedDerivative(): Double? {
        if (speed.size < 2) return null
        val a = speed.elementAt(speed.size - 2)
        val b = speed.last()
        val dt = (b.unifiedNs - a.unifiedNs) / 1e9
        if (dt <= 0.05) return null
        return (b.speedMps - a.speedMps) / dt
    }

    /** Peak yaw rate (rad/s) over the window — turn sharpness. */
    fun peakYawRate(): Double = gyro.maxOfOrNull { kotlin.math.abs(it.z) } ?: 0.0

    /** Peak vertical acceleration deviation (m/s²) — pothole / speed-bump impacts. */
    fun peakVertical(): Double = accel.maxOfOrNull { kotlin.math.abs(it.z) } ?: 0.0

    /** Peak lateral acceleration (m/s²). */
    fun peakLateral(): Double = accel.maxOfOrNull { kotlin.math.abs(it.y) } ?: 0.0

    private inline fun <T> trim(dq: ArrayDeque<T>, nowNs: Long, ts: (T) -> Long) {
        while (dq.isNotEmpty() && nowNs - ts(dq.first()) > horizonNs) dq.removeFirst()
    }
}

/**
 * A single detection heuristic. Stateless w.r.t. shared data (reads [SensorWindow]); the detector
 * enforces a per-rule cooldown to debounce. Returns a populated [DrivingEvent] builder result, or
 * null. Designed so a future ML/TFLite rule drops in behind the same interface.
 */
interface EventRule {
    val type: DrivingEvent.EventType
    val cooldownMs: Long get() = 3_000

    /** @param nowNs current unified time; evaluate the latest window state. */
    fun evaluate(window: SensorWindow, nowNs: Long): DrivingEvent?
}
