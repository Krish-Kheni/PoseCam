package com.posecam.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SampleUploadsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val now = 1_791_547_200_000L // 2026-10-09T12:00:00Z

    @Test fun theHistoryHasAHundredADayAndItsCountsAddUp() {
        val (stats, recordings) = SampleUploads.fullHistory(now, utc)

        assertEquals(100, stats.days.first().recordings)
        assertEquals(recordings.size, stats.totalRecordings)
        assertEquals(stats.days.sumOf { it.recordings }, recordings.size)
        for (day in stats.days) {
            assertEquals(day.recordings, recordings.count { it.date == day.date })
            assertEquals(day.recordings, day.pipes.values.sum())
            assertEquals(day.recordings, day.live + day.publishing + day.failed)
        }
        assertEquals(recordings.size, recordings.map { it.sessionId }.toSet().size)
        assertTrue(stats.days.zipWithNext().all { (a, b) -> a.date > b.date })
    }

    @Test fun todayHasSomethingStillPublishingAndSomethingFailed() {
        val today = SampleUploads.fullHistory(now, utc).first.days.first()
        assertTrue(today.publishing > 0 && today.failed > 0)
    }

    @Test fun mixesYesterdayAndTheDayBeforeIntoTheRealDays() {
        val real = UploadStats("a@b.co", 2, 2, null, listOf(DayCount("2026-10-09", 2, mapOf("white" to 2), live = 2)))

        val mixed = SampleUploads.mixedInto(real, now, utc)

        assertEquals(listOf("2026-10-09", "2026-10-08", "2026-10-07"), mixed.days.map { it.date })
        assertEquals(listOf(2, 100, 87), mixed.days.map { it.recordings })
        assertEquals(2 + 187, mixed.totalRecordings)
        assertEquals(2, mixed.uploadedToday)
    }

    @Test fun aSampleDaysListMatchesItsCount() {
        val mixed = SampleUploads.mixedInto(UploadStats("a@b.co", 0, 0, null, emptyList()), now, utc)

        for (day in mixed.days) assertEquals(day.recordings, SampleUploads.recordingsOn(day.date, now, utc).size)
        assertTrue(SampleUploads.recordingsOn("2020-01-01", now, utc).isEmpty())
    }
}
