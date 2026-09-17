package com.posecam

/**
 * Row format for poses.csv. One row per distinct camera frame, tracked or not:
 * dropping untracked rows would make frame indices lie.
 *
 * The `image` column is `saved` when a JPEG was queued for this frame (file
 * frames/<frame_index>_<timestamp_ns>.jpg), `dropped:<reason>` otherwise.
 */
object PoseCsv {
    const val HEADER = "frame_index,timestamp_ns,tx,ty,tz,qx,qy,qz,qw,tracking_state,image"

    const val IMAGE_SAVED = "saved"

    fun droppedImage(reason: String) = "dropped:$reason"

    /** [translation] is (x, y, z); [rotation] is (qx, qy, qz, qw), as ARCore's Pose returns them. */
    fun trackedRow(
        frameIndex: Long, timestampNs: Long, translation: FloatArray, rotation: FloatArray, image: String,
    ): String {
        require(translation.size == 3) { "translation must have 3 components" }
        require(rotation.size == 4) { "rotation must have 4 components" }
        return buildString {
            append(frameIndex).append(',').append(timestampNs)
            for (v in translation) append(',').append(v)
            for (v in rotation) append(',').append(v)
            append(",TRACKING,").append(image)
        }
    }

    /** Pose columns are left empty; [state] is e.g. "PAUSED:INSUFFICIENT_FEATURES" or "STOPPED". */
    fun untrackedRow(frameIndex: Long, timestampNs: Long, state: String, image: String): String =
        "$frameIndex,$timestampNs,,,,,,,,$state,$image"
}
