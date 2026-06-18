package com.blurabbit.drivelogger.events

import com.blurabbit.drivelogger.events.rules.HardBrakingRule
import com.blurabbit.drivelogger.events.rules.PotholeImpactRule
import com.blurabbit.drivelogger.events.rules.RapidLaneChangeRule
import com.blurabbit.drivelogger.events.rules.SharpTurnRule
import com.blurabbit.drivelogger.proto.DrivingEvent.EventType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class EventRuleTest {

    // ---- GPS-speed events (mounting-independent) -----------------------------------------

    @Test
    fun `hard braking fires on strong deceleration`() {
        val w = SensorWindow()
        w.add(SpeedSample(0, speedMps = 20.0, lat = 1.0, lon = 2.0))
        w.add(SpeedSample(500_000_000, speedMps = 17.0, lat = 1.0, lon = 2.0)) // -6 m/s²
        val ev = HardBrakingRule().evaluate(w, nowNs = 500_000_000)
        assertThat(ev).isNotNull()
        assertThat(ev!!.type).isEqualTo(EventType.HARD_BRAKING)
        assertThat(ev.confidence).isGreaterThan(0.0)
    }

    @Test
    fun `hard braking ignores gentle slowing`() {
        val w = SensorWindow()
        w.add(SpeedSample(0, 20.0, 1.0, 2.0))
        w.add(SpeedSample(1_000_000_000, 19.0, 1.0, 2.0)) // -1 m/s²
        assertThat(HardBrakingRule().evaluate(w, 1_000_000_000)).isNull()
    }

    // ---- vehicle-frame estimator ---------------------------------------------------------

    @Test
    fun `estimator recovers vertical axis from gravity`() {
        val e = VehicleFrameEstimator()
        e.updateGravity(0.0, 9.8, 0.0)              // gravity along device +y
        assertThat(e.gravityReady).isTrue()
        assertThat(e.vertical(0.0, 5.0, 0.0)).isWithin(0.01).of(5.0) // device +y reads as vertical
        assertThat(abs(e.vertical(0.0, 0.0, 5.0))).isWithin(0.01).of(0.0)
    }

    @Test
    fun `estimator recovers forward axis from braking`() {
        val e = VehicleFrameEstimator()
        e.updateGravity(0.0, 9.8, 0.0)              // up = device +y
        // Braking: body accelerates backward along device -z while GPS speed drops.
        repeat(20) { e.updateMotion(0.0, 0.0, -4.0, speedDeriv = -4.0) }
        assertThat(e.calibrated).isTrue()
        assertThat(e.longitudinal(0.0, 0.0, 3.0)).isWithin(0.2).of(3.0) // device +z is forward
        assertThat(abs(e.lateral(5.0, 0.0, 0.0))).isWithin(0.2).of(5.0) // device +x is lateral
    }

    // ---- orientation independence: a TILTED mount where device axes != vehicle axes ------
    //   mount: vehicle up = device +y, vehicle forward = device +z, vehicle left = device +x
    //   so a vehicle vector (fwd, left, up) appears in the device frame as (left, up, fwd).
    private fun dev(fwd: Double, left: Double, up: Double) = Triple(left, up, fwd)

    @Test
    fun `pothole detected on a tilted mount where device-z would miss it`() {
        val w = SensorWindow()
        val (gx, gy, gz) = dev(0.0, 0.0, 9.8)
        w.add(GravitySample(0, gx, gy, gz))
        w.add(SpeedSample(0, 10.0, 0.0, 0.0))
        val (ax, ay, az) = dev(0.0, 0.0, 12.0)      // pure vehicle-vertical impact
        w.add(AccelSample(1, ax, ay, az))

        val ev = PotholeImpactRule().evaluate(w, 1)
        assertThat(ev).isNotNull()
        assertThat(ev!!.type).isEqualTo(EventType.POTHOLE_IMPACT)
        assertThat(az).isEqualTo(0.0) // the old abs(accel.z) heuristic would have seen 0 → missed
    }

    @Test
    fun `sharp turn detected on a tilted mount where device-z would miss it`() {
        val w = SensorWindow()
        val (gx, gy, gz) = dev(0.0, 0.0, 9.8)
        w.add(GravitySample(0, gx, gy, gz))
        w.add(SpeedSample(0, 10.0, 0.0, 0.0))
        val (wx, wy, wz) = dev(0.0, 0.0, 0.8)        // 0.8 rad/s yaw about vehicle up
        w.add(GyroSample(0, wx, wy, wz))

        assertThat(SharpTurnRule().evaluate(w, 0)).isNotNull()
        assertThat(wz).isEqualTo(0.0) // old abs(gyro.z) would have seen 0 → missed
    }

    @Test
    fun `sharp turn needs gravity calibration before it can fire`() {
        val w = SensorWindow()
        w.add(SpeedSample(0, 10.0, 0.0, 0.0))
        w.add(GyroSample(0, 0.0, 0.0, 1.0))          // no gravity yet → orientation unknown
        assertThat(SharpTurnRule().evaluate(w, 0)).isNull()
    }

    @Test
    fun `sharp turn ignores low speed`() {
        val w = SensorWindow()
        w.add(GravitySample(0, 0.0, 0.0, 9.8))
        w.add(SpeedSample(0, 1.0, 0.0, 0.0))
        w.add(GyroSample(0, 0.0, 0.0, 1.0))
        assertThat(SharpTurnRule().evaluate(w, 0)).isNull()
    }

    @Test
    fun `lane change detected only after full calibration on a tilted mount`() {
        val w = SensorWindow()
        val (gx, gy, gz) = dev(0.0, 0.0, 9.8)
        w.add(GravitySample(0, gx, gy, gz))
        // Calibrate forward with a braking phase (speed dropping, body accel backward).
        var t = 0L
        for (i in 0 until 12) {
            w.add(SpeedSample(t, 20.0 - i * 0.5, 0.0, 0.0))   // ~ -5 m/s²
            val (ax, ay, az) = dev(-5.0, 0.0, 0.0)
            w.add(AccelSample(t, ax, ay, az))
            t += 100_000_000
        }
        assertThat(w.calibrated()).isTrue()

        // A lateral jolt with low yaw at speed → lane change.
        w.add(SpeedSample(t, 14.0, 0.0, 0.0))
        val (lx, ly, lz) = dev(0.0, 3.5, 0.0)
        w.add(AccelSample(t, lx, ly, lz))
        val ev = RapidLaneChangeRule().evaluate(w, t)
        assertThat(ev).isNotNull()
        assertThat(ev!!.type).isEqualTo(EventType.RAPID_LANE_CHANGE)
    }
}
