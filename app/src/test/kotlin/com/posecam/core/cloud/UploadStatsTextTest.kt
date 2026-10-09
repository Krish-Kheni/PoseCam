package com.posecam.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class UploadStatsTextTest {
    @Test fun todayAndYesterdayAreNamed() {
        assertEquals("Today", UploadStatsText.dayLabel("2026-10-09", "2026-10-09"))
        assertEquals("Yesterday", UploadStatsText.dayLabel("2026-10-08", "2026-10-09"))
    }

    @Test fun yesterdayCrossesMonthAndYearBoundaries() {
        assertEquals("Yesterday", UploadStatsText.dayLabel("2026-09-30", "2026-10-01"))
        assertEquals("Yesterday", UploadStatsText.dayLabel("2025-12-31", "2026-01-01"))
    }

    @Test fun olderDaysShowTheirDate() {
        val label = UploadStatsText.dayLabel("2026-10-05", "2026-10-09")
        assertEquals(true, label.contains("5") && label.contains("2026"))
    }

    @Test fun anUnreadableDateIsShownAsIs() = assertEquals("garbage", UploadStatsText.dayLabel("garbage", "2026-10-09"))

    @Test fun todayFollowsTheGivenTimeZone() {
        val noonUtc = 1_791_547_200_000L // 2026-10-09T12:00:00Z
        assertEquals("2026-10-09", UploadStatsText.today(noonUtc, TimeZone.getTimeZone("UTC")))
        // 22:00Z on the 9th is already 03:30 on the 10th in India.
        assertEquals("2026-10-10", UploadStatsText.today(noonUtc + 10 * 3_600_000L, TimeZone.getTimeZone("Asia/Kolkata")))
    }

    @Test fun theOffsetIsMinutesEastOfUtc() {
        assertEquals(330, UploadStatsText.tzOffsetMinutes(0, TimeZone.getTimeZone("Asia/Kolkata")))
        assertEquals(-300, UploadStatsText.tzOffsetMinutes(1_791_547_200_000L, TimeZone.getTimeZone("America/Bogota")))
    }

    @Test fun countsArePluralised() {
        assertEquals("1 recording", UploadStatsText.recordings(1))
        assertEquals("0 recordings", UploadStatsText.recordings(0))
        assertEquals("7 recordings", UploadStatsText.recordings(7))
    }

    @Test fun ageOfASavedCopy() {
        assertNull(UploadStatsText.age(1_000, 30_000))
        assertEquals("5 min ago", UploadStatsText.age(0, 5 * 60_000))
        assertEquals("3 h ago", UploadStatsText.age(0, 3 * 3_600_000L))
        assertEquals("2 days ago", UploadStatsText.age(0, 2 * 86_400_000L))
        assertNotNull(UploadStatsText.age(0, 60_000))
    }

    // ---- the day list and a day's recordings -----------------------------------------------------

    private fun rec(id: String, date: String, pipe: String = "white", label: String = "White pipes", status: String = "live") =
        UploadedRecording(id, pipe, label, "2026-10-09T10:00:00.000Z", date, status)

    @Test fun eachDayIsOneRowWithItsPipeSplitBiggestFirst() {
        val stats = UploadStats(
            "a@b.co", 190, 100, null,
            listOf(
                DayCount("2026-10-09", 100, mapOf("black" to 35, "white" to 40, "pink" to 25), live = 94, publishing = 5, failed = 1),
                DayCount("2026-10-08", 90, mapOf("white" to 90), live = 90),
            ),
        )

        val rows = UploadStatsText.dayRows(stats, "2026-10-09") { wire -> if (wire == "white") "White pipes" else null }

        assertEquals("Today", rows[0].title)
        assertEquals(100, rows[0].count)
        assertEquals("White 40 \u00b7 black 35 \u00b7 pink 25", rows[0].pipes)
        assertEquals("5 publishing \u00b7 1 failed", rows[0].attention)
        assertEquals(true, rows[0].hasFailed)
        assertEquals("Yesterday", rows[1].title)
        assertEquals("", rows[1].attention) // a fully live day has nothing to flag
        assertEquals(false, rows[1].hasFailed)
    }

    @Test fun pipeNamesAreShortAndTiesKeepAStableOrder() {
        assertEquals("White", UploadStatsText.shortPipe("White pipes"))
        assertEquals("Black/White", UploadStatsText.shortPipe("Black/White pipes"))
        assertEquals("Pink", UploadStatsText.shortPipe("Pink Pipes"))
        assertEquals("a 2 \u00b7 b 2", UploadStatsText.pipeSplit(mapOf("b" to 2, "a" to 2)) { null })
    }

    @Test fun aRecordingShowsItsNameAndPipeAndStatus() {
        val r = rec("capture-20261009T154304-60c403", "2026-10-09", status = "publishing")
        assertEquals("20261009T154304-60c403", UploadStatsText.recordingName(r.sessionId))
        assertEquals("White pipe \u00b7 Publishing\u2026", UploadStatsText.recordingDetail(r) { null })
        assertEquals("Pink pipe \u00b7 Live", UploadStatsText.recordingDetail(rec("c", "d", "pink", "Pink Pipes")) { null })
    }

    @Test fun theDaySummaryDescribesTheWholeDay() {
        val day = List(98) { rec("c$it", "d") } + rec("p1", "d", status = "publishing") + rec("f1", "d", status = "publish_failed")
        assertEquals("100 recordings \u00b7 98 live \u00b7 1 publishing \u00b7 1 failed", UploadStatsText.daySummary(day))
        assertEquals("1 recording \u00b7 1 live", UploadStatsText.daySummary(listOf(rec("c", "d"))))
    }

    @Test fun everyStatusHasWording() {
        assertEquals("Live", UploadStatsText.status("live"))
        assertEquals("Publish failed", UploadStatsText.status("publish_failed"))
        assertEquals("Uploaded", UploadStatsText.status("something new"))
    }
}
