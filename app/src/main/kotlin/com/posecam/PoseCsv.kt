package com.posecam

/**
 * Row format for poses.csv. One row per distinct camera frame, tracked or not:
 * dropping untracked rows would make frame indices lie.
 */
object PoseCsv {
    const val HEADER = "frame_index,timestamp_ns,tx,ty,tz,qx,qy,qz,qw,tracking_state"

    /** [translation] is (x, y, z); [rotation] is (qx, qy, qz, qw), as ARCore's Pose returns them. */
    fun trackedRow(frameIndex: Long, timestampNs: Long, translation: FloatArray, rotation: FloatArray): String {
        require(translation.size == 3) { "translation must have 3 components" }
        require(rotation.size == 4) { "rotation must have 4 components" }
        return buildString {
            append(frameIndex).append(',').append(timestampNs)
            for (v in translation) append(',').append(v)
            for (v in rotation) append(',').append(v)
            append(",TRACKING")
        }
    }

    /** Pose columns are left empty; [state] is e.g. "PAUSED:INSUFFICIENT_FEATURES" or "STOPPED". */
    fun untrackedRow(frameIndex: Long, timestampNs: Long, state: String): String =
        "$frameIndex,$timestampNs,,,,,,,,$state"
}
