package com.blurabbit.drivelogger.domain.model

/** Lifecycle state of a trip. Mirrors the `status` column in the Room `trips` table. */
enum class TripStatus { CREATED, RECORDING, PAUSED, STOPPED, EXPORTED, FAILED }

/** User-supplied descriptive metadata captured when a trip is created. */
data class TripProfile(
    val name: String? = null,
    val vehicleId: String? = null,
    val vehicleName: String? = null,
    val driverName: String? = null,
    val routeName: String? = null,
    val weather: String? = null,
    val city: String? = null,
    val notes: String? = null,
)

/** Aggregated statistics maintained during recording and finalized on stop. */
data class TripStats(
    val distanceMeters: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val avgSpeedMps: Double = 0.0,
    val gpsSamples: Long = 0,
    val imuSamples: Long = 0,
    val frameCount: Long = 0,
    val eventCount: Long = 0,
)

data class Trip(
    val id: String,
    val profile: TripProfile,
    val status: TripStatus,
    val startWallMs: Long?,
    val endWallMs: Long?,
    val startElapsedNs: Long?,
    val stats: TripStats,
)
