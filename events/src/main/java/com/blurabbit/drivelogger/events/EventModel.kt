package com.blurabbit.drivelogger.events

import kotlin.math.abs
import kotlin.math.sqrt

/** Linear-acceleration sample (m/s², gravity removed) in the **device** frame, on the unified clock. */
data class AccelSample(val unifiedNs: Long, val x: Double, val y: Double, val z: Double)

/** Gyroscope sample (rad/s) in the **device** frame, on the unified clock. */
data class GyroSample(val unifiedNs: Long, val x: Double, val y: Double, val z: Double)

/** Gravity vector (m/s²) in the **device** frame — used to recover the vehicle's vertical axis. */
data class GravitySample(val unifiedNs: Long, val x: Double, val y: Double, val z: Double)

/** Speed / position sample derived from GNSS. */
data class SpeedSample(val unifiedNs: Long, val speedMps: Double, val lat: Double, val lon: Double)

/**
 * Estimates the rotation from the phone's (device) frame into the **vehicle** frame, so events are
 * detected on real fore/aft, lateral and vertical motion regardless of how the phone is mounted.
 *
 *  - **up** (vehicle vertical) = direction of the gravity vector (Android gravity points "up" along
 *    the axis facing the sky). Available within a second of a stable gravity reading.
 *  - **forward** (vehicle longitudinal) = the horizontal direction along which linear acceleration
 *    aligns with GPS speed *change*: when speeding up the body accelerates forward, when braking it
 *    accelerates backward. We accumulate `sign(dv/dt) · horizontal_accel` until the direction is
 *    confident. Needs one accel/brake to lock in.
 *  - **lateral** = up × forward.
 *
 * All updates come from the single detector coroutine — no synchronization needed.
 */
class VehicleFrameEstimator {
    // Vehicle axes expressed in the device frame (default: phone lying flat, screen up).
    var ux = 0.0; var uy = 0.0; var uz = 1.0; private set
    var fx = 0.0; var fy = 1.0; var fz = 0.0; private set
    var lx = 1.0; var ly = 0.0; var lz = 0.0; private set

    var gravityReady = false; private set
    var calibrated = false; private set

    private var gravityInit = false
    private var fAccX = 0.0; private var fAccY = 0.0; private var fAccZ = 0.0
    private var fwdEvidence = 0.0

    fun updateGravity(gx: Double, gy: Double, gz: Double) {
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1e-3) return
        val nx = gx / n; val ny = gy / n; val nz = gz / n
        if (!gravityInit) {
            ux = nx; uy = ny; uz = nz; gravityInit = true
        } else {
            ux = ux * (1 - G_ALPHA) + nx * G_ALPHA
            uy = uy * (1 - G_ALPHA) + ny * G_ALPHA
            uz = uz * (1 - G_ALPHA) + nz * G_ALPHA
            val m = sqrt(ux * ux + uy * uy + uz * uz)
            if (m > 1e-6) { ux /= m; uy /= m; uz /= m }
        }
        // Accept only a physically plausible gravity magnitude (rejects violent transients).
        gravityReady = gravityInit && n in 8.5..11.5
        recomputeLateral()
    }

    /** Feed a linear-accel sample with the current GPS-derived longitudinal acceleration. */
    fun updateMotion(ax: Double, ay: Double, az: Double, speedDeriv: Double) {
        if (!gravityReady || abs(speedDeriv) < FWD_MIN_DERIV) return
        val d = ax * ux + ay * uy + az * uz          // vertical component
        val hx = ax - d * ux; val hy = ay - d * uy; val hz = az - d * uz // horizontal accel
        val hm = sqrt(hx * hx + hy * hy + hz * hz)
        if (hm < FWD_MIN_ACCEL) return
        val s = if (speedDeriv >= 0) 1.0 else -1.0   // brake → flip so the sum points forward
        fAccX += s * hx; fAccY += s * hy; fAccZ += s * hz
        fwdEvidence += hm
        if (fwdEvidence >= FWD_READY) {
            val pd = fAccX * ux + fAccY * uy + fAccZ * uz
            val px = fAccX - pd * ux; val py = fAccY - pd * uy; val pz = fAccZ - pd * uz
            val pm = sqrt(px * px + py * py + pz * pz)
            if (pm > 1e-6) {
                fx = px / pm; fy = py / pm; fz = pz / pm
                calibrated = true
                recomputeLateral()
            }
        }
    }

    private fun recomputeLateral() {
        lx = uy * fz - uz * fy; ly = uz * fx - ux * fz; lz = ux * fy - uy * fx
        val m = sqrt(lx * lx + ly * ly + lz * lz)
        if (m > 1e-6) { lx /= m; ly /= m; lz /= m }
    }

    fun vertical(x: Double, y: Double, z: Double): Double = x * ux + y * uy + z * uz
    fun lateral(x: Double, y: Double, z: Double): Double = x * lx + y * ly + z * lz
    fun longitudinal(x: Double, y: Double, z: Double): Double = x * fx + y * fy + z * fz
    fun yawRate(x: Double, y: Double, z: Double): Double = x * ux + y * uy + z * uz

    private companion object {
        const val G_ALPHA = 0.08          // gravity smoothing
        const val FWD_MIN_DERIV = 1.0     // m/s² of GPS accel before we trust the forward cue
        const val FWD_MIN_ACCEL = 0.7     // ignore tiny horizontal accel
        const val FWD_READY = 30.0        // accumulated horizontal-accel evidence to lock forward
    }
}

/**
 * Bounded sliding window of recent inertial + speed samples, projected into the **vehicle** frame
 * via [VehicleFrameEstimator]. Appends happen on the single detector coroutine; rules read
 * synchronously.
 */
class SensorWindow(private val horizonNs: Long = 4_000_000_000L) {
    private val accel = ArrayDeque<AccelSample>()
    private val gyro = ArrayDeque<GyroSample>()
    private val speed = ArrayDeque<SpeedSample>()
    val frame = VehicleFrameEstimator()

    fun add(s: AccelSample) {
        accel.addLast(s); trim(accel, s.unifiedNs) { it.unifiedNs }
        frame.updateMotion(s.x, s.y, s.z, speedSlope() ?: 0.0)
    }
    fun add(s: GyroSample) { gyro.addLast(s); trim(gyro, s.unifiedNs) { it.unifiedNs } }
    fun add(s: GravitySample) { frame.updateGravity(s.x, s.y, s.z) }
    fun add(s: SpeedSample) { speed.addLast(s); trim(speed, s.unifiedNs) { it.unifiedNs } }

    fun latestSpeed(): SpeedSample? = speed.lastOrNull()
    fun gravityReady(): Boolean = frame.gravityReady
    fun calibrated(): Boolean = frame.calibrated

    /** Longitudinal acceleration (dv/dt, m/s²) via least-squares over the speed window — noise-robust. */
    fun speedSlope(): Double? {
        if (speed.size < 2) return null
        val t0 = speed.first().unifiedNs
        var n = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (s in speed) {
            val x = (s.unifiedNs - t0) / 1e9; val y = s.speedMps
            n++; sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-9) return null
        return (n * sxy - sx * sy) / denom
    }

    /** Peak yaw rate (rad/s) about the **vehicle** vertical axis — true turn sharpness. */
    fun peakYawRate(): Double = gyro.maxOfOrNull { abs(frame.yawRate(it.x, it.y, it.z)) } ?: 0.0

    /** Peak **vehicle-vertical** acceleration (m/s²) — pothole / speed-bump impacts. */
    fun peakVertical(): Double = accel.maxOfOrNull { abs(frame.vertical(it.x, it.y, it.z)) } ?: 0.0

    /** Peak **vehicle-lateral** acceleration (m/s²). */
    fun peakLateral(): Double = accel.maxOfOrNull { abs(frame.lateral(it.x, it.y, it.z)) } ?: 0.0

    private inline fun <T> trim(dq: ArrayDeque<T>, nowNs: Long, ts: (T) -> Long) {
        while (dq.isNotEmpty() && nowNs - ts(dq.first()) > horizonNs) dq.removeFirst()
    }
}

/**
 * A single detection heuristic. Stateless w.r.t. shared data (reads [SensorWindow]); the detector
 * enforces a per-rule cooldown to debounce. Returns a populated [com.blurabbit.drivelogger.proto.DrivingEvent],
 * or null. Designed so a future ML/TFLite rule drops in behind the same interface.
 */
interface EventRule {
    val type: com.blurabbit.drivelogger.proto.DrivingEvent.EventType
    val cooldownMs: Long get() = 3_000

    /** @param nowNs current unified time; evaluate the latest window state. */
    fun evaluate(window: SensorWindow, nowNs: Long): com.blurabbit.drivelogger.proto.DrivingEvent?
}
