package com.posecam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/** Static facts about the phone for device.json: hardware, camera characteristics, sensors. */
object DeviceInfo {

    fun collect(context: Context, cameraId: String, sensors: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "manufacturer" to Build.MANUFACTURER,
        "brand" to Build.BRAND,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "hardware" to Build.HARDWARE,
        "android_release" to Build.VERSION.RELEASE,
        "android_sdk" to Build.VERSION.SDK_INT,
        "build_fingerprint" to Build.FINGERPRINT,
        "camera" to camera(context, cameraId),
        "imu" to sensors,
    )

    private fun camera(context: Context, cameraId: String): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("camera2_id" to cameraId)
        val c = try {
            (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            // ARCore may use a logical id Camera2 does not expose; the rest is best-effort.
            Log.w("PoseCam", "No Camera2 characteristics for $cameraId", e)
            out["error"] = e.toString()
            return out
        }

        // REALTIME means frame timestamps share SystemClock.elapsedRealtimeNanos with IMU
        // events. UNKNOWN means they may not be directly comparable: check before fusing.
        out["timestamp_source"] = when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
            else -> null
        }
        out["optical_stabilization_modes"] = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        out["video_stabilization_modes"] = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        out["focal_lengths_mm"] = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        // Diopters. Calibration tells whether they are metric (CALIBRATED/APPROXIMATE) or arbitrary.
        out["minimum_focus_distance_diopters"] = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        out["hyperfocal_distance_diopters"] = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
        out["focus_distance_calibration"] = when (c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
            else -> null
        }
        c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { out["sensor_physical_size_mm"] = floatArrayOf(it.width, it.height) }
        c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { out["sensor_pixel_array_size"] = intArrayOf(it.width, it.height) }
        out["sensor_orientation_deg"] = c.get(CameraCharacteristics.SENSOR_ORIENTATION)
        // Factory calibration, when the manufacturer publishes it. Intrinsics here are for the
        // full sensor array, not ARCore's CPU image; use intrinsics.json for projection.
        out["lens_intrinsic_calibration"] = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        out["lens_pose_rotation"] = c.get(CameraCharacteristics.LENS_POSE_ROTATION)
        out["lens_pose_translation"] = c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
        if (Build.VERSION.SDK_INT >= 28) {
            out["lens_distortion"] = c.get(CameraCharacteristics.LENS_DISTORTION)
            out["lens_pose_reference"] = when (c.get(CameraCharacteristics.LENS_POSE_REFERENCE)) {
                CameraCharacteristics.LENS_POSE_REFERENCE_PRIMARY_CAMERA -> "PRIMARY_CAMERA"
                CameraCharacteristics.LENS_POSE_REFERENCE_GYROSCOPE -> "GYROSCOPE"
                CameraCharacteristics.LENS_POSE_REFERENCE_UNDEFINED -> "UNDEFINED"
                else -> null
            }
        }
        return out
    }
}
