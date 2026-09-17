package com.posecam

/**
 * Per-frame Camera2 capture results, as ARCore exposes them. Any field can be null when
 * the phone does not report it. Written to frame_metadata.csv, one row per poses.csv row.
 */
data class FrameMetadata(
    val exposureTimeNs: Long? = null,
    val frameDurationNs: Long? = null,
    val rollingShutterSkewNs: Long? = null,
    val sensitivityIso: Int? = null,
    /** Diopters (1/m); 0 means infinity. Uncalibrated units on some phones, see device.json. */
    val focusDistanceDiopters: Float? = null,
    val focalLengthMm: Float? = null,
    /** CaptureResult.LENS_OPTICAL_STABILIZATION_MODE: 0 = off, 1 = on. */
    val opticalStabilizationMode: Int? = null,
) {
    companion object {
        const val HEADER = "frame_index,timestamp_ns,exposure_time_ns,frame_duration_ns,rolling_shutter_skew_ns," +
            "sensitivity_iso,focus_distance_diopters,focal_length_mm,ois_mode"

        fun row(frameIndex: Long, timestampNs: Long, m: FrameMetadata?): String = buildString {
            append(frameIndex).append(',').append(timestampNs)
            for (v in listOf(
                m?.exposureTimeNs, m?.frameDurationNs, m?.rollingShutterSkewNs, m?.sensitivityIso,
                m?.focusDistanceDiopters, m?.focalLengthMm, m?.opticalStabilizationMode,
            )) {
                append(',')
                if (v != null) append(v)
            }
        }
    }
}

/** Running summary of [FrameMetadata] for manifest.json. */
class FrameMetadataSummary {
    private var frames = 0L
    private var framesWithMetadata = 0L
    private val oisModes = sortedSetOf<Int>()
    private var framesOisOn = 0L
    private var minFocus: Float? = null
    private var maxFocus: Float? = null
    private var minExposure: Long? = null
    private var maxExposure: Long? = null

    fun reset() {
        frames = 0
        framesWithMetadata = 0
        oisModes.clear()
        framesOisOn = 0
        minFocus = null
        maxFocus = null
        minExposure = null
        maxExposure = null
    }

    fun add(m: FrameMetadata?) {
        frames++
        if (m == null) return
        framesWithMetadata++
        m.opticalStabilizationMode?.let {
            oisModes.add(it)
            if (it != 0) framesOisOn++
        }
        m.focusDistanceDiopters?.let {
            minFocus = minOf(minFocus ?: it, it)
            maxFocus = maxOf(maxFocus ?: it, it)
        }
        m.exposureTimeNs?.let {
            minExposure = minOf(minExposure ?: it, it)
            maxExposure = maxOf(maxExposure ?: it, it)
        }
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "frames" to frames,
        "frames_with_metadata" to framesWithMetadata,
        "ois_modes_seen" to oisModes.toList(),
        "frames_with_ois_on" to framesOisOn,
        "focus_distance_diopters_range" to minFocus?.let { floatArrayOf(it, maxFocus!!) },
        "exposure_time_ns_range" to minExposure?.let { listOf(it, maxExposure!!) },
    )
}
