package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMetadataTest {
    private val columns = FrameMetadata.HEADER.split(",").size

    @Test
    fun rowLeavesMissingFieldsEmpty() {
        val row = FrameMetadata.row(4, 99, FrameMetadata(exposureTimeNs = 8_000_000, opticalStabilizationMode = 0))
        assertEquals("4,99,8000000,,,,,,0", row)
        assertEquals(columns, row.split(",").size)
        assertEquals("5,100,,,,,,,", FrameMetadata.row(5, 100, null))
    }

    @Test
    fun summaryReportsOisAndRanges() {
        val summary = FrameMetadataSummary()
        summary.add(FrameMetadata(exposureTimeNs = 10, focusDistanceDiopters = 0.5f, opticalStabilizationMode = 0))
        summary.add(FrameMetadata(exposureTimeNs = 30, focusDistanceDiopters = 0.5f, opticalStabilizationMode = 1))
        summary.add(null)
        val json = summary.toJson()
        assertEquals(3L, json["frames"])
        assertEquals(2L, json["frames_with_metadata"])
        assertEquals(listOf(0, 1), json["ois_modes_seen"])
        assertEquals(1L, json["frames_with_ois_on"])
        assertEquals(listOf(10L, 30L), json["exposure_time_ns_range"])
        assertEquals(listOf(0.5f, 0.5f), (json["focus_distance_diopters_range"] as FloatArray).toList())

        summary.reset()
        assertEquals(0L, summary.toJson()["frames"])
    }
}
