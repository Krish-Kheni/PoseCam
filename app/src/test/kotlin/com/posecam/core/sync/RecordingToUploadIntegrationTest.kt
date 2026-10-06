package com.posecam.core.sync

import com.posecam.FrameEncoder
import com.posecam.FrameImage
import com.posecam.PoseRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * The whole chain with the real classes and only the network faked: PoseRecorder records a take, the listener
 * (UploadCoordinator) reacts, the processor uploads. Proves that what reaches S3 is what the recorder wrote.
 */
class RecordingToUploadIntegrationTest {
    private val f = SyncFixture()
    private val scheduler = FakeScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinator = UploadCoordinator(f.repo, scheduler, f.staging, scope)

    // The real gate (ActiveRecordingSessions), unlike SyncFixture's switch.
    private val processor = UploadProcessor(f.repo, f.api, f.s3, FileHasher(), { "install-1" }, "test", f.materializer)

    /** Distinct, timestamp-derived bytes per frame, so a mix-up between frames is visible. */
    private val encoder = FrameEncoder { image, out -> out.writeBytes(ByteArray(64 + (image.timestampNs % 29).toInt()) { (image.timestampNs + it).toByte() }) }

    private val t = floatArrayOf(0f, 0f, 0f)
    private val q = floatArrayOf(0f, 0f, 0f, 1f)

    init { f.api.multipartThreshold = Long.MAX_VALUE }

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

    private fun PoseRecorder.captured(ts: Long): FrameImage {
        // A real recording hands the writer a pooled buffer per frame; the pool refills as frames are written.
        var buffer = pool.tryAcquire()
        while (buffer == null) { Thread.sleep(1); buffer = pool.tryAcquire() }
        buffer.set(2, 2, ts, ByteBuffer.wrap(ByteArray(4)), ByteBuffer.wrap(ByteArray(1)), ByteBuffer.wrap(ByteArray(1)), 2, 1, 1)
        return FrameImage.Captured(buffer)
    }

    private fun recordTake(frames: Int): File {
        val recorder = PoseRecorder(f.capturesRoot, encoder, listener = coordinator)
        val dir = recorder.start(mapOf("app_version" to "0.3.0"), device = mapOf("model" to "test"))
        recorder.onIntrinsics(com.posecam.Intrinsics(500f, 500f, 320f, 240f, 640, 480))
        for (i in 0 until frames) {
            val ts = 1_000_000_000L + i * 33_333_333L
            if (i % 50 == 7) recorder.onFrame(ts, "PAUSED:INSUFFICIENT_FEATURES", null, null, recorder.captured(ts))
            else recorder.onFrame(ts, "TRACKING", t, q, recorder.captured(ts))
        }
        recorder.stop()
        return dir
    }

    @Test
    fun aRecordedTakeArrivesInTheCloudExactlyAsTheRecorderWroteIt() = runBlocking {
        val dir = recordTake(2_350)
        val id = dir.name
        until { f.repo.session(id)?.recordingFinal == true }

        assertEquals(QueueRunResult.DONE, processor.runQueue())

        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertTrue(f.repo.uploadsForSession(id).all { it.state == UploadState.VERIFIED })
        assertEquals("complete", f.api.createdStatuses[id])

        // Plain files: byte for byte what is on disk, including the FINAL manifest (complete: true).
        for (name in listOf("manifest.json", "poses.csv", "frame_metadata.csv", "intrinsics.json", "device.json")) {
            val sent = f.s3.puts.firstOrNull { it.url.endsWith("/$name") }
            assertNotNull("$name was uploaded", sent)
            assertArrayEquals(name, File(dir, name).readBytes(), sent!!.content)
        }
        assertTrue(File(dir, "manifest.json").readText().contains("\"complete\": true"))

        // Frames: three chunks (1000 + 1000 + 350) holding every JPEG exactly once, unmodified.
        val seen = mutableMapOf<String, ByteArray>()
        for (chunk in 0..2) {
            val put = f.s3.puts.first { it.url.endsWith("frames-0000$chunk.zip") }
            val zip = File.createTempFile("chunk$chunk", ".zip").apply { writeBytes(put.content); deleteOnExit() }
            ZipFile(zip).use { z -> z.entries().asSequence().forEach { seen[it.name] = z.getInputStream(it).readBytes() } }
        }
        val onDisk = File(dir, "frames").listFiles()!!
        assertEquals(2_350, onDisk.size)
        assertEquals(onDisk.map { it.name }.toSet(), seen.keys)
        onDisk.forEach { assertArrayEquals(it.name, it.readBytes(), seen[it.name]) }

        // poses.csv has one row per frame, and the manifest agrees with what was uploaded.
        assertEquals(2_351, File(dir, "poses.csv").readLines().size)
        assertTrue(File(dir, "manifest.json").readText().contains("\"frame_count\": 2350"))
    }

    @Test
    fun aFinishedTakeWaitsForThePipeChoiceAndThenUploadsIntoThatPipe() = runBlocking {
        f.dao.defaultPipe = null // production: no pipe until the collector taps White pipe / Black pipe
        val dir = recordTake(60)
        val id = dir.name
        until { f.repo.session(id)?.recordingFinal == true }

        // "Take looks good" is on screen and the collector has not chosen yet: nothing may leave the phone.
        assertEquals(QueueRunResult.DONE, processor.runQueue())
        assertEquals(0, f.api.calls.size)
        assertEquals(0, f.s3.puts.size)

        coordinator.choosePipe(id, Pipe.BLACK)
        until { f.repo.session(id)?.pipe == "black" }
        assertEquals(QueueRunResult.DONE, processor.runQueue())

        assertEquals("black", f.api.createdPipes[id])
        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertTrue(f.s3.puts.isNotEmpty())
    }

    @Test
    fun nothingIsUploadedWhileTheTakeIsRunningAndEverythingAfterwards() = runBlocking {
        // An older, finished recording is waiting in the queue when the user starts a new take.
        val older = f.sessionDir("capture-20260101T000000-000001")
        f.frames(older, 30)
        f.repo.finalizeSession(older.name, older, "complete", UploadPlan.forSession(older.name, older, f.staging))
        f.repo.markSessionCreated(older.name)

        val recorder = PoseRecorder(f.capturesRoot, encoder, listener = coordinator)
        val dir = recorder.start(emptyMap())
        until { scheduler.paused >= 1 } // the chain was paused when the take started
        recorder.onFrame(1_000_000_000L, "TRACKING", t, q, recorder.captured(1_000_000_000L))

        // A worker that slipped through (or any manual trigger) does nothing while recording.
        assertTrue(com.posecam.core.sync.ActiveRecordingSessions.contains(dir.name))
        assertEquals(QueueRunResult.DONE, processor.runQueue())
        assertEquals(0, f.s3.puts.size)
        assertEquals(0, f.api.calls.size)

        recorder.stop()
        until { !ActiveRecordingSessions.contains(dir.name) && f.repo.session(dir.name)?.recordingFinal == true }
        until { scheduler.scheduled >= 1 } // resumed

        assertEquals(QueueRunResult.DONE, processor.runQueue())
        assertNotNull(f.repo.session(older.name)!!.syncedAt)
        assertNotNull(f.repo.session(dir.name)!!.syncedAt)
        assertFalse(f.s3.puts.isEmpty())
    }

    @Test
    fun aTakeKilledMidRecordingIsAdoptedByRecoveryAsIncomplete() = runBlocking {
        // Simulate a crash: the recorder started (manifest complete:false on disk) and the process died.
        val recorder = PoseRecorder(f.capturesRoot, encoder, listener = null)
        val dir = recorder.start(emptyMap())
        recorder.onFrame(1_000_000_000L, "TRACKING", t, q, recorder.captured(1_000_000_000L))
        Thread.sleep(50) // the writer thread finishes the JPEG; no stop(): the app "died"
        assertTrue(File(dir, "manifest.json").readText().contains("\"complete\": false"))

        UploadQueueRecovery(f.capturesRoot, f.repo, scheduler, f.staging) { false }.run()
        f.api.multipartThreshold = Long.MAX_VALUE
        assertEquals(QueueRunResult.DONE, processor.runQueue())

        assertEquals("incomplete", f.api.completedStatuses.single())
        assertNotNull(f.repo.session(dir.name)!!.syncedAt)
    }
}
