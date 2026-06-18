package com.blurabbit.drivelogger.events.di

import com.blurabbit.drivelogger.events.EventRule
import com.blurabbit.drivelogger.events.rules.HardBrakingRule
import com.blurabbit.drivelogger.events.rules.PotholeImpactRule
import com.blurabbit.drivelogger.events.rules.RapidLaneChangeRule
import com.blurabbit.drivelogger.events.rules.SharpTurnRule
import com.blurabbit.drivelogger.events.rules.SpeedBumpRule
import com.blurabbit.drivelogger.events.rules.SuddenAccelerationRule
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Detection rules are bound `@IntoSet`. A future ML rule (TFLite/LiteRT) is added with one line
 * and the detector picks it up — AGGRESSIVE_DRIVING is derived in the detector from event density.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EventModule {
    @Binds @IntoSet abstract fun hardBraking(r: HardBrakingRule): EventRule
    @Binds @IntoSet abstract fun suddenAccel(r: SuddenAccelerationRule): EventRule
    @Binds @IntoSet abstract fun sharpTurn(r: SharpTurnRule): EventRule
    @Binds @IntoSet abstract fun pothole(r: PotholeImpactRule): EventRule
    @Binds @IntoSet abstract fun speedBump(r: SpeedBumpRule): EventRule
    @Binds @IntoSet abstract fun laneChange(r: RapidLaneChangeRule): EventRule
}
