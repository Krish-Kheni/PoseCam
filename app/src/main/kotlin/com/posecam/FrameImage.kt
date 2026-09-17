package com.posecam

/** Outcome of trying to capture the camera image for one frame. */
sealed interface FrameImage {
    class Captured(val buffer: YuvBuffer) : FrameImage

    /** [reason] is written to poses.csv as `dropped:<reason>`. */
    class Dropped(val reason: String) : FrameImage

    companion object {
        const val QUEUE_FULL = "queue_full"
        const val NOT_YET_AVAILABLE = "not_yet_available"
        const val DEADLINE_EXCEEDED = "deadline_exceeded"
        const val RESOURCES_EXHAUSTED = "resources_exhausted"
    }
}
