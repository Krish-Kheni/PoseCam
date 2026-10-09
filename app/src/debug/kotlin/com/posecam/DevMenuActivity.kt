package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.posecam.core.cloud.SampleUploads
import com.posecam.core.cloud.UploadStatsCache
import com.posecam.core.sync.CloudSessionEntity
import com.posecam.core.sync.CloudSync
import com.posecam.core.sync.ExportState
import com.posecam.core.sync.PublishState
import com.posecam.core.sync.UploadDatabase
import com.posecam.core.sync.UploadEntity
import com.posecam.core.sync.UploadFileType
import com.posecam.core.sync.UploadSourceKind
import com.posecam.core.sync.UploadState
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Debug-only launcher for phones and emulators without ARCore: reach every screen without the camera, and fill the
 * Recordings list with one fake recording per state, so each look of the UI can be checked without a backend.
 * Not part of release builds (it lives in src/debug).
 */
class DevMenuActivity : Activity() {

    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        info = TextView(this).apply { textSize = 14f }
        column.addView(TextView(this).apply { text = "PoseCam dev menu (debug build only)"; textSize = 18f })
        column.addView(info)
        column.addView(
            TextView(this).apply {
                text = "Fake recordings are not real uploads, but while you are signed in the background uploader will try " +
                    "to send the queued ones. Sign out first to keep every state as seeded."
                textSize = 12f
                alpha = 0.7f
                setPadding(0, pad / 2, 0, pad / 2)
            },
        )
        column.addView(button("Open Recordings") { startActivity(Intent(this, SessionsActivity::class.java)) })
        column.addView(button("Add one recording in every cloud state") { seedEveryState() })
        column.addView(button("Add fake recording") { seed(complete = true) })
        column.addView(button("Add fake incomplete recording") { seed(complete = false) })
        column.addView(button("Open sign-in screen") { startActivity(Intent(this, AuthActivity::class.java)) })
        column.addView(button("Open My uploads with sample data") { openSampleUploads() })
        column.addView(button(sampleDaysLabel(sampleDaysOn())) { }.also { b -> b.setOnClickListener { toggleSampleDays(b) } })
        column.addView(button("Open My uploads (needs a real sign-in)") { startActivity(Intent(this, UploadStatsActivity::class.java)) })
        column.addView(button("Sign out (shows the sign-in banner in Recordings)") { signOut() })
        column.addView(button("Delete ALL recordings") { confirmDeleteAll() })
        column.addView(button("Open camera screen (needs ARCore)") { startActivity(Intent(this, CaptureActivity::class.java)) })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        val sync = runCatching { CloudSync.get(this) }.getOrNull()
        val cloud = sync?.config?.enabled == true
        val who = sync?.auth?.current()?.session?.email
        info.text = "Cloud upload: ${if (cloud) "ON (${BuildConfig.CLOUD_BASE_URL})" else "OFF (no backend URL in this build)"}\n" +
            "Signed in: ${who ?: "no"}"
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    /** The whole sample history, so My uploads can be checked without a server or a sign-in. */
    private fun openSampleUploads() {
        val (stats, recordings) = SampleUploads.fullHistory(System.currentTimeMillis())
        startActivity(
            Intent(this, UploadStatsActivity::class.java)
                .putExtra(UploadStatsActivity.EXTRA_PREVIEW, UploadStatsCache.encode(stats, System.currentTimeMillis()))
                .putExtra(UploadStatsActivity.EXTRA_PREVIEW_RECORDINGS, UploadStatsCache.encodeRecordings(recordings)),
        )
    }

    private fun sampleDaysOn() = getSharedPreferences(UploadStatsActivity.DEBUG_PREFS, MODE_PRIVATE).getBoolean(UploadStatsActivity.KEY_SAMPLE_DAYS, false)

    private fun toggleSampleDays(button: Button) {
        val on = !sampleDaysOn()
        getSharedPreferences(UploadStatsActivity.DEBUG_PREFS, MODE_PRIVATE).edit().putBoolean(UploadStatsActivity.KEY_SAMPLE_DAYS, on).apply()
        button.text = sampleDaysLabel(on)
    }

    private fun sampleDaysLabel(on: Boolean) = "Sample past days in the real My uploads: ${if (on) "ON" else "OFF"}"

    private fun signOut() {
        runCatching { CloudSync.get(this).signOut() }
        onResume()
        Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
    }

    private fun captures() = File(getExternalFilesDir(null), "captures")

    /** Writes a small, PoseCam-shaped session folder: every file the uploader requires, plus [frames] fake JPEGs. */
    private fun writeSession(id: String, at: Long, complete: Boolean, frames: Int = 12): File {
        val dir = File(captures(), id).also { it.mkdirs() }
        File(dir, "manifest.json").writeText(
            """{"format_version":"posecam-5","session_id":"$id","start_wall_time_utc":"${PoseRecorder.isoUtc(at)}",""" +
                """"complete":$complete,"frame_count":$frames}""",
        )
        File(dir, "device.json").writeText("""{"model":"dev-menu fake"}""")
        File(dir, "intrinsics.json").writeText("""{"fx":1000.0,"fy":1000.0,"cx":640.0,"cy":360.0}""")
        File(dir, "poses.csv").writeText("index,timestamp_ns,status\n" + (0 until frames).joinToString("\n") { "$it,${it * 33_000_000L},saved" } + "\n")
        File(dir, "frame_metadata.csv").writeText("index,timestamp_ns\n" + (0 until frames).joinToString("\n") { "$it,${it * 33_000_000L}" } + "\n")
        File(dir, "imu.csv").writeText("timestamp_ns,ax,ay,az\n0,0.0,0.0,9.8\n")
        val jpegs = File(dir, "frames").also { it.mkdirs() }
        for (i in 0 until frames) {
            File(jpegs, "%06d_%d.jpg".format(i, i * 33_000_000L)).writeBytes(ByteArray(2_000 + i) { (i + it).toByte() })
        }
        return dir
    }

    private fun seed(complete: Boolean) {
        val now = System.currentTimeMillis()
        val id = PoseRecorder.newSessionId(now)
        writeSession(id, now, complete)
        runCatching { CloudSync.get(this).recoverQueue() }
        Toast.makeText(this, "Added $id", Toast.LENGTH_SHORT).show()
    }

    /** Removes every recording folder and every queue row, so the next test starts from an empty list. */
    private fun confirmDeleteAll() {
        val count = captures().listFiles { f -> f.isDirectory }?.size ?: 0
        AlertDialog.Builder(this)
            .setTitle("Delete all recordings?")
            .setMessage("This removes $count recording folder(s) from this phone, including real ones, and clears the upload queue. Nothing in the cloud is touched. It cannot be undone.")
            .setPositiveButton("Delete all") { _, _ ->
                Thread {
                    val dao = UploadDatabase.get(this).uploadDao()
                    runBlocking {
                        dao.allSessions().forEach { dao.deleteUploadsForSession(it.sessionId); dao.deleteSession(it.sessionId) }
                    }
                    captures().listFiles()?.forEach { it.deleteRecursively() }
                    File(getExternalFilesDir(null), "upload-staging").deleteRecursively()
                    runOnUiThread { Toast.makeText(this, "All recordings deleted", Toast.LENGTH_SHORT).show() }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Writes straight into the upload queue database, so the Recordings screen shows every state without a backend or
     * S3. It does not start the upload worker (and `recoverQueue` is not called), so the states stay put. Do not open the
     * camera screen afterwards: it runs queue recovery, which may move them.
     *
     * The first rows are the ones a collector sees while uploading; the "Synced" rows show each way the pipeline export
     * and the website step can end; the last two are fully finished, so "Delete all synced" has something to delete.
     */
    private fun seedEveryState() {
        Thread {
            val dao = UploadDatabase.get(this).uploadDao()
            val now = System.currentTimeMillis()
            var minute = 0
            val V = UploadState.VERIFIED

            fun session(
                pipe: String?,
                created: Boolean = true,
                syncedAt: Long? = null,
                error: String? = null,
                export: ExportState = ExportState.PENDING,
                exportNote: String? = null,
                publish: PublishState = PublishState.PENDING,
                publishedSets: Int = 0,
                publishNote: String? = null,
                files: (String, File) -> List<UploadEntity>,
            ) {
                val at = now - (minute++) * 60_000L
                val id = PoseRecorder.newSessionId(at)
                val dir = writeSession(id, at, true)
                runBlocking {
                    dao.insertSessionIgnore(
                        CloudSessionEntity(
                            sessionId = id, directoryPath = dir.absolutePath, recordingStatus = "complete", recordingFinal = true,
                            cloudCreated = created, syncedAt = syncedAt, lastError = error, createdAt = at, updatedAt = at,
                            pipe = pipe, exportState = export, exportNote = exportNote, publishState = publish,
                            publishedSets = publishedSets, publishNote = publishNote,
                        ),
                    )
                    files(id, dir).forEach { dao.insertUploadIgnore(it) }
                }
            }

            fun row(
                id: String, path: String, type: UploadFileType, size: Long, state: UploadState,
                sent: Long = 0, error: String? = null, items: Int = 0, required: Boolean = true, local: String = "$id/$path",
            ) = UploadEntity(
                sessionId = id, relativePath = path, localPath = File(captures(), local).absolutePath, fileType = type,
                kind = if (type == UploadFileType.FRAME_CHUNK) UploadSourceKind.FRAME_CHUNK else UploadSourceKind.PLAIN,
                itemCount = items, required = required, sizeBytes = size, state = state, uploadedBytes = sent, lastError = error,
                createdAt = now, updatedAt = now,
            )

            // Four files of 2 KB, 400 KB, 3 MB and 30 MB: the percentage of the whole recording follows their sizes.
            fun fileSet(id: String, states: List<UploadState>, sent: List<Long> = List(4) { 0 }, error: String? = null): List<UploadEntity> {
                val specs = listOf(
                    Triple("manifest.json", UploadFileType.METADATA, 2_000L),
                    Triple("poses.csv", UploadFileType.TABLE, 400_000L),
                    Triple("imu.csv", UploadFileType.IMU, 3_000_000L),
                    Triple("frames-00000.zip", UploadFileType.FRAME_CHUNK, 30_000_000L),
                )
                return specs.mapIndexed { i, (path, type, size) ->
                    row(id, path, type, size, states[i], sent[i], if (states[i] == UploadState.FAILED) error else null,
                        items = if (type == UploadFileType.FRAME_CHUNK) 1000 else 0)
                }
            }

            /** A recording whose rows match its folder exactly, so storage cleanup considers it safe to delete. */
            fun finished(id: String, dir: File): List<UploadEntity> {
                val plain = listOf(
                    "manifest.json" to UploadFileType.METADATA, "device.json" to UploadFileType.METADATA,
                    "intrinsics.json" to UploadFileType.METADATA, "poses.csv" to UploadFileType.TABLE,
                    "frame_metadata.csv" to UploadFileType.TABLE, "imu.csv" to UploadFileType.IMU,
                ).map { (name, type) -> row(id, name, type, File(dir, name).length(), V) }
                val frames = File(dir, "frames").listFiles().orEmpty()
                return plain + row(id, "frames-00000.zip", UploadFileType.FRAME_CHUNK, frames.sumOf { it.length() }, V, items = frames.size)
            }

            fun exportRows(id: String, state: UploadState, sent: Long = 0): List<UploadEntity> = listOf(
                row(id, "export/demo-s1/RGB_demo-s1.mp4", UploadFileType.EXPORT, 50_000_000, state, sent, required = false),
                row(id, "export/demo-s1/AR_Pose_demo-s1.txt", UploadFileType.EXPORT, 300_000, state, required = false),
            )

            // ---- while it uploads ----
            session(pipe = null, created = false) { _, _ -> emptyList() }                                 // choose a pipe
            session(pipe = "white") { id, _ -> fileSet(id, listOf(UploadState.PENDING, UploadState.PENDING, UploadState.PENDING, UploadState.PENDING)) } // queued
            session(pipe = "black") { id, _ ->                                                          // uploading, about 10%
                fileSet(id, listOf(V, UploadState.UPLOADING, UploadState.PENDING, UploadState.PENDING), sent = listOf(2_000, 400_000, 0, 0))
            }
            session(pipe = "white") { id, _ ->                                                          // uploading, about 60%
                fileSet(id, listOf(V, V, V, UploadState.UPLOADING), sent = listOf(2_000, 400_000, 3_000_000, 16_600_000))
            }
            session(pipe = "black") { id, _ ->                                                          // verifying: all bytes in
                fileSet(id, listOf(V, V, V, UploadState.UPLOADED), sent = listOf(2_000, 400_000, 3_000_000, 30_000_000))
            }
            session(pipe = "white") { id, _ ->                                                          // failed
                fileSet(id, listOf(V, V, UploadState.FAILED, UploadState.PENDING), error = "The server rejected imu.csv (HTTP 403)")
            }
            // ---- uploaded: what can still be going on ----
            session(pipe = "black", syncedAt = now, export = ExportState.PENDING) { id, _ -> fileSet(id, listOf(V, V, V, V)) } // making the export
            session(pipe = "white", syncedAt = now, export = ExportState.DONE) { id, _ ->               // uploading the export
                fileSet(id, listOf(V, V, V, V)) + exportRows(id, UploadState.UPLOADING, 10_000_000)
            }
            session(pipe = "black", syncedAt = now, export = ExportState.DONE, publish = PublishState.PENDING) { id, _ -> // publishing
                fileSet(id, listOf(V, V, V, V)) + exportRows(id, V)
            }
            session(pipe = "white", syncedAt = now, export = ExportState.OFF_PROTOCOL, exportNote = "recorded at 640x360, not the team's 640x480", publish = PublishState.UNSUPPORTED) { id, _ ->
                fileSet(id, listOf(V, V, V, V))
            }
            session(pipe = "black", syncedAt = now, export = ExportState.FAILED, exportNote = "The encoder ran out of storage", publish = PublishState.UNSUPPORTED) { id, _ ->
                fileSet(id, listOf(V, V, V, V))
            }
            session(pipe = "white", syncedAt = now, export = ExportState.DONE, publish = PublishState.FAILED, publishNote = "ffmpeg exited with code 1") { id, _ ->
                fileSet(id, listOf(V, V, V, V)) + exportRows(id, V)
            }
            // ---- finished: live on the website, safe to delete ----
            repeat(2) {
                session(pipe = if (it == 0) "black" else "white", syncedAt = now, export = ExportState.DONE, publish = PublishState.DONE, publishedSets = 1) { id, dir ->
                    finished(id, dir) + exportRows(id, V)
                }
            }
            runOnUiThread { Toast.makeText(this, "Added one recording per cloud state", Toast.LENGTH_LONG).show() }
        }.start()
    }
}
