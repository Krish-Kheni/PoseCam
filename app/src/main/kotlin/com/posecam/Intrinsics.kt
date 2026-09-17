package com.posecam

/** Pinhole intrinsics of the CPU image, in pixels. ARCore reports no distortion model. */
data class Intrinsics(
    val fx: Float, val fy: Float,
    val cx: Float, val cy: Float,
    val width: Int, val height: Int,
) {
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "width" to width, "height" to height,
        "fx" to fx, "fy" to fy, "cx" to cx, "cy" to cy,
    )
}

/** Samples intrinsics during a recording and reports whether they ever changed. */
class IntrinsicsTracker {
    var first: Intrinsics? = null
        private set
    var last: Intrinsics? = null
        private set
    var distinctValues = 0
        private set
    var samples = 0L
        private set
    private var firstChangeFrame: Long? = null

    fun reset() {
        first = null
        last = null
        distinctValues = 0
        samples = 0
        firstChangeFrame = null
    }

    /** Returns true if this is the first sample (so intrinsics.json should be written now). */
    fun update(frameIndex: Long, value: Intrinsics): Boolean {
        samples++
        val previous = last
        last = value
        if (previous == null) {
            first = value
            distinctValues = 1
            return true
        }
        if (value != previous) {
            distinctValues++
            if (firstChangeFrame == null) firstChangeFrame = frameIndex
        }
        return false
    }

    fun toJson(complete: Boolean): Map<String, Any?> {
        val start = first ?: return linkedMapOf("available" to false)
        val json = linkedMapOf<String, Any?>(
            "model" to "pinhole",
            "distortion" to "none reported by ARCore",
            "source" to "Camera.getImageIntrinsics",
        )
        json.putAll(start.toJson())
        json["complete"] = complete
        json["samples"] = samples
        json["changed_during_recording"] = distinctValues > 1
        json["distinct_values"] = distinctValues
        json["first_change_frame_index"] = firstChangeFrame
        json["at_end"] = if (complete) last?.toJson() else null
        return json
    }
}
