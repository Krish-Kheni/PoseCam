package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Lists recordings and lets the user share, export or delete them. Recordings live in
 * app-specific storage, which file managers cannot browse on Android 11+, so Share and
 * Save to Downloads are the ways to get data off a phone without adb.
 */
class SessionsActivity : Activity() {

    private class Row(val dir: File, val label: String)

    private companion object {
        const val PREF_ROTATION = "export_rotation_degrees"
    }

    private lateinit var captures: File
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        captures = File(getExternalFilesDir(null), "captures")
        findViewById<ListView>(R.id.list).setOnItemClickListener { _, _, position, _ -> showActions(rows[position]) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val dirs = captures.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        rows = dirs.map { Row(it, describe(it)) }
        findViewById<ListView>(R.id.list).adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.map { it.label })
        val free = captures.parentFile?.let { it.usableSpace / 1e9 } ?: 0.0
        findViewById<TextView>(R.id.header).text =
            "%d recording(s), %.1f GB used, %.1f GB free\nTap a recording for actions.".format(
                rows.size, rows.sumOf { SessionZipper.sizeOf(it.dir) } / 1e9, free)
        findViewById<TextView>(R.id.empty).apply {
            text = getString(R.string.no_recordings, captures.path)
            visibility = if (rows.isEmpty()) TextView.VISIBLE else TextView.GONE
        }
    }

    private fun describe(dir: File): String {
        val manifest = File(dir, "manifest.json").takeIf { it.exists() }?.readText() ?: ""
        val frames = Regex("\"frame_count\": (\\d+)").find(manifest)?.groupValues?.get(1)?.toLongOrNull()
        val fps = Regex("\"measured_fps\": ([0-9.]+)").find(manifest)?.groupValues?.get(1)?.toDoubleOrNull()
        val complete = manifest.contains("\"complete\": true")
        val jumps = Regex("\"pose_jumps\": \\{\\s*\"count\": (\\d+)").find(manifest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = if (frames != null && fps != null && fps > 0) frames / fps else null
        return buildString {
            append(dir.name.removePrefix("capture-"))
            append("\n")
            if (seconds != null) append("%.0f s, ".format(seconds))
            if (frames != null) append("$frames frames, ")
            append("%.0f MB".format(SessionZipper.sizeOf(dir) / 1e6))
            if (jumps > 0) append(", $jumps pose jump(s)")
            if (!complete) append(", INCOMPLETE")
        }
    }

    private fun showActions(row: Row) {
        val actions = mutableListOf(getString(R.string.export_pipeline), getString(R.string.share))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) actions.add(getString(R.string.save_to_downloads))
        actions.add(getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(row.dir.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    getString(R.string.export_pipeline) -> askRotationThenExport(row.dir)
                    getString(R.string.share) -> shareZip(row.dir, "${row.dir.name}.zip")
                    getString(R.string.save_to_downloads) -> saveZipToDownloads(row.dir, "${row.dir.name}.zip")
                    getString(R.string.delete) -> confirmDelete(row.dir)
                }
            }
            .show()
    }

    /**
     * The pipeline's gripper detector needs the jaws pointing up in the video, so the
     * rotation depends on how the phone sits on the mount. Asked once and remembered.
     */
    private fun askRotationThenExport(dir: File) {
        val prefs = getSharedPreferences(CaptureActivity.PREFS, MODE_PRIVATE)
        val values = intArrayOf(0, 90, 180, 270)
        val labels = arrayOf(
            "No rotation — phone mounted sideways (landscape)",
            "90° — phone mounted upright (portrait)",
            "180°",
            "270°",
        )
        val current = values.indexOf(prefs.getInt(PREF_ROTATION, 0)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Which way is up?")
            .setSingleChoiceItems(labels, current, null)
            .setPositiveButton(R.string.export_pipeline) { dialog, _ ->
                val rotation = values[(dialog as AlertDialog).listView.checkedItemPosition.coerceAtLeast(0)]
                prefs.edit().putInt(PREF_ROTATION, rotation).apply()
                runExport(dir, rotation)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runExport(dir: File, rotation: Int) {
        val root = File(File(cacheDir, "pipeline"), "${dir.name}-pipeline")
        var result: PipelineExporter.Result? = null
        withProgress("Exporting ${dir.name}", { setMessage ->
            setMessage("Reading poses…")
            result = PipelineExporter.export(dir, root, rotation, appVersion()) { stage, done, total ->
                setMessage("$stage\n$done / $total frames")
            }
        }) { error ->
            val done = result
            if (error != null || done == null) {
                AlertDialog.Builder(this)
                    .setTitle("Export failed")
                    .setMessage(error?.message ?: "Unknown error")
                    .setPositiveButton(R.string.close, null)
                    .show()
                return@withProgress
            }
            val skipped = if (done.skipped > 0) "\n${done.skipped} short stretch(es) skipped." else ""
            AlertDialog.Builder(this)
                .setTitle("Exported ${done.folders.size} recording(s)")
                .setMessage("${done.frames} frames, %.0f MB, rotation $rotation°.$skipped\n\nSend the export to whoever processes the data."
                    .format(done.bytes / 1e6))
                .setPositiveButton(R.string.share_export) { _, _ -> shareZip(root, "${root.name}.zip") }
                .setNeutralButton(R.string.close, null)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setNegativeButton(R.string.save_export_to_downloads) { _, _ ->
                            saveZipToDownloads(root, "${root.name}.zip")
                        }
                    }
                }
                .show()
        }
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Runs [work] off the main thread with a progress dialog; [done] runs on the main thread. */
    private fun withProgress(title: String, work: ((String) -> Unit) -> Unit, done: (Throwable?) -> Unit) {
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage("Starting…").setCancelable(false).show()
        executor.execute {
            val error = runCatching {
                work { message -> mainHandler.post { dialog.setMessage(message) } }
            }.exceptionOrNull()
            mainHandler.post {
                dialog.dismiss()
                done(error)
            }
        }
    }

    private fun shareZip(dir: File, zipName: String) {
        val shared = File(cacheDir, "shared").apply { mkdirs() }
        shared.listFiles()?.forEach { it.delete() } // previous zips; the share target has its own copy by now
        val zip = File(shared, zipName)
        withProgress("Zipping ${dir.name}", { setMessage ->
            FileOutputStream(zip).use { out ->
                SessionZipper.zip(dir, out) { n, total -> setMessage("$n / $total files") }
            }
        }) { error ->
            if (error != null) {
                Toast.makeText(this, "Zip failed: $error", Toast.LENGTH_LONG).show()
                return@withProgress
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PoseCam ${dir.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share recording"))
        }
    }

    /** Public Downloads/PoseCam/, visible in any file manager and over USB (MTP). */
    private fun saveZipToDownloads(dir: File, zipName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zipName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PoseCam")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Could not create the Downloads entry", Toast.LENGTH_LONG).show()
            return
        }
        withProgress("Saving ${dir.name} to Downloads", { setMessage ->
            resolver.openOutputStream(uri)!!.use { out ->
                SessionZipper.zip(dir, out) { n, total -> setMessage("$n / $total files") }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }) { error ->
            if (error != null) {
                resolver.delete(uri, null, null)
                Toast.makeText(this, "Save failed: $error", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Saved to Downloads/PoseCam/$zipName", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${dir.name}?")
            .setMessage("This cannot be undone. Make sure it has been shared or saved first.")
            .setPositiveButton(R.string.delete) { _, _ ->
                dir.deleteRecursively()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
