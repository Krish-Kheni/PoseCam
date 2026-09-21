package com.posecam

/**
 * Judges a finished take so the collector does not have to watch the screen while recording.
 * Pure logic: thresholds match what the exporter and the training pipeline do with the data.
 */
object TakeVerdict {
    /** Below this, a take yields too little for the consumer's 8-frame action stride. */
    const val MIN_USEFUL_SECONDS = 5.0
    /** A gap longer than the exporter's 5-frame interpolation cap splits the take. */
    const val SPLITTING_GAP_SECONDS = 5.0 / 30

    class Result(val redo: Boolean, val headline: String, val reasons: List<String>, val detail: String)

    fun of(
        seconds: Double,
        frames: Long,
        trackedFrames: Long,
        poseJumps: Int,
        longestGapSeconds: Double,
        droppedImages: Long,
    ): Result {
        val untracked = frames - trackedFrames
        val reasons = mutableListOf<String>()
        if (seconds < MIN_USEFUL_SECONDS) {
            reasons.add("Only %.1f s long.".format(seconds))
        }
        if (poseJumps > 0) {
            reasons.add(
                if (poseJumps == 1) "Tracking jumped once: the take is split there, so part of it is lost."
                else "Tracking jumped $poseJumps times: the take is split there, so parts of it are lost."
            )
        }
        if (longestGapSeconds > SPLITTING_GAP_SECONDS) {
            reasons.add("Lost tracking for %.1f s: the take is split there.".format(longestGapSeconds))
        } else if (untracked > 0) {
            reasons.add("$untracked frame(s) without tracking, short enough to be filled in.")
        }
        if (droppedImages > 0) {
            reasons.add("$droppedImages frame(s) had no image saved.")
        }
        // Short gaps and a few missing images are repaired by the exporter; jumps and long
        // gaps cut the take in two, and a short take may not survive that.
        val redo = poseJumps > 0 || longestGapSeconds > SPLITTING_GAP_SECONDS || seconds < MIN_USEFUL_SECONDS
        val headline = if (redo) "Better to record this one again" else "Take looks good"
        val detail = "%.0f s, %d frames, %.0f%% tracked".format(
            seconds, frames, if (frames > 0) 100.0 * trackedFrames / frames else 0.0
        )
        return Result(redo, headline, reasons, detail)
    }
}
