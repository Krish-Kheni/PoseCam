package com.posecam

/**
 * Arms the record button only after tracking has been continuous for [requiredStableNs].
 * ARCore's first second or two of tracking is unreliable; this keeps it out of recordings.
 * Time is measured with frame timestamps, never wall clock.
 */
class TrackingGate(private val requiredStableNs: Long = 1_000_000_000L) {
    private var trackingSinceNs: Long? = null

    /** Feed every frame; returns true when recording may start. */
    fun update(isTracking: Boolean, timestampNs: Long): Boolean {
        if (!isTracking) {
            trackingSinceNs = null
            return false
        }
        val since = trackingSinceNs ?: timestampNs.also { trackingSinceNs = it }
        return timestampNs - since >= requiredStableNs
    }
}
