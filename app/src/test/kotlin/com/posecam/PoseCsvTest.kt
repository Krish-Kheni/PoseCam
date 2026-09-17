package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Test

class PoseCsvTest {
    private val columns = PoseCsv.HEADER.split(",").size

    @Test
    fun trackedRowHasAllColumnsInOrder() {
        val row = PoseCsv.trackedRow(7, 1663341052123456789, floatArrayOf(1f, 2f, 3f), floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f))
        assertEquals("7,1663341052123456789,1.0,2.0,3.0,0.1,0.2,0.3,0.9,TRACKING", row)
        assertEquals(columns, row.split(",").size)
    }

    @Test
    fun untrackedRowKeepsColumnCountWithEmptyPose() {
        val row = PoseCsv.untrackedRow(3, 42, "PAUSED:INSUFFICIENT_FEATURES")
        val fields = row.split(",")
        assertEquals(columns, fields.size)
        assertEquals(listOf("3", "42"), fields.take(2))
        assertEquals(List(7) { "" }, fields.subList(2, 9))
        assertEquals("PAUSED:INSUFFICIENT_FEATURES", fields.last())
    }
}
