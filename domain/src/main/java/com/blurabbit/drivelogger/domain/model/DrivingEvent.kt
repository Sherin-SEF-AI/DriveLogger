package com.blurabbit.drivelogger.domain.model

enum class DrivingEventType {
    HARD_BRAKING, SUDDEN_ACCELERATION, SHARP_TURN, POTHOLE_IMPACT,
    SPEED_BUMP, RAPID_LANE_CHANGE, AGGRESSIVE_DRIVING,
    SIREN, VEHICLE_HORN,
}

data class DrivingEvent(
    val id: Long = 0,
    val tripId: String,
    val type: DrivingEventType,
    val confidence: Double,
    val unifiedTsNs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedMps: Double? = null,
    val evidenceJson: String = "{}",
)
