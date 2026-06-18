package com.blurabbit.drivelogger.sensors.impl

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.blurabbit.drivelogger.core.clock.ClockSynchronizer
import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.proto.QuaternionStamped
import com.blurabbit.drivelogger.proto.Vector3Stamped
import com.blurabbit.drivelogger.sensors.RateTracker
import com.blurabbit.drivelogger.sensors.SensorHealthSnapshot
import com.blurabbit.drivelogger.sensors.SensorRecord
import com.blurabbit.drivelogger.sensors.SensorSource
import com.blurabbit.drivelogger.sensors.TopicDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

/**
 * Inertial sources: accelerometer, gyroscope, magnetometer, rotation vector, gravity, and linear
 * acceleration. Runs on a dedicated [HandlerThread] and requests hardware batching
 * (`maxReportLatency`) so the sensor FIFO wakes the CPU less often at 100–200 Hz.
 *
 * Every `SensorEvent.timestamp` is mapped onto the unified clock via [ClockSynchronizer] because
 * its native epoch is device-dependent.
 */
class ImuSensorSource @Inject constructor(
    @ApplicationContext context: android.content.Context,
    private val sync: ClockSynchronizer,
) : SensorSource {

    override val id: String = "imu"

    private val sensorManager =
        context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager

    private data class Stream(
        val type: Int,
        val topic: String,
        val sourceId: String,
        val expectedHz: Double,
        val quaternion: Boolean = false,
    )

    private val streams = listOf(
        Stream(Sensor.TYPE_ACCELEROMETER, Topics.IMU_ACCEL, "imu.accel", 200.0),
        Stream(Sensor.TYPE_GYROSCOPE, Topics.IMU_GYRO, "imu.gyro", 200.0),
        Stream(Sensor.TYPE_MAGNETIC_FIELD, Topics.IMU_MAG, "imu.mag", 50.0),
        Stream(Sensor.TYPE_ROTATION_VECTOR, Topics.IMU_ROTATION, "imu.rotation", 100.0, quaternion = true),
        Stream(Sensor.TYPE_GRAVITY, Topics.IMU_GRAVITY, "imu.gravity", 100.0),
        Stream(Sensor.TYPE_LINEAR_ACCELERATION, Topics.IMU_LINEAR_ACCEL, "imu.linear", 100.0),
    )

    private val trackers = streams.associate { it.topic to RateTracker(it.expectedHz) }
    private val topicByType = streams.associate { it.type to it }

    private var handlerThread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    override val topics: List<TopicDescriptor> = listOf(
        TopicDescriptor(Topics.IMU_ACCEL, Vector3Stamped.getDescriptor()),
        TopicDescriptor(Topics.IMU_GYRO, Vector3Stamped.getDescriptor()),
        TopicDescriptor(Topics.IMU_MAG, Vector3Stamped.getDescriptor()),
        TopicDescriptor(Topics.IMU_ROTATION, QuaternionStamped.getDescriptor()),
        TopicDescriptor(Topics.IMU_GRAVITY, Vector3Stamped.getDescriptor()),
        TopicDescriptor(Topics.IMU_LINEAR_ACCEL, Vector3Stamped.getDescriptor()),
    )

    override fun start(scope: CoroutineScope): Flow<SensorRecord> = callbackFlow {
        val thread = HandlerThread("imu-sensors").apply { start() }
        handlerThread = thread
        val handler = Handler(thread.looper)

        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val stream = topicByType[event.sensor.type] ?: return
                val unified = sync.observeAndConvert(stream.sourceId, event.timestamp)
                trackers[stream.topic]?.onSample(unified)
                val record = if (stream.quaternion) {
                    // ROTATION_VECTOR values = [x, y, z, w(optional), heading accuracy(optional)]
                    val v = event.values
                    val w = if (v.size >= 4) v[3] else computeW(v[0], v[1], v[2])
                    SensorRecord(stream.topic, unified, QuaternionStamped.newBuilder()
                        .setUnifiedNs(unified).setW(w.toDouble())
                        .setX(v[0].toDouble()).setY(v[1].toDouble()).setZ(v[2].toDouble())
                        .setHeadingAccuracyRad(if (v.size >= 5) v[4].toDouble() else 0.0)
                        .build())
                } else {
                    SensorRecord(stream.topic, unified, Vector3Stamped.newBuilder()
                        .setUnifiedNs(unified)
                        .setX(event.values[0].toDouble())
                        .setY(event.values.getOrElse(1) { 0f }.toDouble())
                        .setZ(event.values.getOrElse(2) { 0f }.toDouble())
                        .setAccuracy(event.accuracy)
                        .build())
                }
                val sent = trySend(record).isSuccess
                if (!sent) trackers[stream.topic]?.onDropped()
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        listener = l

        streams.forEach { s ->
            val sensor = sensorManager.getDefaultSensor(s.type) ?: return@forEach
            // ~2.5 ms sampling (fastest) with 20 ms batch window to use the hardware FIFO.
            sensorManager.registerListener(l, sensor, 2_500, 20_000, handler)
        }

        awaitClose { stopInternal() }
    }

    override fun stop() = stopInternal()

    private fun stopInternal() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    override fun health(): SensorHealthSnapshot {
        // Report the worst-performing inertial stream as the source-level health.
        val driftMs = (sync.currentOffsetNs("imu.accel") ?: 0L) / 1_000_000.0
        return trackers.values
            .map { it.snapshot(id, driftMs) }
            .minByOrNull { it.actualHz / (it.expectedHz.takeIf { hz -> hz > 0 } ?: 1.0) }
            ?: SensorHealthSnapshot(id, 0.0, 0.0, 0, driftMs, true)
    }

    private fun computeW(x: Float, y: Float, z: Float): Float {
        val t = 1f - (x * x + y * y + z * z)
        return if (t > 0f) kotlin.math.sqrt(t) else 0f
    }
}
