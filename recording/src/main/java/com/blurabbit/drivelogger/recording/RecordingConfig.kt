package com.blurabbit.drivelogger.recording

/**
 * Tunables for unattended, fleet-scale recording. Tier 1 uses the defaults below; Tier 3 will back
 * these with DataStore + a Settings screen. Kept in :recording so the recorder, storage, camera,
 * and watchdog all share one source of truth.
 */
data class RecordingConfig(
    /** Roll a new segment after this many seconds… */
    val segmentSeconds: Long = 60,
    /** …or this many bytes, whichever comes first. */
    val segmentBytes: Long = 1L * 1024 * 1024 * 1024, // 1 GiB
    /** Stop recording gracefully if free space drops below this floor. */
    val freeSpaceFloorBytes: Long = 2L * 1024 * 1024 * 1024, // 2 GiB
    /** Stop recording gracefully below this battery level (ignored while charging). */
    val minBatteryPct: Double = 5.0,
    /** Keep this many most-recent trips locally even after they're uploaded (safety buffer). */
    val keepLastNTrips: Int = 2,
    /** Auto-enqueue uploads on segment close + trip stop when cloud creds are configured. */
    val autoUpload: Boolean = true,
    /** Also embed foxglove.CompressedVideo into the MCAP (Tier 2; opt-in, larger files). */
    val embedVideo: Boolean = false,
)
