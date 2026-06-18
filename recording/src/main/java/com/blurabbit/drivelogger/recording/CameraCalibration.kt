package com.blurabbit.drivelogger.recording

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the road-facing (back) camera's intrinsic calibration + lens distortion from
 * [CameraCharacteristics] and serializes it to JSON. Written once per trip as an MCAP attachment
 * (`calibration.json`) — required for any 3D / SLAM / HD-mapping / undistortion use of the dataset.
 *
 * Intrinsics are expressed in the sensor **active array** pixel coordinates; consumers scale them
 * to the recorded video resolution using `active_array` vs `recording` fields below.
 */
object CameraCalibration {

    fun readBackCameraJson(context: Context, recordWidth: Int = 1920, recordHeight: Int = 1080): String? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val cameraId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        }.getOrNull() ?: return null

        val c = runCatching { cm.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
        val json = JSONObject()
        json.put("camera_id", cameraId)
        json.put("vehicle_position", "front") // back-of-phone == front-of-vehicle (road view)
        json.put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        json.put("recording", JSONObject().put("width", recordWidth).put("height", recordHeight))

        val activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        activeArray?.let { r ->
            json.put("active_array", JSONObject().put("width", r.width()).put("height", r.height()))
        }
        c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { s ->
            json.put("pixel_array", JSONObject().put("width", s.width).put("height", s.height))
        }
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        physical?.let { s ->
            json.put("physical_size_mm", JSONObject().put("width", s.width).put("height", s.height))
        }
        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        focals?.let {
            json.put("focal_lengths_mm", JSONArray().apply { it.forEach { f -> put(f.toDouble()) } })
        }

        // Intrinsics: [fx, fy, cx, cy, skew] in active-array pixels (API 23+, when the device reports it).
        val intrinsics = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (intrinsics != null && intrinsics.size >= 5) {
            json.put("intrinsics", JSONObject()
                .put("fx", intrinsics[0].toDouble()).put("fy", intrinsics[1].toDouble())
                .put("cx", intrinsics[2].toDouble()).put("cy", intrinsics[3].toDouble())
                .put("skew", intrinsics[4].toDouble())
                .put("model", "pinhole")
                .put("reference", "active_array"))
        } else if (activeArray != null && physical != null && focals != null && focals.isNotEmpty()) {
            // Fallback: derive a pinhole model from sensor geometry (focal & physical size → fx,fy).
            val f = focals[0]
            val fx = f / physical.width * activeArray.width()
            val fy = f / physical.height * activeArray.height()
            json.put("intrinsics", JSONObject()
                .put("fx", fx.toDouble()).put("fy", fy.toDouble())
                .put("cx", activeArray.width() / 2.0).put("cy", activeArray.height() / 2.0)
                .put("skew", 0.0)
                .put("model", "pinhole_derived")
                .put("reference", "active_array")
                .put("note", "derived from focal_length & physical_size; device did not expose LENS_INTRINSIC_CALIBRATION"))
        }

        // Distortion: prefer the modern 5-coeff model (API 28+), else legacy radial (API 23+).
        val distortion = if (Build.VERSION.SDK_INT >= 28) {
            c.get(CameraCharacteristics.LENS_DISTORTION)
        } else null
        if (distortion != null && distortion.size >= 5) {
            json.put("distortion", JSONObject()
                .put("model", "brown_conrady")
                .put("k1", distortion[0].toDouble()).put("k2", distortion[1].toDouble())
                .put("k3", distortion[2].toDouble())
                .put("p1", distortion[3].toDouble()).put("p2", distortion[4].toDouble()))
        } else {
            @Suppress("DEPRECATION")
            c.get(CameraCharacteristics.LENS_RADIAL_DISTORTION)?.let { rd ->
                if (rd.size >= 6) json.put("distortion", JSONObject()
                    .put("model", "legacy_radial")
                    .put("kappa", JSONArray().apply { rd.forEach { put(it.toDouble()) } }))
            }
        }

        return json.toString(2)
    }
}
