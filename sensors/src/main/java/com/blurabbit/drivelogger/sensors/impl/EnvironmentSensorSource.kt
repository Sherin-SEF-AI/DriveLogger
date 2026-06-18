package com.blurabbit.drivelogger.sensors.impl

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.blurabbit.drivelogger.core.clock.ClockSynchronizer
import com.blurabbit.drivelogger.core.common.Topics
import com.blurabbit.drivelogger.proto.Environment
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
 * Environmental sensors (barometer, ambient light, ambient temperature, relative humidity).
 * Absent sensors are simply not registered; their fields stay NaN in the published message.
 */
class EnvironmentSensorSource @Inject constructor(
    @ApplicationContext context: Context,
    private val sync: ClockSynchronizer,
) : SensorSource {

    override val id: String = "environment"
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val tracker = RateTracker(expectedHz = 1.0)
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    @Volatile private var pressure = Double.NaN
    @Volatile private var light = Double.NaN
    @Volatile private var temperature = Double.NaN
    @Volatile private var humidity = Double.NaN

    override val topics = listOf(TopicDescriptor(Topics.ENVIRONMENT, Environment.getDescriptor()))

    override fun isAvailable(): Boolean =
        listOf(Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT, Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY)
            .any { sensorManager.getDefaultSensor(it) != null }

    override fun start(scope: CoroutineScope): Flow<SensorRecord> = callbackFlow {
        val ht = HandlerThread("environment").apply { start() }
        thread = ht
        val handler = Handler(ht.looper)
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_PRESSURE -> pressure = event.values[0].toDouble()
                    Sensor.TYPE_LIGHT -> light = event.values[0].toDouble()
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> temperature = event.values[0].toDouble()
                    Sensor.TYPE_RELATIVE_HUMIDITY -> humidity = event.values[0].toDouble()
                    else -> return
                }
                val unified = sync.observeAndConvert("environment", event.timestamp)
                tracker.onSample(unified)
                trySend(SensorRecord(Topics.ENVIRONMENT, unified, Environment.newBuilder()
                    .setUnifiedNs(unified)
                    .setPressureHpa(pressure).setAmbientLightLux(light)
                    .setTemperatureC(temperature).setRelativeHumidity(humidity)
                    .build()))
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }
        listener = l
        listOf(Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT, Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY)
            .forEach { type ->
                sensorManager.getDefaultSensor(type)?.let {
                    sensorManager.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
                }
            }
        awaitClose { stopInternal() }
    }

    override fun stop() = stopInternal()
    private fun stopInternal() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null; thread?.quitSafely(); thread = null
    }

    override fun health(): SensorHealthSnapshot = tracker.snapshot(id, driftMs = 0.0)
}
