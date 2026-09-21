package com.posecam

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Writes the recordings the training pipeline consumes, on the phone: one folder per
 * continuous jump-free segment, each holding `RGB_<stem>.mp4` and `AR_Pose_<stem>.txt`
 * with pose line N matching video frame N.
 *
 * Mirrors tools/export_anysense.py; the two are checked against each other on real
 * sessions (see docs/SETUP_GUIDE.md).
 */
object PipelineExporter {
    private const val TAG = "PoseCam"

    class ExportException(message: String) : Exception(message)

    class Result(val folders: List<File>, val frames: Int, val skipped: Int, val bytes: Long)

    /** The team records one way; a session recorded any other way is not comparable. */
    fun offProtocol(manifest: JSONObject): List<String> {
        val camera = manifest.optJSONObject("camera_config")
        val size = camera?.optJSONArray("cpu_image_size")
        val fpsRange = camera?.optJSONArray("fps_range")
        val problems = mutableListOf<String>()
        if (size == null || size.optInt(0) != 640 || size.optInt(1) != 480) {
            problems.add("recorded at ${size?.optInt(0)}x${size?.optInt(1)}, not the team's 640x480")
        }
        if (fpsRange != null && (fpsRange.optInt(0) != 30 || fpsRange.optInt(1) != 30)) {
            problems.add("camera set to ${fpsRange.optInt(0)}-${fpsRange.optInt(1)} fps, not 30")
        }
        val focus = manifest.optString("focus_mode", "AUTO")
        if (focus != "AUTO") problems.add("focus was $focus, not auto")
        val measured = manifest.optDouble("measured_fps", 30.0)
        if (Math.abs(measured - 30.0) > 0.6) problems.add("recorded at %.1f fps, not 30".format(measured))
        return problems
    }

    /**
     * @param rotateDegrees clockwise rotation of the image so the gripper jaws point up
     * @param outputRoot emptied and filled with one folder per exported segment
     */
    fun export(
        session: File,
        outputRoot: File,
        rotateDegrees: Int,
        appVersion: String,
        minSeconds: Double = SessionExport.MIN_SECONDS,
        onProgress: (stage: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): Result {
        val manifest = JSONObject(File(session, "manifest.json").readText())
        if (!manifest.optBoolean("complete", true)) {
            throw ExportException("This recording did not stop cleanly and cannot be exported.")
        }
        val problems = offProtocol(manifest)
        if (problems.isNotEmpty()) {
            throw ExportException("This recording was ${problems.joinToString("; ")}. " +
                "It cannot be mixed with the rest of the dataset, so it is not exported.")
        }
        val startWallMs = SessionExport.parseWallTime(manifest.getString("start_wall_time_utc"))
        // posecam-3 and earlier have no Record-tap stamp; the first frame predates it by ~100 ms.
        val pressedNs = if (manifest.isNull("record_pressed_elapsed_realtime_ns")) {
            manifest.getLong("first_timestamp_ns") + 100_000_000L
        } else {
            manifest.getLong("record_pressed_elapsed_realtime_ns")
        }
        val fps = Math.round(manifest.optDouble("measured_fps", 30.0)).toInt().coerceIn(1, 60)

        val rows = File(session, "poses.csv").bufferedReader().use { SessionExport.parsePoses(it) }
        val export = SessionExport(rows)
        val segments = export.segments()
        val usable = segments.filter { export.secondsOf(it) >= minSeconds }
        if (usable.isEmpty()) {
            throw ExportException(
                "Nothing to export: no stretch of at least ${minSeconds.toInt()} s without tracking loss or a pose jump."
            )
        }

        outputRoot.deleteRecursively()
        check(outputRoot.mkdirs()) { "Could not create $outputRoot" }
        val folders = mutableListOf<File>()
        var frames = 0
        val stems = mutableSetOf<String>()

        for ((n, segment) in usable.withIndex()) {
            val stem = SessionExport.stem(
                SessionExport.epochMs(export.timestampNs(segment.first), startWallMs, pressedNs), session.name, n
            )
            stems.add(stem)

            val folder = File(outputRoot, stem)
            check(folder.mkdirs()) { "Could not create $folder" }
            val files = segment.indices.map { index ->
                val row = export.imageRow(index)
                File(File(session, "frames"), FrameWriter.fileName(row.frameIndex, row.timestampNs))
            }
            val missing = files.filterNot { it.exists() }
            if (missing.isNotEmpty()) throw ExportException("Missing frame ${missing.first().name}")

            val label = "Recording ${n + 1} of ${usable.size}"
            val written = Mp4Writer.encode(
                files, File(folder, "RGB_$stem.mp4"), fps = fps, rotateDegrees = rotateDegrees,
            ) { done, total -> onProgress(label, done, total) }
            if (written != files.size) {
                throw ExportException("Video has $written frames but ${files.size} poses: not writing $stem")
            }

            File(folder, "AR_Pose_$stem.txt").bufferedWriter().use { out ->
                for (index in segment.indices) {
                    out.write(export.poseLine(index, SessionExport.epochMs(export.timestampNs(index), startWallMs, pressedNs)))
                    out.newLine()
                }
            }
            File(folder, "posecam_export.json").writeText(
                provenance(session, manifest, export, segment, n, usable.size, rotateDegrees, fps, appVersion) + "\n"
            )
            folders.add(folder)
            frames += written
            Log.i(TAG, "Exported $stem: $written frames, ${export.secondsOf(segment).toInt()} s")
        }
        return Result(folders, frames, segments.size - usable.size, SessionZipper.sizeOf(outputRoot))
    }

    private fun provenance(
        session: File, manifest: JSONObject, export: SessionExport, segment: SessionExport.Segment,
        index: Int, total: Int, rotateDegrees: Int, fps: Int, appVersion: String,
    ): String = JSONObject().apply {
        put("source_session", session.name)
        put("source_format", manifest.optString("format_version"))
        put("exported_by", "PoseCam $appVersion (on device)")
        put("rows_exported", JSONArray(listOf(segment.first, segment.last)))
        put("frames", segment.size)
        put("seconds", Math.round(export.secondsOf(segment) * 1000) / 1000.0)
        put("selection", "segment ${index + 1} of $total")
        put("pose_jumps_in_source", JSONArray(export.jumps))
        put("interpolated_pose_rows", JSONArray(segment.interpolated))
        put("reused_previous_image_rows", JSONArray(segment.reusedImages))
        put("measured_fps", manifest.opt("measured_fps"))
        put("video", JSONObject().apply {
            put("rotation_deg_clockwise", rotateDegrees)
            put("fps", fps)
            put("bit_rate", Mp4Writer.DEFAULT_BIT_RATE)
        })
        put("note", "AnySense writes no intrinsics; see the source session's intrinsics.json")
    }.toString(2)
}
