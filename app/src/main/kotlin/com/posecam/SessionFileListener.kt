package com.posecam

import java.io.File

/**
 * How recording reports lifecycle facts without knowing anything about HTTP, S3 or Room.
 * [PoseRecorder] calls it on the thread that called `start`/`stop` and swallows anything it throws,
 * so a faulty listener can never interrupt a recording. Implementations must return immediately
 * (hand the work to another thread).
 *
 * Nothing in a PoseCam session is closed before Stop (the CSVs and the JPEG queue stay open), so
 * there is no per-file event: only the two ends of the take.
 */
interface SessionFileListener {
    /** The session folder exists and recording is about to begin: its files are still being written. */
    fun onSessionStarted(sessionId: String, directory: File) {}

    /**
     * Recording is over and every file is closed and immutable. [recordingStatus] is read back from
     * the manifest: `complete`, or `incomplete` when the take did not stop cleanly.
     */
    fun onSessionFinalized(sessionId: String, directory: File, recordingStatus: String) {}
}
