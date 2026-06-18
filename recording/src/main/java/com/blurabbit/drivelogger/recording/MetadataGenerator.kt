package com.blurabbit.drivelogger.recording

import com.blurabbit.drivelogger.domain.model.Trip
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * Writes the dataset `metadata.json` sidecar summarizing a finished trip — the manifest downstream
 * dataset tooling reads to index a recording without parsing the full MCAP.
 */
class MetadataGenerator @Inject constructor() {

    fun write(trip: Trip, durationNs: Long, mcap: File, mp4: File?, dest: File): String {
        val durationSec = durationNs / 1e9
        val stats = trip.stats
        val json = JSONObject().apply {
            put("schema_version", 1)
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
            put("artifacts", JSONObject().apply {
                put("mcap", mcap.name)
                put("mcap_bytes", mcap.length())
                put("mp4", mp4?.name ?: JSONObject.NULL)
                put("mp4_bytes", mp4?.length() ?: 0)
            })
        }
        val text = json.toString(2)
        dest.writeText(text)
        return text
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
