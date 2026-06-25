package com.blurabbit.drivelogger.domain.model

enum class VideoQuality { UHD, FHD, HD, SD }

/**
 * User-configurable recording profile + privacy options (persisted via DataStore). Read at the
 * start of each trip so changes take effect on the next recording without an app restart.
 */
data class AppSettings(
    // Recording profile
    val videoQuality: VideoQuality = VideoQuality.FHD,
    val segmentSeconds: Long = 60,
    val segmentMb: Int = 1024,            // 1 GiB
    val freeSpaceFloorMb: Int = 2048,     // 2 GiB
    val keepLastNTrips: Int = 2,
    val compressionEnabled: Boolean = true,
    val autoUpload: Boolean = true,
    val embedVideo: Boolean = false,
    // Privacy
    val consentGiven: Boolean = false,
    val anonymizePii: Boolean = false,
)
