package com.posecam.core.cloud

import com.posecam.core.sync.Pipe
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Wording and day arithmetic for the "My uploads" screen. Pure, so it is tested without a phone. */
object UploadStatsText {
    private const val DAY = "yyyy-MM-dd"

    /** The phone's current offset from UTC in minutes east, which is how the server is asked to cut days. */
    fun tzOffsetMinutes(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Int =
        zone.getOffset(now) / 60_000

    /** `Today`, `Yesterday`, or `Fri 9 Oct 2026`. [today] is `yyyy-MM-dd` in the same time zone as [date]. */
    fun dayLabel(date: String, today: String): String {
        if (date == today) return "Today"
        if (date == shiftDay(today, -1)) return "Yesterday"
        val parsed = parse(date) ?: return date
        return utc("EEE d MMM yyyy", Locale.getDefault()).format(parsed)
    }

    fun today(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat(DAY, Locale.US).apply { timeZone = zone }.format(now)

    /** One row of the day list in "My uploads". */
    data class DayRow(
        val date: String,
        /** "Today", "Yesterday" or "Tue 6 Oct 2026". */
        val title: String,
        val count: Int,
        /** The day's split by pipe, biggest first: "White 40 \u00b7 Black 35 \u00b7 Pink 25". */
        val pipes: String,
        /** What is not live yet ("2 publishing \u00b7 1 failed"), or empty when the whole day is live. */
        val attention: String,
        /** True when something that day failed to publish: shown in red. */
        val hasFailed: Boolean,
    )

    /** The day list: one row per day, each with its pipe split. [pipeName] turns a pipe id into a short name. */
    fun dayRows(stats: UploadStats, today: String, pipeName: (wire: String) -> String?): List<DayRow> = stats.days.map { day ->
        DayRow(
            date = day.date,
            title = dayLabel(day.date, today),
            count = day.recordings,
            pipes = pipeSplit(day.pipes, pipeName),
            attention = listOfNotNull(
                "${day.publishing} publishing".takeIf { day.publishing > 0 },
                "${day.failed} failed".takeIf { day.failed > 0 },
            ).joinToString(" \u00b7 "),
            hasFailed = day.failed > 0,
        )
    }

    /** "White 40 \u00b7 Black 35": biggest first, ties by name, so the line never reorders itself between visits. */
    fun pipeSplit(pipes: Map<String, Int>, pipeName: (wire: String) -> String?): String =
        pipes.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(" \u00b7 ") { "${shortPipe(pipeName(it.key) ?: it.key)} ${it.value}" }

    /** "White pipes" / "White pipe" -> "White": in a list of pipes the word is noise. */
    fun shortPipe(label: String): String = Pipe.short(label).removeSuffix(" pipe").ifBlank { label }

    /** The second line of a recording in a day's list: "White pipe \u00b7 Live". */
    fun recordingDetail(r: UploadedRecording, pipeName: (wire: String) -> String?): String {
        val pipe = Pipe.short(pipeName(r.pipe) ?: r.pipeLabel.ifBlank { r.pipe })
        return listOf(pipe, status(r.status)).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
    }

    /** The name a recording is listed under: its folder name without the "capture-" prefix. */
    fun recordingName(sessionId: String): String = sessionId.removePrefix("capture-")

    /** The summary line over a day's list: "100 recordings \u00b7 98 live \u00b7 2 publishing". */
    fun daySummary(recordings: List<UploadedRecording>): String {
        val live = recordings.count { it.status == "live" }
        val failed = recordings.count { it.status == "publish_failed" }
        val publishing = recordings.size - live - failed
        return listOfNotNull(
            recordings(recordings.size),
            "$live live".takeIf { live > 0 },
            "$publishing publishing".takeIf { publishing > 0 },
            "$failed failed".takeIf { failed > 0 },
        ).joinToString(" \u00b7 ")
    }

    fun status(wire: String): String = when (wire) {
        "live" -> "Live"
        "publishing" -> "Publishing\u2026"
        "publish_failed" -> "Publish failed"
        else -> "Uploaded"
    }

    fun recordings(count: Int): String = if (count == 1) "1 recording" else "$count recordings"

    /** "Updated 5 min ago" for a cached answer, or null when it is under a minute old. */
    fun age(savedAtMs: Long, now: Long): String? {
        val minutes = (now - savedAtMs) / 60_000
        return when {
            minutes < 1 -> null
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    // A calendar day has no time zone: it is read and written as UTC midnight so shifting it never slides across a date.
    private fun utc(pattern: String, locale: Locale = Locale.US) =
        SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun parse(date: String) = runCatching { utc(DAY).parse(date) }.getOrNull()

    private fun shiftDay(date: String, days: Int): String? {
        val parsed = parse(date) ?: return null
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = parsed; add(Calendar.DAY_OF_MONTH, days) }
        return utc(DAY).format(calendar.time)
    }
}
