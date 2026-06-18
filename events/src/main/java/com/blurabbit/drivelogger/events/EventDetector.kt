package com.blurabbit.drivelogger.events

import com.blurabbit.drivelogger.proto.DrivingEvent
import com.blurabbit.drivelogger.proto.DrivingEvent.EventType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

/**
 * Real-time driving-event detector. Fed typed samples by the recording orchestrator (decoupled
 * from :sensors), it maintains a [SensorWindow], runs every injected [EventRule] with per-type
 * cooldown debouncing, and derives a composite AGGRESSIVE_DRIVING event from event density.
 *
 * All `onX` calls must come from a single coroutine (the recorder's consumer) — no locks needed.
 */
class EventDetector @Inject constructor(
    private val rules: Set<@JvmSuppressWildcards EventRule>,
) {
    private val window = SensorWindow()
    private val lastFired = HashMap<EventType, Long>()
    private val recentEventTimes = ArrayDeque<Long>()

    private val _events = MutableSharedFlow<DrivingEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DrivingEvent> = _events.asSharedFlow()

    fun onAccel(s: AccelSample) { window.add(s); evaluate(s.unifiedNs) }
    fun onGyro(s: GyroSample) { window.add(s) }
    fun onSpeed(s: SpeedSample) { window.add(s); evaluate(s.unifiedNs) }

    private fun evaluate(nowNs: Long) {
        for (rule in rules) {
            val last = lastFired[rule.type] ?: 0L
            if ((nowNs - last) / 1_000_000 < rule.cooldownMs) continue
            val ev = rule.evaluate(window, nowNs) ?: continue
            lastFired[rule.type] = nowNs
            emit(ev)
            registerForAggression(nowNs)
        }
    }

    private fun registerForAggression(nowNs: Long) {
        recentEventTimes.addLast(nowNs)
        while (recentEventTimes.isNotEmpty() && nowNs - recentEventTimes.first() > AGGRESSION_WINDOW_NS) {
            recentEventTimes.removeFirst()
        }
        val last = lastFired[EventType.AGGRESSIVE_DRIVING] ?: 0L
        if (recentEventTimes.size >= AGGRESSION_COUNT &&
            (nowNs - last) / 1_000_000 >= AGGRESSION_COOLDOWN_MS
        ) {
            lastFired[EventType.AGGRESSIVE_DRIVING] = nowNs
            val s = window.latestSpeed()
            emit(
                DrivingEvent.newBuilder()
                    .setUnifiedNs(nowNs).setType(EventType.AGGRESSIVE_DRIVING)
                    .setConfidence(min(1.0, recentEventTimes.size / (AGGRESSION_COUNT * 2.0)))
                    .setEvidenceJson("""{"events_in_window":${recentEventTimes.size}}""")
                    .setLatitude(s?.lat ?: 0.0).setLongitude(s?.lon ?: 0.0).setSpeedMps(s?.speedMps ?: 0.0)
                    .build(),
            )
        }
    }

    private fun emit(ev: DrivingEvent) { _events.tryEmit(ev) }

    private fun min(a: Double, b: Double) = if (a < b) a else b

    private companion object {
        const val AGGRESSION_WINDOW_NS = 30_000_000_000L // 30 s
        const val AGGRESSION_COUNT = 3
        const val AGGRESSION_COOLDOWN_MS = 15_000L
    }
}
