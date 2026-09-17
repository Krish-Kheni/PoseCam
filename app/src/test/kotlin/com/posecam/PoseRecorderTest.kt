package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PoseRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t = floatArrayOf(0f, 0f, 0f)
    private val q = floatArrayOf(0f, 0f, 0f, 1f)

    @Test
    fun writesHeaderRowsAndManifest() {
        val recorder = PoseRecorder(tmp.root)
        val dir = recorder.start(mapOf("device" to mapOf("model" to "SM-G781B")))
        assertTrue(dir.name.matches(Regex("capture-\\d{8}T\\d{6}-[0-9a-f]{6}")))
        assertTrue(File(dir, "manifest.json").readText().contains("\"complete\": false"))

        recorder.onFrame(100, "TRACKING", t, q)
        recorder.onFrame(133, "PAUSED:INSUFFICIENT_FEATURES", null, null)
        recorder.onFrame(166, "TRACKING", t, q)
        val summary = recorder.stop()!!

        assertEquals(3, summary.frameCount)
        val lines = File(dir, "poses.csv").readLines()
        assertEquals(PoseCsv.HEADER, lines[0])
        assertEquals(listOf("0", "1", "2"), lines.drop(1).map { it.substringBefore(',') })
        assertEquals("1,133,,,,,,,,PAUSED:INSUFFICIENT_FEATURES", lines[2])

        val manifest = File(dir, "manifest.json").readText()
        assertTrue(manifest.contains("\"complete\": true"))
        assertTrue(manifest.contains("\"frame_count\": 3"))
        assertTrue(manifest.contains("\"tracked_frame_count\": 2"))
        assertTrue(manifest.contains("\"first_timestamp_ns\": 100"))
        assertTrue(manifest.contains("\"last_timestamp_ns\": 166"))
        assertTrue(manifest.contains("\"model\": \"SM-G781B\""))
    }

    @Test
    fun ignoresRepeatedTimestampsFromFastRenderLoop() {
        val recorder = PoseRecorder(tmp.root)
        recorder.start(emptyMap())
        recorder.onFrame(100, "TRACKING", t, q)
        recorder.onFrame(100, "TRACKING", t, q)
        recorder.onFrame(133, "TRACKING", t, q)
        assertEquals(2, recorder.stop()!!.frameCount)
    }

    @Test
    fun framesOutsideRecordingAreDropped() {
        val recorder = PoseRecorder(tmp.root)
        recorder.onFrame(1, "TRACKING", t, q)
        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun flushesPeriodicallySoAKilledAppLosesLittle() {
        val recorder = PoseRecorder(tmp.root)
        val dir = recorder.start(emptyMap())
        repeat(250) { recorder.onFrame(it.toLong() + 1, "TRACKING", t, q) }
        val onDisk = File(dir, "poses.csv").readLines().size - 1
        assertTrue("expected >= 200 rows flushed, got $onDisk", onDisk >= 200)
        recorder.stop()
    }
}
