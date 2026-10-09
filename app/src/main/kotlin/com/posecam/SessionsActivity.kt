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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.posecam.core.sync.CloudSync
import com.posecam.core.sync.CloudSyncSettings
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Lists recordings and lets the user share, export or delete them (sharing only without cloud upload). Recordings live in
 * app-specific storage, which file managers cannot browse on Android 11+, so Share and
 * Save to Downloads are the ways to get data off a phone without adb.
 */
class SessionsActivity : Activity() {

    /** [title] is the recording's name, [meta] what it holds: "59 s · 1753 frames · 52 MB". */
    private class Row(val dir: File, val title: String, val meta: String)

    private lateinit var captures: File
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rows: List<Row> = emptyList()

    /** Null unless cloud upload is configured (a backend URL was built in): then this screen is exactly as before. */
    private var cloud: SessionsCloudUi? = null
    private var listAdapter: RowAdapter? = null
    private var baseHeader = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sessions)
        captures = File(getExternalFilesDir(null), "captures")
        keepBelowSystemBars()
        findViewById<ListView>(R.id.list).setOnItemClickListener { _, _, position, _ -> showActions(rows[position]) }
        cloud = runCatching { CloudSync.get(this).takeIf { it.config.enabled } }.getOrNull()
            ?.let { SessionsCloudUi(this, it, ::updateCloudLabels, ::refresh) }
        cloud?.requestNotificationPermissionOnce()
    }

    /** The gear in the header exists only when cloud upload is configured. */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (cloud != null) menuInflater.inflate(R.menu.sessions, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_cloud_settings) {
            cloud?.showSettings()
            return true
        }
        if (item.itemId == R.id.action_my_uploads) {
            startActivity(Intent(this, UploadStatsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        cloud?.start()
    }

    override fun onStop() {
        cloud?.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Recordings already safe in the cloud can be removed when the phone is short on space.
        cloud?.reclaimStorage()
        refresh()
    }

    private fun refresh() {
        val dirs = captures.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        rows = dirs.map { describe(it) }
        val adapter = RowAdapter()
        listAdapter = adapter
        findViewById<ListView>(R.id.list).adapter = adapter
        val free = captures.parentFile?.let { it.usableSpace / 1e9 } ?: 0.0
        baseHeader = "%d recordings · %.1f GB · %.1f GB free".format(
            rows.size, rows.sumOf { SessionZipper.sizeOf(it.dir) } / 1e9, free)
        findViewById<TextView>(R.id.header).text = baseHeader
        findViewById<TextView>(R.id.empty).apply {
            text = getString(R.string.no_recordings, captures.path)
            visibility = if (rows.isEmpty()) TextView.VISIBLE else TextView.GONE
        }
    }

    /** Cloud state changed: redraw the rows, keeping the list's scroll position. */
    private fun updateCloudLabels() {
        listAdapter?.notifyDataSetChanged()
        findViewById<TextView>(R.id.header).text = baseHeader
    }

    /** A recording's row: name, what it holds, and (when cloud upload is on) its cloud status, icon and Retry button. */
    private inner class RowAdapter : android.widget.BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val root = convertView ?: layoutInflater.inflate(R.layout.row_session, parent, false)
            val row = rows[position]
            root.findViewById<TextView>(R.id.rowTitle).text = row.title
            root.findViewById<TextView>(R.id.rowMeta).text = row.meta
            val status = root.findViewById<TextView>(R.id.rowStatus)
            val icon = root.findViewById<android.widget.ImageView>(R.id.rowIcon)
            val retry = root.findViewById<View>(R.id.rowRetry)
            val ui = cloud
            if (ui == null) {
                status.visibility = View.GONE
                icon.visibility = View.GONE
                retry.visibility = View.GONE
            } else {
                status.visibility = View.VISIBLE
                status.text = ui.rowLine(row.dir.name)
                icon.visibility = View.VISIBLE
                icon.setImageResource(ui.rowIcon(row.dir.name))
                retry.visibility = if (ui.needsRetry(row.dir.name)) View.VISIBLE else View.GONE
                retry.setOnClickListener { ui.retry(row.dir.name) }
            }
            return root
        }
    }

    private fun describe(dir: File): Row {
        val manifest = File(dir, "manifest.json").takeIf { it.exists() }?.readText() ?: ""
        val frames = Regex("\"frame_count\": (\\d+)").find(manifest)?.groupValues?.get(1)?.toLongOrNull()
        val fps = Regex("\"measured_fps\": ([0-9.]+)").find(manifest)?.groupValues?.get(1)?.toDoubleOrNull()
        val complete = manifest.contains("\"complete\": true")
        val jumps = Regex("\"pose_jumps\": \\{\\s*\"count\": (\\d+)").find(manifest)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = if (frames != null && fps != null && fps > 0) frames / fps else null
        val meta = listOfNotNull(
            seconds?.let { "%.0f s".format(it) },
            frames?.let { "$it frames" },
            "%.0f MB".format(SessionZipper.sizeOf(dir) / 1e6),
            "$jumps pose jump${if (jumps == 1) "" else "s"}".takeIf { jumps > 0 },
            "incomplete".takeIf { !complete },
        ).joinToString(" · ")
        return Row(dir, dir.name.removePrefix("capture-"), meta)
    }

    /**
     * With cloud upload on, the app is how recordings leave the phone. Offering "Share" / "Save to Downloads" as well would
     * invite sending the same recording through the old manual (Drive) route, which makes the downstream pipeline redo
     * work the upload already did. Without cloud upload nothing changes: those are the only ways off the phone.
     */
    private val offersFileSharing: Boolean get() = cloud == null

    private fun showActions(row: Row) {
        val actions = mutableListOf(getString(R.string.export_pipeline))
        if (offersFileSharing) {
            actions.add(getString(R.string.share))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) actions.add(getString(R.string.save_to_downloads))
        }
        actions.add(getString(R.string.delete))
        // Cloud entries come first and only exist when cloud upload is on; the original entries are untouched.
        val cloudItems = cloud?.actionsFor(row.dir.name).orEmpty()
        val labels = cloudItems.map { it.label } + actions
        AlertDialog.Builder(this)
            .setTitle(row.title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < cloudItems.size) {
                    cloudItems[which].run()
                    return@setItems
                }
                when (actions[which - cloudItems.size]) {
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
        // The same value Cloud sync settings edits (and the automatic exports use): one place, one answer.
        val prefs = getSharedPreferences(CaptureActivity.PREFS, MODE_PRIVATE)
        val current = prefs.getInt(CloudSyncSettings.KEY_EXPORT_ROTATION, 0)
        ExportRotationDialog.show(this, current, getString(R.string.export_pipeline)) { rotation ->
            prefs.edit().putInt(CloudSyncSettings.KEY_EXPORT_ROTATION, rotation).apply()
            runExport(dir, rotation)
        }
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
            val next = if (offersFileSharing) "Send the export to whoever processes the data."
            else "This was a local check: recordings are exported and uploaded automatically once they are filed under a pipe."
            AlertDialog.Builder(this)
                .setTitle("Exported ${done.folders.size} recording(s)")
                .setMessage("${done.frames} frames, %.0f MB, rotation $rotation°.$skipped\n\n$next".format(done.bytes / 1e6))
                .apply {
                    if (offersFileSharing) {
                        setPositiveButton(R.string.share_export) { _, _ -> shareZip(root, "${root.name}.zip") }
                        setNeutralButton(R.string.close, null)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setNegativeButton(R.string.save_export_to_downloads) { _, _ ->
                                saveZipToDownloads(root, "${root.name}.zip")
                            }
                        }
                    } else {
                        setPositiveButton(R.string.close, null)
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
            .setMessage(cloud?.deleteWarning(dir.name) ?: "This cannot be undone. Make sure it has been shared or saved first.")
            .setPositiveButton(R.string.delete) { _, _ ->
                dir.deleteRecursively()
                cloud?.onDeleted(dir.name)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
