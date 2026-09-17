package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class PoseRecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t = floatArrayOf(0f, 0f, 0f)
    private val q = floatArrayOf(0f, 0f, 0f, 1f)
    private val fakeJpeg = FrameEncoder { _, out -> out.writeBytes(byteArrayOf(1)) }

    private fun recorder(poolSize: Int = 8) = PoseRecorder(tmp.root, fakeJpeg, poolSize = poolSize)

    private fun PoseRecorder.captured(timestampNs: Long): FrameImage {
        val buffer = pool.tryAcquire() ?: return FrameImage.Dropped(FrameImage.QUEUE_FULL)
        buffer.set(2, 2, timestampNs, ByteBuffer.wrap(ByteArray(4)), ByteBuffer.wrap(ByteArray(1)), ByteBuffer.wrap(ByteArray(1)), 2, 1, 1)
        return FrameImage.Captured(buffer)
    }

    @Test
    fun writesPosesFramesAndManifest() {
        val recorder = recorder()
        val dir = recorder.start(mapOf("app_version" to "0.1.0"), device = mapOf("model" to "SM-G781B"))
        assertTrue(dir.name.matches(Regex("capture-\\d{8}T\\d{6}-[0-9a-f]{6}")))
        assertTrue(File(dir, "manifest.json").readText().contains("\"complete\": false"))

        assertTrue(recorder.wantsIntrinsics())
        recorder.onIntrinsics(Intrinsics(500f, 500f, 320f, 240f, 640, 480))
        assertTrue(File(dir, "intrinsics.json").readText().contains("\"complete\": false"))
        assertFalse(recorder.wantsIntrinsics())
        recorder.onFrame(100, "TRACKING", t, q, recorder.captured(100))
        recorder.onFrame(133, "PAUSED:INSUFFICIENT_FEATURES", null, null, FrameImage.Dropped(FrameImage.NOT_YET_AVAILABLE))
        recorder.onFrame(166, "TRACKING", t, q, recorder.captured(166))
        val summary = recorder.stop(extra = mapOf("imu" to mapOf("accel" to mapOf("samples" to 42))))!!

        assertEquals(3, summary.frameCount)
        assertEquals(2, summary.imagesSaved)
        assertEquals(1, summary.imagesDropped)
        val lines = File(dir, "poses.csv").readLines()
        assertEquals(PoseCsv.HEADER, lines[0])
        assertEquals(listOf("0", "1", "2"), lines.drop(1).map { it.substringBefore(',') })
        assertEquals("1,133,,,,,,,,PAUSED:INSUFFICIENT_FEATURES,dropped:not_yet_available", lines[2])
        assertEquals(listOf("000000_100.jpg", "000002_166.jpg"), File(dir, "frames").list()!!.sorted())

        val manifest = File(dir, "manifest.json").readText()
        assertTrue(manifest.contains("\"complete\": true"))
        assertTrue(manifest.contains("\"format_version\": \"posecam-3\""))
        assertTrue(manifest.contains("\"samples\": 42"))
        assertTrue(manifest.contains("\"intrinsics_changed_during_recording\": false"))
        assertTrue(File(dir, "device.json").readText().contains("\"model\": \"SM-G781B\""))
        val intrinsics = File(dir, "intrinsics.json").readText()
        assertTrue(intrinsics.contains("\"complete\": true"))
        assertTrue(intrinsics.contains("\"fx\": 500.0"))
        assertTrue(manifest.contains("\"frame_count\": 3"))
        assertTrue(manifest.contains("\"tracked_frame_count\": 2"))
        assertTrue(manifest.contains("\"written\": 2"))
        assertTrue(manifest.contains("\"not_yet_available\": 1"))
        assertEquals(8, recorder.pool.available)
    }

    @Test
    fun ignoresRepeatedTimestampsFromFastRenderLoop() {
        val recorder = recorder()
        recorder.start(emptyMap())
        recorder.onFrame(100, "TRACKING", t, q, recorder.captured(100))
        assertFalse(recorder.wantsFrame(100))
        recorder.onFrame(100, "TRACKING", t, q, recorder.captured(100))
        assertTrue(recorder.wantsFrame(133))
        recorder.onFrame(133, "TRACKING", t, q, recorder.captured(133))
        assertEquals(2, recorder.stop()!!.frameCount)
        assertEquals(8, recorder.pool.available)
    }

    @Test
    fun framesOutsideRecordingAreDroppedAndBuffersReturned() {
        val recorder = recorder()
        assertFalse(recorder.wantsFrame(1))
        recorder.onFrame(1, "TRACKING", t, q, recorder.captured(1))
        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())
        assertEquals(0, tmp.root.listFiles()!!.size)
        assertEquals(8, recorder.pool.available)
    }

    @Test
    fun exhaustedPoolIsRecordedAsDropNotBlock() {
        val recorder = recorder(poolSize = 1)
        val dir = recorder.start(emptyMap())
        val held = recorder.pool.tryAcquire()!! // simulate the writer being busy
        recorder.onFrame(100, "TRACKING", t, q, recorder.captured(100))
        recorder.pool.release(held)
        val summary = recorder.stop()!!
        assertEquals(1, summary.imagesDropped)
        assertTrue(File(dir, "poses.csv").readLines()[1].endsWith(",dropped:queue_full"))
    }

    @Test
    fun flushesPeriodicallySoAKilledAppLosesLittle() {
        val recorder = recorder()
        val dir = recorder.start(emptyMap())
        repeat(250) { recorder.onFrame(it.toLong() + 1, "TRACKING", t, q, FrameImage.Dropped("test")) }
        val onDisk = File(dir, "poses.csv").readLines().size - 1
        assertTrue("expected >= 200 rows flushed, got $onDisk", onDisk >= 200)
        recorder.stop()
    }
}
