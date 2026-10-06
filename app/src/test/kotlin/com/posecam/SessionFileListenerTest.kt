package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/**
 * The only change cloud upload makes to recording is these two calls. Everything else about a recording must be
 * identical with and without a listener, and a faulty listener must never affect it.
 */
class SessionFileListenerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t = floatArrayOf(0f, 0f, 0f)
    private val q = floatArrayOf(0f, 0f, 0f, 1f)
    private val fakeJpeg = FrameEncoder { _, out -> out.writeBytes(byteArrayOf(1)) }

    private class Recording : SessionFileListener {
        val events = mutableListOf<String>()
        var startedManifest: String? = null
        var finalizedManifest: String? = null
        var finalizedFrames: Int = -1
        var directory: File? = null

        override fun onSessionStarted(sessionId: String, directory: File) {
            events += "started:$sessionId"
            this.directory = directory
            startedManifest = File(directory, "manifest.json").readText()
        }

        override fun onSessionFinalized(sessionId: String, directory: File, recordingStatus: String) {
            events += "finalized:$sessionId:$recordingStatus"
            finalizedManifest = File(directory, "manifest.json").readText()
            finalizedFrames = File(directory, "frames").list().orEmpty().size
        }
    }

    private fun recorder(listener: SessionFileListener?, root: File = tmp.newFolder()) =
        PoseRecorder(root, fakeJpeg, listener = listener)

    private fun PoseRecorder.captured(ts: Long): FrameImage {
        val buffer = pool.tryAcquire() ?: return FrameImage.Dropped(FrameImage.QUEUE_FULL)
        buffer.set(2, 2, ts, ByteBuffer.wrap(ByteArray(4)), ByteBuffer.wrap(ByteArray(1)), ByteBuffer.wrap(ByteArray(1)), 2, 1, 1)
        return FrameImage.Captured(buffer)
    }

    private fun record(recorder: PoseRecorder, frames: Int = 5): PoseRecorder.Summary {
        recorder.start(mapOf("app_version" to "0.3.0"))
        for (i in 0 until frames) recorder.onFrame(100L + i * 33, "TRACKING", t, q, recorder.captured(100L + i * 33))
        return recorder.stop()!!
    }

    @Test
    fun theListenerHearsStartedOnceWithTheSessionIdAndAnInitialIncompleteManifest() {
        val listener = Recording()
        val rec = recorder(listener)

        val dir = rec.start(emptyMap())

        assertEquals(listOf("started:${dir.name}"), listener.events)
        assertSame(dir, listener.directory)
        // The folder is already self-describing when the listener is told: complete:false.
        assertTrue(listener.startedManifest!!.contains("\"complete\": false"))
        rec.stop()
    }

    @Test
    fun theListenerHearsFinalizedOnceAfterEveryFileIsWrittenAndClosed() {
        val listener = Recording()
        val rec = recorder(listener)
        val summary = record(rec, frames = 7)

        val id = summary.directory.name
        assertEquals(listOf("started:$id", "finalized:$id:complete"), listener.events)
        // At that moment the final manifest is on disk, and so are all the frames.
        assertTrue(listener.finalizedManifest!!.contains("\"complete\": true"))
        assertEquals(7, listener.finalizedFrames)
        assertEquals(7, summary.imagesSaved.toInt())
    }

    @Test
    fun stoppingWhenNotRecordingNotifiesNobody() {
        val listener = Recording()
        val rec = recorder(listener)

        assertNull(rec.stop())

        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun twoTakesAreEachAnnouncedOnceInOrder() {
        val listener = Recording()
        val rec = recorder(listener)
        val first = record(rec).directory.name
        val second = record(rec).directory.name

        assertEquals(
            listOf("started:$first", "finalized:$first:complete", "started:$second", "finalized:$second:complete"),
            listener.events,
        )
    }

    @Test
    fun aThrowingListenerNeverInterruptsStartOrStop() {
        val throwing = object : SessionFileListener {
            override fun onSessionStarted(sessionId: String, directory: File) = throw IllegalStateException("cloud is broken")
            override fun onSessionFinalized(sessionId: String, directory: File, recordingStatus: String) =
                throw NoClassDefFoundError("even an Error, e.g. a database that cannot open")
        }
        val rec = recorder(throwing)

        val summary = record(rec)

        assertEquals(5, summary.frameCount)
        assertEquals(5, summary.imagesSaved)
        assertTrue(File(summary.directory, "manifest.json").readText().contains("\"complete\": true"))
    }

    @Test
    fun aRecordingIsIdenticalWithAndWithoutAListener() {
        fun capture(listener: SessionFileListener?): Triple<List<String>, List<String>, String> {
            val summary = record(recorder(listener))
            val dir = summary.directory
            val poses = File(dir, "poses.csv").readLines()
            val frames = File(dir, "frames").list()!!.sorted().map { it.substringAfter('_') }
            // The manifest, minus what legitimately differs between two takes (ids, wall times, per-run timing).
            val manifest = File(dir, "manifest.json").readText().lines()
                .filterNot { Regex("session_id|wall_time|first_timestamp|last_timestamp|measured_fps|record_pressed").containsMatchIn(it) }
                .joinToString("\n")
            return Triple(poses.map { it.substringAfter(',').substringAfter(',') }, frames, manifest)
        }

        val without = capture(null)
        val with = capture(Recording())

        assertEquals(without.first, with.first) // identical pose rows
        assertEquals(without.second.size, with.second.size) // identical number of frames
        assertEquals(without.third, with.third) // identical manifest content
    }

    @Test
    fun aNullListenerIsAComplete_noOp() {
        val summary = record(recorder(null))
        assertNotNull(summary.directory)
        assertFalse(File(summary.directory, "upload").exists())
    }

    @Test
    fun finalizeIsStillReportedIfStopFailsWhileWritingTheFinalFiles() {
        // The directory vanishes under the recorder (storage removed, folder deleted): the final manifest cannot be written.
        val listener = Recording()
        val rec = recorder(listener)
        val dir = rec.start(emptyMap())
        rec.onFrame(100, "TRACKING", t, q, rec.captured(100))
        File(dir, "frames").deleteRecursively()
        dir.deleteRecursively()

        runCatching { rec.stop() }

        // The take is over whatever happened, so whoever gates on "a take is in progress" must be released.
        assertTrue(listener.events.last().startsWith("finalized:${dir.name}:"))
    }
}
