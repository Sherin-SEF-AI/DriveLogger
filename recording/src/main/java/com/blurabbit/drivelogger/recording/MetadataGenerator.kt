package com.blurabbit.drivelogger.recording

import com.blurabbit.drivelogger.core.common.Hashing
import com.blurabbit.drivelogger.domain.model.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Writes the dataset `metadata.json` sidecar summarizing a finished trip — the manifest downstream
 * dataset tooling reads to index a recording without parsing the full MCAP.
 */
class MetadataGenerator @Inject constructor() {

    fun write(
        trip: Trip,
        durationNs: Long,
        mcapSegments: List<File>,
        mp4Segments: List<File>,
        dest: File,
        integrityVerified: Boolean,
    ): String {
        val durationSec = durationNs / 1e9
        val stats = trip.stats
        // SHA-256 each artifact so downstream ingestion can verify nothing was corrupted/truncated.
        fun artifactJson(f: File) = JSONObject()
            .put("name", f.name).put("bytes", f.length()).put("sha256", Hashing.sha256(f))
        val json = JSONObject().apply {
            put("schema_version", 2)
            put("integrity_verified", integrityVerified)
            put("trip_id", trip.id)
            put("vehicle_id", trip.profile.vehicleId ?: JSONObject.NULL)
            put("vehicle_name", trip.profile.vehicleName ?: JSONObject.NULL)
            put("driver_name", trip.profile.driverName ?: JSONObject.NULL)
            put("route_name", trip.profile.routeName ?: JSONObject.NULL)
            put("city", trip.profile.city ?: JSONObject.NULL)
            put("weather", trip.profile.weather ?: JSONObject.NULL)
            put("notes", trip.profile.notes ?: JSONObject.NULL)
            put("start_wall_ms", trip.startWallMs ?: JSONObject.NULL)
            put("end_wall_ms", trip.endWallMs ?: JSONObject.NULL)
            put("duration_seconds", round2(durationSec))
            put("distance_meters", round2(stats.distanceMeters))
            put("average_speed_mps", round2(stats.avgSpeedMps))
            put("max_speed_mps", round2(stats.maxSpeedMps))
            put("gps_samples", stats.gpsSamples)
            put("imu_samples", stats.imuSamples)
            put("frame_count", stats.frameCount)
            put("event_count", stats.eventCount)
            put("segment_count", mcapSegments.size)
            put("artifacts", JSONObject().apply {
                put("mcap", JSONArray().apply { mcapSegments.forEach { put(artifactJson(it)) } })
                put("mp4", JSONArray().apply { mp4Segments.forEach { put(artifactJson(it)) } })
                put("mcap_total_bytes", mcapSegments.sumOf { it.length() })
                put("mp4_total_bytes", mp4Segments.sumOf { it.length() })
            })
        }
        val text = json.toString(2)
        dest.writeText(text)
        return text
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
