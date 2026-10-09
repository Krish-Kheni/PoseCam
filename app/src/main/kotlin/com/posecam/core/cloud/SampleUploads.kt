package com.posecam.core.cloud

import java.util.TimeZone

/**
 * Made-up upload history at the real volume (about a hundred recordings a day), for looking at the My uploads screens
 * with a full list. Used only by debug builds (the dev menu), and only ever shown: nothing here is sent to the server or
 * counted by it.
 */
object SampleUploads {
    private val LABELS = mapOf(
        "white" to "White pipes", "black" to "Black pipes", "black-white" to "Black/White pipes",
        "pink" to "Pink Pipes", "green" to "Green Pipes",
    )

    /** The pipes in the order a day cycles through them: roughly 30% white, 30% black, 20% pink, 10% green, 10% mixed. */
    private val CYCLE = listOf("white", "black", "pink", "white", "black", "green", "white", "black", "pink", "black-white")

    /** (days ago, recordings that day). Yesterday and the day before are the ones [mixedInto] adds to real data. */
    private val PLAN = listOf(0 to 100, 1 to 100, 2 to 87, 3 to 100, 5 to 64, 6 to 100, 9 to 100, 12 to 35)
    private val RECENT_AGO = setOf(1, 2)

    private fun dayRecordings(ago: Int, count: Int, now: Long, zone: TimeZone): List<UploadedRecording> {
        val date = UploadStatsText.today(now - ago * 86_400_000L, zone)
        return (0 until count).map { i ->
            val minutes = 8 * 60 + i * 5
            val stamp = date.replace("-", "") + "T%02d%02d00".format(minutes / 60, minutes % 60)
            val pipe = CYCLE[(i + ago) % CYCLE.size]
            // Most are live. Today's newest are still publishing and one failed; the day before has one failure too.
            val status = when {
                ago == 0 && i >= count - 6 -> "publishing"
                ago == 0 && i == count - 7 -> "publish_failed"
                ago == 1 && i >= count - 2 -> "publishing"
                ago == 2 && i == 40 -> "publish_failed"
                else -> "live"
            }
            UploadedRecording(
                sessionId = "capture-$stamp-%06x".format(0x5a0000 + ago * 1000 + i), pipe = pipe, pipeLabel = LABELS.getValue(pipe),
                uploadedAt = "${date}T${"%02d:%02d".format(minutes / 60, minutes % 60)}:00.000Z", date = date, status = status,
            )
        }.sortedByDescending { it.sessionId }
    }

    private fun summarize(date: String, recordings: List<UploadedRecording>) = DayCount(
        date = date,
        recordings = recordings.size,
        pipes = recordings.groupingBy { it.pipe }.eachCount(),
        live = recordings.count { it.status == "live" },
        publishing = recordings.count { it.status == "publishing" },
        failed = recordings.count { it.status == "publish_failed" },
    )

    private fun all(now: Long, zone: TimeZone, only: (Int) -> Boolean = { true }) =
        PLAN.filter { only(it.first) }.flatMap { (ago, count) -> dayRecordings(ago, count, now, zone) }

    /** A whole sample history, today included, for the screen that needs no sign-in. */
    fun fullHistory(now: Long, zone: TimeZone = TimeZone.getDefault()): Pair<UploadStats, List<UploadedRecording>> {
        val recordings = all(now, zone)
        val days = recordings.groupBy { it.date }.map { (date, list) -> summarize(date, list) }.sortedByDescending { it.date }
        val stats = UploadStats("sample@example.com", recordings.size, days.first().recordings, null, days)
        return stats to recordings
    }

    /** One sample day's recordings, newest first; empty for a date the sample history does not cover. */
    fun recordingsOn(date: String, now: Long, zone: TimeZone = TimeZone.getDefault()): List<UploadedRecording> =
        all(now, zone).filter { it.date == date }

    /** The sample recordings [mixedInto] adds: yesterday and the day before. */
    fun recentRecordings(now: Long, zone: TimeZone = TimeZone.getDefault()): List<UploadedRecording> = all(now, zone) { it in RECENT_AGO }

    /** [real] with the sample days added; a day that already exists keeps its real recordings and gains the sample ones. */
    fun mixedInto(real: UploadStats, now: Long, zone: TimeZone = TimeZone.getDefault()): UploadStats {
        val sample = recentRecordings(now, zone).groupBy { it.date }.map { (date, list) -> summarize(date, list) }
        val days = (real.days + sample).groupBy { it.date }.map { (date, parts) ->
            DayCount(
                date = date,
                recordings = parts.sumOf { it.recordings },
                pipes = parts.flatMap { it.pipes.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() },
                live = parts.sumOf { it.live },
                publishing = parts.sumOf { it.publishing },
                failed = parts.sumOf { it.failed },
            )
        }.sortedByDescending { it.date }
        return real.copy(totalRecordings = real.totalRecordings + sample.sumOf { it.recordings }, days = days)
    }
}
