package com.posecam

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.posecam.core.cloud.AuthValidation
import com.posecam.core.cloud.SampleUploads
import com.posecam.core.cloud.UploadStatsCache
import com.posecam.core.cloud.UploadStatsText
import com.posecam.core.cloud.UploadedRecording
import com.posecam.core.sync.CloudSync
import com.posecam.core.sync.Pipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One day of "My uploads": every recording uploaded that day with its pipe and status. A day can hold a hundred, so the
 * pipe chips (and "Not live") narrow the list, and the summary line over it always describes the whole day.
 */
class DayUploadsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sync: CloudSync
    private lateinit var cache: UploadStatsCache

    private lateinit var summary: TextView
    private lateinit var chips: LinearLayout
    private lateinit var note: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView

    private lateinit var date: String
    private var recordings: List<UploadedRecording> = emptyList()
    private var filter: Filter = Filter.All
    private var pipeName: (String) -> String? = { null }

    private sealed interface Filter {
        data object All : Filter
        data object NotLive : Filter
        data class Pipe(val wire: String) : Filter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_uploads)
        keepBelowSystemBars()
        sync = CloudSync.get(this)
        cache = UploadStatsCache(this)
        summary = findViewById(R.id.daySummary)
        chips = findViewById(R.id.dayChips)
        note = findViewById(R.id.dayNote)
        list = findViewById(R.id.dayList)
        empty = findViewById(R.id.dayEmpty)
        date = intent.getStringExtra(EXTRA_DATE) ?: run { finish(); return }
        title = UploadStatsText.dayLabel(date, UploadStatsText.today())
        val catalog = runCatching { sync.pipes.current() }.getOrDefault(emptyList())
        pipeName = { wire -> com.posecam.core.sync.Pipe.fromWire(wire, catalog)?.label }
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
        // Preview (debug dev menu): the sample recordings arrive with the intent, no server involved.
        intent.getStringExtra(UploadStatsActivity.EXTRA_PREVIEW_RECORDINGS)?.let(UploadStatsCache.Companion::decodeRecordings)?.let { all ->
            show(all.filter { it.date == date }, null)
            return
        }
        val session = sync.auth.current().session ?: run { finish(); return }
        cache.loadDay(session.email, date)?.let { show(it, "Saved copy") } ?: showNote("Loading\u2026")
        scope.launch {
            try {
                val offset = UploadStatsText.tzOffsetMinutes()
                val fresh = withContext(Dispatchers.IO) { sync.accounts.uploadsOn(date, offset) }
                cache.saveDay(session.email, date, fresh)
                // Debug builds mix the same made-up days into the list as into the day counts (dev menu switch).
                val sample = if (BuildConfig.DEBUG && getSharedPreferences(UploadStatsActivity.DEBUG_PREFS, MODE_PRIVATE).getBoolean(UploadStatsActivity.KEY_SAMPLE_DAYS, false)) {
                    SampleUploads.recordingsOn(date, System.currentTimeMillis())
                } else {
                    emptyList()
                }
                show((fresh + sample).sortedByDescending { it.sessionId }, if (sample.isEmpty()) null else "Sample recordings are mixed in (debug)")
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (!sync.auth.isSignedIn) {
                    finish()
                    return@launch
                }
                if (recordings.isEmpty()) showNote(AuthValidation.messageFor(failure)) else showNote("Offline \u00b7 saved copy")
            }
        }
    }

    private fun showNote(text: String?) {
        note.text = text
        note.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun show(all: List<UploadedRecording>, footnote: String?) {
        recordings = all
        showNote(footnote)
        summary.text = if (all.isEmpty()) "Nothing uploaded this day" else UploadStatsText.daySummary(all)
        if (filter is Filter.Pipe && all.none { it.pipe == (filter as Filter.Pipe).wire }) filter = Filter.All
        renderChips()
        renderList()
    }

    private fun renderChips() {
        chips.removeAllViews()
        (chips.parent as View).visibility = if (recordings.size < 2) View.GONE else View.VISIBLE
        chip("All ${recordings.size}", Filter.All)
        val notLive = recordings.count { it.status != "live" }
        if (notLive > 0) chip("Not live $notLive", Filter.NotLive)
        recordings.groupingBy { it.pipe }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { (wire, count) -> chip("${UploadStatsText.shortPipe(pipeName(wire) ?: wire)} $count", Filter.Pipe(wire)) }
    }

    private fun chip(label: String, target: Filter) {
        val density = resources.displayMetrics.density
        val selected = filter == target
        chips.addView(
            TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (selected) Color.WHITE else 0xFF37474F.toInt())
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 100f
                    setColor(if (selected) 0xFF37474F.toInt() else 0xFFECEFF1.toInt())
                }
                setOnClickListener {
                    filter = target
                    renderChips()
                    renderList()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * density).toInt() },
        )
    }

    private fun renderList() {
        val shown = when (val f = filter) {
            Filter.All -> recordings
            Filter.NotLive -> recordings.filter { it.status != "live" }
            is Filter.Pipe -> recordings.filter { it.pipe == f.wire }
        }
        list.adapter = RecordingAdapter(shown)
        list.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class RecordingAdapter(private val items: List<UploadedRecording>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = resources.displayMetrics.density
            val root = (convertView as? LinearLayout) ?: LinearLayout(this@DayUploadsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                addView(TextView(context).apply { textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "name" })
                addView(TextView(context).apply { textSize = 12f; tag = "detail" })
            }
            val item = items[position]
            root.findViewWithTag<TextView>("name").text = UploadStatsText.recordingName(item.sessionId)
            root.findViewWithTag<TextView>("detail").apply {
                text = UploadStatsText.recordingDetail(item, pipeName)
                alpha = if (item.status == "publish_failed") 1f else 0.65f
                setTextColor(if (item.status == "publish_failed") 0xFFC62828.toInt() else 0xFF000000.toInt())
            }
            return root
        }
    }

    companion object {
        const val EXTRA_DATE = "date"
    }
}
