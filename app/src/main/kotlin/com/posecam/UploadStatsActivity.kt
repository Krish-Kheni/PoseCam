package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.posecam.core.cloud.AuthValidation
import com.posecam.core.cloud.SampleUploads
import com.posecam.core.cloud.UploadStats
import com.posecam.core.cloud.UploadStatsCache
import com.posecam.core.cloud.UploadStatsText
import com.posecam.core.sync.CloudSync
import com.posecam.core.sync.Pipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * "My uploads": how many recordings this collector has uploaded, in total and day by day. A day is one short row (its
 * count and how it splits across pipes), because at a hundred recordings a day a flat list of recordings is unreadable:
 * tapping a day, or picking a date, opens that day's recordings ([DayUploadsActivity]).
 *
 * The numbers come from the server, which counts a recording once it is fully uploaded, so deleting recordings from the
 * phone (by hand, with "Delete synced", or by storage cleanup) never lowers them. The last answer is kept for when there
 * is no signal.
 */
class UploadStatsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sync: CloudSync
    private lateinit var cache: UploadStatsCache

    private lateinit var name: TextView
    private lateinit var account: TextView
    private lateinit var total: TextView
    private lateinit var today: TextView
    private lateinit var note: TextView
    private lateinit var days: ListView
    private lateinit var empty: TextView
    private var firstDay: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload_stats)
        keepBelowSystemBars()
        sync = CloudSync.get(this)
        cache = UploadStatsCache(this)
        name = findViewById(R.id.statsName)
        account = findViewById(R.id.statsAccount)
        total = findViewById(R.id.statsTotal)
        today = findViewById(R.id.statsToday)
        note = findViewById(R.id.statsNote)
        days = findViewById(R.id.statsDays)
        empty = findViewById(R.id.statsEmpty)
        findViewById<Button>(R.id.statsSignOut).setOnClickListener { confirmSignOut() }
        findViewById<Button>(R.id.statsPickDate).setOnClickListener { pickDate() }
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load() {
        // Debug builds can show sample data (see the dev menu); nothing in a release build ever sets this extra.
        intent.getStringExtra(EXTRA_PREVIEW)?.let(UploadStatsCache.Companion::decode)?.let { preview ->
            account.text = "Preview with sample data"
            name.text = "Sample Collector"
            name.visibility = View.VISIBLE
            show(preview.stats, null)
            return
        }
        val session = sync.auth.current().session
        if (session == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        account.text = session.email
        name.text = session.name
        name.visibility = if (session.name.isNullOrBlank()) View.GONE else View.VISIBLE
        cache.load(session.email)?.let { show(it.stats, "Saved copy \u00b7 ${UploadStatsText.age(it.savedAtMs, System.currentTimeMillis()) ?: "just now"}") }
            ?: run { showNote("Loading\u2026") }
        scope.launch {
            try {
                val offset = UploadStatsText.tzOffsetMinutes()
                val stats = withContext(Dispatchers.IO) { sync.accounts.myStats(offset) }
                cache.save(stats, System.currentTimeMillis())
                // Debug builds can mix made-up past days into the real list (dev menu switch); never in a release build.
                if (BuildConfig.DEBUG && getSharedPreferences(DEBUG_PREFS, MODE_PRIVATE).getBoolean(KEY_SAMPLE_DAYS, false)) {
                    show(SampleUploads.mixedInto(stats, System.currentTimeMillis()), "Sample days are mixed in (debug)")
                } else {
                    show(stats, null)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // A token the server refused has already signed the collector out (see UserAuthProvider).
                if (!sync.auth.isSignedIn) {
                    startActivity(Intent(this@UploadStatsActivity, AuthActivity::class.java))
                    finish()
                    return@launch
                }
                val saved = cache.load(session.email)
                showNote(
                    if (saved != null) {
                        "Offline \u00b7 saved copy from ${UploadStatsText.age(saved.savedAtMs, System.currentTimeMillis()) ?: "a moment ago"}"
                    } else {
                        AuthValidation.messageFor(failure)
                    },
                )
            }
        }
    }

    private fun showNote(text: String?) {
        note.text = text
        note.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun show(stats: UploadStats, footnote: String?) {
        total.text = stats.totalRecordings.toString()
        today.text = stats.uploadedToday.toString()
        showNote(footnote)
        firstDay = stats.days.lastOrNull()?.date
        val catalog = runCatching { sync.pipes.current() }.getOrDefault(emptyList())
        val rows = UploadStatsText.dayRows(stats, UploadStatsText.today()) { wire -> Pipe.fromWire(wire, catalog)?.label }
        days.adapter = DayAdapter(rows)
        days.setOnItemClickListener { _, _, position, _ -> openDay(rows[position].date) }
        days.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDay(date: String) {
        val open = Intent(this, DayUploadsActivity::class.java).putExtra(DayUploadsActivity.EXTRA_DATE, date)
        // A preview has no server to ask for the day, so it hands the sample recordings along.
        intent.getStringExtra(EXTRA_PREVIEW_RECORDINGS)?.let { open.putExtra(EXTRA_PREVIEW_RECORDINGS, it) }
        startActivity(open)
    }

    /** Jump to any date, including one with nothing on it (the day's screen then says so). */
    private fun pickDate() {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            openDay("%04d-%02d-%02d".format(Locale.US, year, month + 1, day))
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = now.timeInMillis
            firstDay?.let { first ->
                val parts = first.split("-").mapNotNull { it.toIntOrNull() }
                if (parts.size == 3) datePicker.minDate = Calendar.getInstance().apply { set(parts[0], parts[1] - 1, parts[2], 0, 0, 0) }.timeInMillis
            }
        }.show()
    }

    /** Day heading and count on one line, its pipe split under it, and what is not live yet under that. */
    private inner class DayAdapter(private val rows: List<UploadStatsText.DayRow>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val root = (convertView as? LinearLayout) ?: LinearLayout(this@UploadStatsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(TextView(context).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD); tag = "title" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(context).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD); tag = "count" })
                        addView(TextView(context).apply { text = "  \u203A"; textSize = 16f; alpha = 0.4f })
                    },
                )
                addView(TextView(context).apply { textSize = 12f; alpha = 0.65f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "pipes" })
                addView(TextView(context).apply { textSize = 12f; tag = "attention" })
            }
            val row = rows[position]
            root.findViewWithTag<TextView>("title").text = row.title
            root.findViewWithTag<TextView>("count").text = row.count.toString()
            root.findViewWithTag<TextView>("pipes").text = row.pipes
            root.findViewWithTag<TextView>("attention").apply {
                text = row.attention
                setTextColor(if (row.hasFailed) 0xFFC62828.toInt() else 0xFF8D6E00.toInt())
                visibility = if (row.attention.isEmpty()) View.GONE else View.VISIBLE
            }
            return root
        }
    }

    private fun confirmSignOut() {
        if (intent.hasExtra(EXTRA_PREVIEW)) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("Recordings not yet uploaded stay on this phone and upload to the next account that signs in.")
            .setPositiveButton("Sign out") { _, _ ->
                sync.signOut()
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /** Stats as [UploadStatsCache] encodes them: shown instead of asking the server. Used by the debug dev menu. */
        const val EXTRA_PREVIEW = "preview_stats"

        /** The preview's recordings ([UploadStatsCache.encodeRecordings]), handed on to a day's screen. */
        const val EXTRA_PREVIEW_RECORDINGS = "preview_recordings"

        /** Debug-only switch (set by the dev menu): add sample past days to the real list. */
        const val DEBUG_PREFS = "posecam_debug"
        const val KEY_SAMPLE_DAYS = "sample_past_days"
    }
}
