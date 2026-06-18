package com.blurabbit.drivelogger.events

import com.blurabbit.drivelogger.events.rules.HardBrakingRule
import com.blurabbit.drivelogger.events.rules.SharpTurnRule
import com.blurabbit.drivelogger.proto.DrivingEvent.EventType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EventRuleTest {

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

    @Test
    fun `sharp turn requires both yaw and speed`() {
        val w = SensorWindow()
        w.add(SpeedSample(0, speedMps = 10.0, lat = 0.0, lon = 0.0))
        w.add(GyroSample(0, x = 0.0, y = 0.0, z = 1.0)) // 1 rad/s yaw
        assertThat(SharpTurnRule().evaluate(w, 0)).isNotNull()

        val slow = SensorWindow()
        slow.add(SpeedSample(0, speedMps = 1.0, lat = 0.0, lon = 0.0))
        slow.add(GyroSample(0, 0.0, 0.0, 1.0))
        assertThat(SharpTurnRule().evaluate(slow, 0)).isNull()
    }
}
