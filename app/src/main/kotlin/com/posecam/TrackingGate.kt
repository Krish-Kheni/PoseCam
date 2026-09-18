package com.posecam

/**
 * Arms the record button only after tracking has been continuous, and free of pose jumps,
 * for [requiredStableNs]. ARCore's pose keeps re-scaling for the first seconds of a
 * session (a Tecno Pova 5G take showed seven 13–55 cm jumps in its first 1.4 s), so a
 * mere TRACKING state is not enough. Time is measured with frame timestamps, never wall clock.
 */
class TrackingGate(private val requiredStableNs: Long = 3_000_000_000L) {
    private var stableSinceNs: Long? = null

    /** Feed every frame; returns true when recording may start. */
    fun update(isTracking: Boolean, timestampNs: Long, jumped: Boolean = false): Boolean {
        if (!isTracking || jumped) {
            stableSinceNs = null
            return false
        }
        val since = stableSinceNs ?: timestampNs.also { stableSinceNs = it }
        return timestampNs - since >= requiredStableNs
    }
}
