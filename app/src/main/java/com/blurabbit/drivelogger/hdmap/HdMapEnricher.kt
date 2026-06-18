package com.blurabbit.drivelogger.hdmap

import com.blurabbit.drivelogger.recording.TripStorage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** A road element from OSM along the trip's track. */
data class RoadSegment(
    val id: Long,
    val highway: String,
    val lanes: Int?,
    val maxspeedKph: Int?,
    val oneway: Boolean,
    val name: String?,
)

/**
 * Offline HD-map enrichment: reads the trip's downsampled GPS polyline (`track.json`), queries the
 * OSM Overpass API for `highway` ways inside its bounding box, and writes the road class / lanes /
 * speed-limit / oneway tags to `hdmap.json`. Best-effort context (not ground truth); OSM data is
 * ODbL-licensed. No extra hardware — pure network + parse.
 */
class HdMapEnricher @Inject constructor(
    private val storage: TripStorage,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** @return true if hdmap.json was written; false on no track / network / parse failure. */
    fun enrich(tripId: String): Boolean {
        val track = storage.trackFile(tripId).takeIf { it.exists() }
            ?: run { android.util.Log.w(TAG, "no track.json for $tripId"); return false }
        val points = parseTrack(track.readText())
        val bbox = trackBbox(points)
            ?: run { android.util.Log.w(TAG, "empty track (${points.size} pts)"); return false }
        val query = overpassQuery(bbox)

        val req = Request.Builder()
            .url(OVERPASS_URL)
            // Overpass/OSM policy requires a descriptive User-Agent; generic ones get a 406.
            .header("User-Agent", USER_AGENT)
            .post(("data=" + URLEncoder.encode(query, "UTF-8")).toRequestBody(FORM))
            .build()

        val segments = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { android.util.Log.w(TAG, "overpass HTTP ${resp.code}"); return false }
                parseOverpass(resp.body?.string() ?: return false)
            }
        }.getOrElse { android.util.Log.w(TAG, "overpass call failed", it); return false }

        storage.hdMapFile(tripId).writeText(hdMapJson(bbox, segments))
        android.util.Log.i(TAG, "wrote hdmap.json: ${segments.size} segments for $tripId")
        return true
    }

    private companion object {
        const val TAG = "HdMapEnricher"
        const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        const val USER_AGENT = "BlurabbitDriveLogger/0.1 (https://blurabbit.com)"
        val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}

/** Parses `[[lat,lon],...]` (track.json) into points; tolerant of empties. */
internal fun parseTrack(json: String): List<DoubleArray> = runCatching {
    val arr = org.json.JSONArray(json)
    buildList {
        for (i in 0 until arr.length()) {
            val p = arr.getJSONArray(i)
            add(doubleArrayOf(p.getDouble(0), p.getDouble(1)))
        }
    }
}.getOrDefault(emptyList())

/** [minLat, minLon, maxLat, maxLon] padded slightly, or null if no points. Pure → unit-tested. */
internal fun trackBbox(points: List<DoubleArray>, padDeg: Double = 0.002): DoubleArray? {
    if (points.isEmpty()) return null
    var minLat = Double.MAX_VALUE; var minLon = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
    for (p in points) {
        if (p[0] < minLat) minLat = p[0]; if (p[0] > maxLat) maxLat = p[0]
        if (p[1] < minLon) minLon = p[1]; if (p[1] > maxLon) maxLon = p[1]
    }
    return doubleArrayOf(minLat - padDeg, minLon - padDeg, maxLat + padDeg, maxLon + padDeg)
}

/** Overpass QL: all highway ways within the bbox, tags only. */
internal fun overpassQuery(bbox: DoubleArray): String =
    "[out:json][timeout:25];way[\"highway\"](${bbox[0]},${bbox[1]},${bbox[2]},${bbox[3]});out tags;"

/** Parses an Overpass JSON response into road segments (way elements with a `highway` tag). */
internal fun parseOverpass(json: String): List<RoadSegment> {
    val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
    val out = ArrayList<RoadSegment>(elements.length())
    for (i in 0 until elements.length()) {
        val el = elements.getJSONObject(i)
        if (el.optString("type") != "way") continue
        val tags = el.optJSONObject("tags") ?: continue
        if (!tags.has("highway")) continue
        val map = HashMap<String, String>()
        tags.keys().forEach { k -> map[k] = tags.optString(k) }
        out += roadSegmentFromTags(el.optLong("id"), map)
    }
    return out
}

/** Pure tag → [RoadSegment] mapping (lanes/maxspeed/oneway normalization). Unit-tested. */
internal fun roadSegmentFromTags(id: Long, tags: Map<String, String>): RoadSegment {
    val lanes = tags["lanes"]?.trim()?.toIntOrNull()
    val maxspeed = tags["maxspeed"]?.let { raw ->
        val t = raw.trim().lowercase()
        val num = t.takeWhile { it.isDigit() }.toIntOrNull()
        when {
            num == null -> null
            "mph" in t -> Math.round(num * 1.60934).toInt()
            else -> num
        }
    }
    val oneway = tags["oneway"].let { it == "yes" || it == "true" || it == "1" }
    return RoadSegment(
        id = id,
        highway = tags["highway"] ?: "",
        lanes = lanes,
        maxspeedKph = maxspeed,
        oneway = oneway,
        name = tags["name"],
    )
}

/** Serializes the enrichment result to hdmap.json (manual JSON; no Android deps needed). */
internal fun hdMapJson(bbox: DoubleArray, segments: List<RoadSegment>): String = buildString {
    append("{\"schema_version\":1,")
    append("\"bbox\":[${bbox[0]},${bbox[1]},${bbox[2]},${bbox[3]}],")
    append("\"source\":\"osm-overpass\",")
    append("\"segment_count\":${segments.size},")
    append("\"segments\":[")
    segments.forEachIndexed { i, s ->
        if (i > 0) append(",")
        append("{\"id\":${s.id},")
        append("\"highway\":${jsonStr(s.highway)},")
        append("\"lanes\":${s.lanes ?: "null"},")
        append("\"maxspeed_kph\":${s.maxspeedKph ?: "null"},")
        append("\"oneway\":${s.oneway},")
        append("\"name\":${if (s.name == null) "null" else jsonStr(s.name)}}")
    }
    append("]}")
}

private fun jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
