package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImuRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun rowsHaveFixedColumnsWithEmptyBiasForCalibratedSensors() {
        val columns = ImuRecorder.HEADER.split(",").size
        val accel = ImuRecorder.row(5, "accel", floatArrayOf(0.1f, 9.8f, 0.2f, 99f), 3)
        val gyro = ImuRecorder.row(6, "gyro_uncal", floatArrayOf(1f, 2f, 3f, 0.01f, 0.02f, 0.03f), 6)
        assertEquals("5,accel,0.1,9.8,0.2,,,", accel)
        assertEquals("6,gyro_uncal,1.0,2.0,3.0,0.01,0.02,0.03", gyro)
        assertEquals(columns, accel.split(",").size)
        assertEquals(columns, gyro.split(",").size)
    }

    @Test
    fun recordsOnlyWhileStartedAndReportsRates() {
        val recorder = ImuRecorder(preRollNs = 0)
        recorder.start(tmp.root)
        for (i in 0..200) recorder.onSample(1_000_000_000L + i * 5_000_000L, "accel", floatArrayOf(0f, 9.8f, 0f), 3)
        recorder.onSample(1_000_000_000L, "gyro_uncal", FloatArray(6), 6)
        @Suppress("UNCHECKED_CAST")
        val stats = recorder.stop()!! as Map<String, Map<String, Any?>>

        assertEquals(201L, stats["accel"]!!["samples"])
        assertEquals(200.0, stats["accel"]!!["mean_rate_hz"])
        assertEquals(1L, stats["gyro_uncal"]!!["samples"])
        assertEquals(203, File(tmp.root, "imu.csv").readLines().size)
        assertNull(recorder.stop())
    }

    @Test
    fun startWritesTheLastSecondOfIdleSamplesFirst() {
        val second = 1_000_000_000L
        val recorder = ImuRecorder(preRollNs = second)
        for (i in 0..30) recorder.onSample(i * 100_000_000L, "accel", floatArrayOf(i.toFloat(), 0f, 0f), 3)
        recorder.start(tmp.root)
        recorder.onSample(31 * 100_000_000L, "accel", floatArrayOf(31f, 0f, 0f), 3)
        recorder.stop()

        val timestamps = File(tmp.root, "imu.csv").readLines().drop(1).map { it.substringBefore(',').toLong() }
        // Idle samples within 1 s of the newest (2.0..3.0 s), then the live one.
        assertEquals((20..31).map { it * 100_000_000L }, timestamps)
    }
}
