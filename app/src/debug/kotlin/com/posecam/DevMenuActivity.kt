package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.posecam.core.sync.CloudSessionEntity
import com.posecam.core.sync.CloudSync
import com.posecam.core.sync.UploadDatabase
import com.posecam.core.sync.UploadEntity
import com.posecam.core.sync.UploadFileType
import com.posecam.core.sync.UploadSourceKind
import com.posecam.core.sync.UploadState
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Debug-only launcher for phones and emulators without ARCore: reach the Recordings screen without the camera
 * screen, and seed fake recordings so the list, pipe choice and upload UI have something to show.
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
        column.addView(button("Open Recordings") { startActivity(Intent(this, SessionsActivity::class.java)) })
        column.addView(button("Add fake recording") { seed(complete = true) })
        column.addView(button("Add one recording in every cloud state") { seedEveryState() })
        column.addView(button("Add fake incomplete recording") { seed(complete = false) })
        column.addView(button("Delete ALL recordings") { confirmDeleteAll() })
        column.addView(button("Open camera screen (needs ARCore)") { startActivity(Intent(this, CaptureActivity::class.java)) })
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        val cloud = runCatching { CloudSync.get(this).config.enabled }.getOrDefault(false)
        info.text = "Cloud upload: ${if (cloud) "ON (${BuildConfig.CLOUD_BASE_URL})" else "OFF (no backend URL in this build)"}"
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    /** Writes a small, PoseCam-shaped session folder: every file the uploader requires, plus a few fake JPEGs. */
    private fun seed(complete: Boolean) {
        val now = System.currentTimeMillis()
        val id = PoseRecorder.newSessionId(now)
        val dir = File(File(getExternalFilesDir(null), "captures"), id).also { it.mkdirs() }
        val frames = 12
        File(dir, "manifest.json").writeText(
            """{"format_version":"posecam-5","session_id":"$id","start_wall_time_utc":"${PoseRecorder.isoUtc(now)}",""" +
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
        runCatching { CloudSync.get(this).recoverQueue() }
        Toast.makeText(this, "Added $id", Toast.LENGTH_SHORT).show()
    }

    /** Removes every recording folder and every queue row, so the next test starts from an empty list. */
    private fun confirmDeleteAll() {
        val captures = File(getExternalFilesDir(null), "captures")
        val count = captures.listFiles { f -> f.isDirectory }?.size ?: 0
        AlertDialog.Builder(this)
            .setTitle("Delete all recordings?")
            .setMessage("This removes $count recording folder(s) from this phone, including real ones, and clears the upload queue. Nothing in the cloud is touched. It cannot be undone.")
            .setPositiveButton("Delete all") { _, _ ->
                Thread {
                    val dao = UploadDatabase.get(this).uploadDao()
                    runBlocking {
                        dao.allSessions().forEach { dao.deleteUploadsForSession(it.sessionId); dao.deleteSession(it.sessionId) }
                    }
                    captures.listFiles()?.forEach { it.deleteRecursively() }
                    File(getExternalFilesDir(null), "upload-staging").deleteRecursively()
                    runOnUiThread { Toast.makeText(this, "All recordings deleted", Toast.LENGTH_SHORT).show() }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Writes straight into the upload queue database, so the Recordings screen shows every status without a
     * backend or S3. It deliberately does not start the upload worker (and `recoverQueue` is not called), so
     * the states stay put. Do not open the camera screen afterwards: it runs queue recovery, which may move them.
     */
    private fun seedEveryState() {
        Thread {
            val dao = UploadDatabase.get(this).uploadDao()
            val now = System.currentTimeMillis()
            val captures = File(getExternalFilesDir(null), "captures")
            var minute = 0

            fun session(
                pipe: String?,
                created: Boolean = true,
                syncedAt: Long? = null,
                error: String? = null,
                permanentFailure: Boolean = false,
                files: (String) -> List<UploadEntity>,
            ) {
                val at = now - (minute++) * 60_000L
                val id = PoseRecorder.newSessionId(at)
                val dir = File(captures, id).also { it.mkdirs() }
                File(dir, "manifest.json").writeText(
                    """{"format_version":"posecam-5","session_id":"$id","start_wall_time_utc":"${PoseRecorder.isoUtc(at)}",""" +
                        """"complete":true,"frame_count":12}""",
                )
                runBlocking {
                    dao.insertSessionIgnore(
                        CloudSessionEntity(
                            sessionId = id, directoryPath = dir.absolutePath, recordingStatus = "complete", recordingFinal = true,
                            cloudCreated = created, permanentFailure = permanentFailure, syncedAt = syncedAt,
                            lastError = error, createdAt = at, updatedAt = at, pipe = pipe,
                        ),
                    )
                    files(id).forEach { dao.insertUploadIgnore(it) }
                }
            }

            fun row(
                id: String, path: String, type: UploadFileType, size: Long, state: UploadState,
                sent: Long = 0, error: String? = null, items: Int = 0,
            ) = UploadEntity(
                sessionId = id, relativePath = path, localPath = File(captures, "$id/$path").absolutePath, fileType = type,
                kind = if (type == UploadFileType.FRAME_CHUNK) UploadSourceKind.FRAME_CHUNK else UploadSourceKind.PLAIN,
                itemCount = items, sizeBytes = size, state = state, uploadedBytes = sent, lastError = error,
                createdAt = now, updatedAt = now,
            )

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

            val V = UploadState.VERIFIED
            session(pipe = null, created = false) { emptyList() }                                     // choose a pipe
            session(pipe = "white") { fileSet(it, listOf(UploadState.PENDING, UploadState.PENDING, UploadState.PENDING, UploadState.PENDING)) }
            session(pipe = "black") {                                                                // uploading
                fileSet(it, listOf(V, V, UploadState.UPLOADING, UploadState.PENDING), sent = listOf(2_000, 400_000, 1_500_000, 0))
            }
            session(pipe = "white") { fileSet(it, listOf(V, V, V, UploadState.UPLOADED), sent = listOf(2_000, 400_000, 3_000_000, 30_000_000)) } // verifying
            session(pipe = "black", syncedAt = now) { fileSet(it, listOf(V, V, V, V)) }              // synced
            session(pipe = "white") {                                                                // failed
                fileSet(it, listOf(V, V, UploadState.FAILED, UploadState.PENDING), error = "The server rejected imu.csv (HTTP 403)")
            }
            runOnUiThread { Toast.makeText(this, "Added one recording per cloud state", Toast.LENGTH_LONG).show() }
        }.start()
    }
}
