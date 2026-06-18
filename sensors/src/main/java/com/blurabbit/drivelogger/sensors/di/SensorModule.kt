package com.blurabbit.drivelogger.sensors.di

import com.blurabbit.drivelogger.sensors.SensorSource
import com.blurabbit.drivelogger.sensors.impl.DeviceTelemetrySource
import com.blurabbit.drivelogger.sensors.impl.EnvironmentSensorSource
import com.blurabbit.drivelogger.sensors.impl.GnssSensorSource
import com.blurabbit.drivelogger.sensors.impl.ImuSensorSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * The recorder injects `Set<SensorSource>` and records every available one. Adding a new sensor
 * is a single `@Binds @IntoSet` line here — no other module changes.
 *
 * (ObdSensorSource is intentionally NOT bound yet — it's a future-hardware example; bind it here
 * once a real transport exists.)
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SensorModule {
    @Binds @IntoSet abstract fun imu(source: ImuSensorSource): SensorSource
    @Binds @IntoSet abstract fun gnss(source: GnssSensorSource): SensorSource
    @Binds @IntoSet abstract fun environment(source: EnvironmentSensorSource): SensorSource
    @Binds @IntoSet abstract fun telemetry(source: DeviceTelemetrySource): SensorSource
}
