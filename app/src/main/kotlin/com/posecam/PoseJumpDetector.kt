package com.posecam

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

/**
 * Flags discontinuities between consecutive tracked frames. ARCore relocalizes without
 * leaving TRACKING, so poses either side of a jump are in different frames.
 *
 * Nothing is dropped or smoothed: jumps are recorded in manifest.json so recordings can
 * be split offline (see docs/COORDINATES.md).
 */
class PoseJumpDetector(
    private val maxSpeedMPerS: Double = MAX_SPEED_M_PER_S,
    private val maxRateRadPerS: Double = MAX_RATE_RAD_PER_S,
) {
    data class Jump(
        val frameIndex: Long, val timestampNs: Long,
        val translationM: Double, val rotationDeg: Double, val intervalS: Double,
    ) {
        fun toJson(): Map<String, Any?> = linkedMapOf(
            "frame_index" to frameIndex,
            "timestamp_ns" to timestampNs,
            "translation_m" to round(translationM),
            "rotation_deg" to round(rotationDeg),
            "interval_s" to round(intervalS),
        )

        private fun round(v: Double) = Math.round(v * 1000) / 1000.0
    }

    private var lastTimestampNs = 0L
    private var lastTranslation: FloatArray? = null
    private var lastRotation: FloatArray? = null
    private val jumps = mutableListOf<Jump>()

    fun reset() {
        lastTimestampNs = 0
        lastTranslation = null
        lastRotation = null
        jumps.clear()
    }

    /** Returns the jump if this frame is discontinuous from the previous tracked one. */
    fun onTrackedFrame(frameIndex: Long, timestampNs: Long, translation: FloatArray, rotation: FloatArray): Jump? {
        val previousT = lastTranslation
        val previousR = lastRotation
        lastTranslation = translation.copyOf()
        lastRotation = rotation.copyOf()
        val previousNs = lastTimestampNs
        lastTimestampNs = timestampNs
        if (previousT == null || previousR == null || previousNs == 0L) return null

        val dt = (timestampNs - previousNs) / 1e9
        if (dt <= 0) return null
        var sum = 0.0
        for (i in 0..2) {
            val d = (translation[i] - previousT[i]).toDouble()
            sum += d * d
        }
        val distance = Math.sqrt(sum)
        var dot = 0.0
        for (i in 0..3) dot += rotation[i].toDouble() * previousR[i]
        val angle = 2 * acos(min(1.0, abs(dot)))

        if (distance / dt <= maxSpeedMPerS && angle / dt <= maxRateRadPerS) return null
        val jump = Jump(frameIndex, timestampNs, distance, Math.toDegrees(angle), dt)
        // Negative indices are idle-time probes (gate only), not part of any recording.
        if (frameIndex >= 0 && jumps.size < MAX_RECORDED) jumps.add(jump)
        return jump
    }

    /** Frames where tracking was lost break continuity without being jumps. */
    fun onUntrackedFrame() {
        lastTranslation = null
        lastRotation = null
        lastTimestampNs = 0
    }

    val count: Int get() = jumps.size

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "count" to jumps.size,
        "max_speed_m_per_s" to maxSpeedMPerS,
        "max_rate_rad_per_s" to maxRateRadPerS,
        "jumps" to jumps.map { it.toJson() },
    )

    companion object {
        // Faster than any handheld motion; matches tools/check_sync.py.
        const val MAX_SPEED_M_PER_S = 3.0
        const val MAX_RATE_RAD_PER_S = 10.0
        private const val MAX_RECORDED = 100
    }
}
