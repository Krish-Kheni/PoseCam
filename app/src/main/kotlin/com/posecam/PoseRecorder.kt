package com.posecam

import java.io.BufferedWriter
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes one capture session: poses.csv, frame_metadata.csv, frames/, intrinsics.json,
 * device.json and manifest.json. (imu.csv is written alongside by [ImuRecorder].)
 *
 * Thread-safe: frames arrive on the GL thread, start/stop come from the UI thread.
 */
class PoseRecorder(
    private val capturesRoot: File,
    private val encoder: FrameEncoder,
    private val imageMetadata: Map<String, Any?> = emptyMap(),
    poolSize: Int = 8,
) {
    data class Summary(val directory: File, val frameCount: Long, val imagesSaved: Long, val imagesDropped: Long)

    /** Buffers for image capture; the GL thread grabs from here. */
    val pool = BufferPool(poolSize)

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var metadataWriter: BufferedWriter? = null
    private val metadataSummary = FrameMetadataSummary()
    private var recordPressedElapsedNs: Long? = null
    private var frameWriter: FrameWriter? = null
    private var directory: File? = null
    private var sessionId = ""
    private var startWallTime = ""
    private var baseMetadata: Map<String, Any?> = emptyMap()
    private var frameCount = 0L
    private var trackedCount = 0L
    private var imagesQueued = 0L
    private val droppedByReason = linkedMapOf<String, Long>()
    private var writerStats: FrameWriter.Stats? = null
    private var firstTimestampNs: Long? = null
    private var lastTimestampNs: Long? = null
    private var rowsSinceFlush = 0
    private val intrinsics = IntrinsicsTracker()
    private var lastIntrinsicsFrame = -1L
    private var extraMetadata: Map<String, Any?> = emptyMap()

    val isRecording: Boolean get() = synchronized(lock) { writer != null }

    val recordedFrames: Long get() = synchronized(lock) { frameCount }

    val droppedImages: Long get() = synchronized(lock) { droppedByReason.values.sum() }

    /** Duration covered so far, from frame timestamps. */
    val recordedDurationNs: Long
        get() = synchronized(lock) {
            val first = firstTimestampNs ?: return 0L
            (lastTimestampNs ?: first) - first
        }

    /**
     * True if a frame with this timestamp would be recorded. The GL thread checks this
     * before acquiring the camera image, so nothing is grabbed while idle or for a
     * repeated frame (the renderer can run faster than the camera).
     */
    fun wantsFrame(timestampNs: Long): Boolean = synchronized(lock) {
        writer != null && lastTimestampNs.let { it == null || timestampNs > it }
    }

    /** True when the GL thread should sample intrinsics for the next frame (about once a second). */
    fun wantsIntrinsics(): Boolean = synchronized(lock) {
        writer != null && (lastIntrinsicsFrame < 0 || frameCount - lastIntrinsicsFrame >= INTRINSICS_EVERY_FRAMES)
    }

    fun onIntrinsics(value: Intrinsics) {
        synchronized(lock) {
            if (writer == null) return
            lastIntrinsicsFrame = frameCount
            if (intrinsics.update(frameCount, value)) writeIntrinsics(complete = false)
        }
    }

    /**
     * [metadata] is merged into manifest.json (app and camera details); [device] is written
     * as device.json.
     */
    fun start(
        metadata: Map<String, Any?>,
        device: Map<String, Any?> = emptyMap(),
        recordPressedElapsedRealtimeNs: Long? = null,
        wallTimeMs: Long = System.currentTimeMillis(),
    ): File = synchronized(lock) {
        check(writer == null) { "Already recording" }
        sessionId = newSessionId(wallTimeMs)
        val dir = File(capturesRoot, sessionId)
        check(dir.mkdirs()) { "Could not create $dir" }

        directory = dir
        startWallTime = isoUtc(wallTimeMs)
        baseMetadata = metadata
        frameCount = 0
        trackedCount = 0
        imagesQueued = 0
        droppedByReason.clear()
        writerStats = null
        firstTimestampNs = null
        lastTimestampNs = null
        rowsSinceFlush = 0
        intrinsics.reset()
        lastIntrinsicsFrame = -1
        extraMetadata = emptyMap()
        recordPressedElapsedNs = recordPressedElapsedRealtimeNs
        metadataSummary.reset()

        File(dir, "device.json").writeText(Json.write(device) + "\n")
        frameWriter = FrameWriter(File(dir, "frames"), encoder, pool)
        writer = File(dir, "poses.csv").bufferedWriter(bufferSize = 64 * 1024).apply {
            write(PoseCsv.HEADER)
            newLine()
        }
        metadataWriter = File(dir, "frame_metadata.csv").bufferedWriter(bufferSize = 64 * 1024).apply {
            write(FrameMetadata.HEADER)
            newLine()
        }
        // Written now as well as at stop, so a crash still leaves a self-describing folder.
        writeManifest(stopWallTime = null)
        dir
    }

    /**
     * Records one camera frame. [translation]/[rotation] must be non-null exactly when
     * tracking. Takes ownership of a captured image buffer in every case.
     */
    fun onFrame(
        timestampNs: Long, trackingState: String, translation: FloatArray?, rotation: FloatArray?, image: FrameImage,
        metadata: FrameMetadata? = null,
    ) {
        synchronized(lock) {
            val out = writer
            val frames = frameWriter
            val last = lastTimestampNs
            if (out == null || frames == null || (last != null && timestampNs <= last)) {
                if (image is FrameImage.Captured) pool.release(image.buffer)
                return
            }

            val imageStatus = when (image) {
                is FrameImage.Captured -> {
                    frames.submit(frameCount, timestampNs, image.buffer)
                    imagesQueued++
                    PoseCsv.IMAGE_SAVED
                }
                is FrameImage.Dropped -> {
                    droppedByReason[image.reason] = (droppedByReason[image.reason] ?: 0L) + 1
                    PoseCsv.droppedImage(image.reason)
                }
            }

            val row = if (translation != null && rotation != null) {
                trackedCount++
                PoseCsv.trackedRow(frameCount, timestampNs, translation, rotation, imageStatus)
            } else {
                PoseCsv.untrackedRow(frameCount, timestampNs, trackingState, imageStatus)
            }
            out.write(row)
            out.newLine()
            metadataWriter?.let {
                it.write(FrameMetadata.row(frameCount, timestampNs, metadata))
                it.newLine()
            }
            metadataSummary.add(metadata)

            if (firstTimestampNs == null) firstTimestampNs = timestampNs
            lastTimestampNs = timestampNs
            frameCount++
            if (++rowsSinceFlush >= FLUSH_EVERY_ROWS) {
                out.flush()
                metadataWriter?.flush()
                rowsSinceFlush = 0
            }
        }
    }

    /**
     * Stops recording and waits for queued frames to be written. Call from the UI thread.
     * [extra] is merged into manifest.json (e.g. IMU stats).
     */
    fun stop(extra: Map<String, Any?> = emptyMap(), wallTimeMs: Long = System.currentTimeMillis()): Summary? {
        val frames = synchronized(lock) {
            val out = writer ?: return null
            out.close()
            writer = null
            metadataWriter?.close()
            metadataWriter = null
            frameWriter.also { frameWriter = null }!!
        }
        // Outside the lock: the GL thread must not wait on JPEG encoding.
        val stats = frames.finish()
        return synchronized(lock) {
            writerStats = stats
            extraMetadata = extra
            writeIntrinsics(complete = true)
            writeManifest(isoUtc(wallTimeMs))
            Summary(directory!!, frameCount, stats.written, droppedByReason.values.sum() + stats.failedFrameIndices.size)
        }
    }

    private fun writeManifest(stopWallTime: String?) {
        val stats = writerStats
        val images = linkedMapOf<String, Any?>(
            "directory" to "frames",
            "filename_pattern" to "{frame_index:06d}_{timestamp_ns}.jpg",
        )
        images.putAll(imageMetadata)
        images["queued"] = imagesQueued
        images["written"] = stats?.written
        images["dropped"] = LinkedHashMap(droppedByReason)
        images["write_failures"] = stats?.failedFrameIndices
        images["first_write_error"] = stats?.firstError
        images["image_frame_timestamp_mismatches_over_5ms"] = stats?.timestampMismatches
        images["image_minus_frame_timestamp_ns_range"] = stats?.imageMinusFrameNs

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
            // Tap time on the frame/IMU clock. Row 0 of poses.csv is the first frame the app
            // received after it, which was exposed up to ~100 ms earlier.
            "record_pressed_elapsed_realtime_ns" to recordPressedElapsedNs,
            "images" to images,
            "capture_metadata" to metadataSummary.toJson(),
        )
        manifest["intrinsics_changed_during_recording"] = intrinsics.distinctValues > 1
        manifest.putAll(baseMetadata)
        manifest.putAll(extraMetadata)
        File(directory, "manifest.json").writeText(Json.write(manifest) + "\n")
    }

    private fun writeIntrinsics(complete: Boolean) {
        File(directory, "intrinsics.json").writeText(Json.write(intrinsics.toJson(complete)) + "\n")
    }

    companion object {
        const val FORMAT_VERSION = "posecam-4"
        private const val INTRINSICS_EVERY_FRAMES = 30
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
