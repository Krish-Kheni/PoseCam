package com.posecam

import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Session
import java.util.EnumSet
import kotlin.math.abs

/**
 * Picks the camera config explicitly instead of taking the device default, so recordings
 * from different phones are comparable and the choice is written into every session.
 */
object CameraConfigs {
    /** Target CPU image size. Phase 2 encodes these frames, so keep them moderate. */
    const val TARGET_WIDTH = 640
    const val TARGET_HEIGHT = 480

    fun select(session: Session): CameraConfig {
        val back = CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK)
        val preferred = session.getSupportedCameraConfigs(
            CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
                .setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE))
        )
        val candidates = preferred.ifEmpty { session.getSupportedCameraConfigs(back) }
        check(candidates.isNotEmpty()) { "No back-camera configs available" }
        val best = closestIndex(candidates.map { it.imageSize.width to it.imageSize.height })
        return candidates[best]
    }

    /** Index of the size whose pixel count is closest to the target; ties go to the first. */
    fun closestIndex(sizes: List<Pair<Int, Int>>, width: Int = TARGET_WIDTH, height: Int = TARGET_HEIGHT): Int {
        require(sizes.isNotEmpty())
        val target = width.toLong() * height
        return sizes.indices.minBy { abs(sizes[it].first.toLong() * sizes[it].second - target) }
    }

    fun describe(config: CameraConfig): Map<String, Any?> = linkedMapOf(
        "camera_id" to config.cameraId,
        "cpu_image_size" to intArrayOf(config.imageSize.width, config.imageSize.height),
        "gpu_texture_size" to intArrayOf(config.textureSize.width, config.textureSize.height),
        "fps_range" to intArrayOf(config.fpsRange.lower, config.fpsRange.upper),
        "depth_sensor_usage" to config.depthSensorUsage.name,
    )
}
