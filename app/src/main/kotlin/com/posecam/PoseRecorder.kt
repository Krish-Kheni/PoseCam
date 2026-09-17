package com.posecam

import java.io.BufferedWriter
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes one capture session: a folder containing poses.csv and manifest.json.
 *
 * Thread-safe: frames arrive on the GL thread, start/stop come from the UI thread.
 */
class PoseRecorder(private val capturesRoot: File) {

    data class Summary(val directory: File, val frameCount: Long)

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var directory: File? = null
    private var sessionId = ""
    private var startWallTime = ""
    private var baseMetadata: Map<String, Any?> = emptyMap()
    private var frameCount = 0L
    private var trackedCount = 0L
    private var firstTimestampNs: Long? = null
    private var lastTimestampNs: Long? = null
    private var rowsSinceFlush = 0

    val isRecording: Boolean get() = synchronized(lock) { writer != null }

    val recordedFrames: Long get() = synchronized(lock) { frameCount }

    /** Duration covered so far, from frame timestamps. */
    val recordedDurationNs: Long
        get() = synchronized(lock) {
            val first = firstTimestampNs ?: return 0L
            (lastTimestampNs ?: first) - first
        }

    /** [metadata] is merged into manifest.json (device, app and camera details). */
    fun start(metadata: Map<String, Any?>, wallTimeMs: Long = System.currentTimeMillis()): File = synchronized(lock) {
        check(writer == null) { "Already recording" }
        sessionId = newSessionId(wallTimeMs)
        val dir = File(capturesRoot, sessionId)
        check(dir.mkdirs()) { "Could not create $dir" }

        directory = dir
        startWallTime = isoUtc(wallTimeMs)
        baseMetadata = metadata
        frameCount = 0
        trackedCount = 0
        firstTimestampNs = null
        lastTimestampNs = null
        rowsSinceFlush = 0

        writer = File(dir, "poses.csv").bufferedWriter(bufferSize = 64 * 1024).apply {
            write(PoseCsv.HEADER)
            newLine()
        }
        // Written now as well as at stop, so a crash still leaves a self-describing folder.
        writeManifest(stopWallTime = null)
        dir
    }

    /**
     * Records one camera frame. [translation]/[rotation] must be non-null exactly when
     * [trackingState] is "TRACKING". Repeated timestamps (the renderer can run faster
     * than the camera) are ignored so each row is a distinct image.
     */
    fun onFrame(timestampNs: Long, trackingState: String, translation: FloatArray?, rotation: FloatArray?) {
        synchronized(lock) {
            val out = writer ?: return
            val last = lastTimestampNs
            if (last != null && timestampNs <= last) return

            val row = if (translation != null && rotation != null) {
                trackedCount++
                PoseCsv.trackedRow(frameCount, timestampNs, translation, rotation)
            } else {
                PoseCsv.untrackedRow(frameCount, timestampNs, trackingState)
            }
            out.write(row)
            out.newLine()

            if (firstTimestampNs == null) firstTimestampNs = timestampNs
            lastTimestampNs = timestampNs
            frameCount++
            if (++rowsSinceFlush >= FLUSH_EVERY_ROWS) {
                out.flush()
                rowsSinceFlush = 0
            }
        }
    }

    fun stop(wallTimeMs: Long = System.currentTimeMillis()): Summary? = synchronized(lock) {
        val out = writer ?: return null
        out.close()
        writer = null
        writeManifest(isoUtc(wallTimeMs))
        Summary(directory!!, frameCount)
    }

    private fun writeManifest(stopWallTime: String?) {
        val manifest = linkedMapOf<String, Any?>(
            "format_version" to FORMAT_VERSION,
            "session_id" to sessionId,
            "start_wall_time_utc" to startWallTime,
            "stop_wall_time_utc" to stopWallTime,
            "complete" to (stopWallTime != null),
            "frame_count" to frameCount,
            "tracked_frame_count" to trackedCount,
            "first_timestamp_ns" to firstTimestampNs,
            "last_timestamp_ns" to lastTimestampNs,
        )
        manifest.putAll(baseMetadata)
        File(directory, "manifest.json").writeText(Json.write(manifest) + "\n")
    }

    companion object {
        const val FORMAT_VERSION = "posecam-1"
        private const val FLUSH_EVERY_ROWS = 100
        private val random = SecureRandom()

        /** e.g. capture-20260916T143052-a3f9c1 (local time, for humans; timestamps inside are authoritative). */
        fun newSessionId(wallTimeMs: Long): String {
            val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).format(Date(wallTimeMs))
            val suffix = "%06x".format(random.nextInt(0x1000000))
            return "capture-$stamp-$suffix"
        }

        /** Wall-clock time is metadata only; it never keys any recorded data. */
        fun isoUtc(wallTimeMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(wallTimeMs))
    }
}
