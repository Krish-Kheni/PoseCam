package com.posecam.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadQueueRecoveryTest {
    private val f = SyncFixture()
    private val scheduler = FakeScheduler()
    private var active = setOf<String>()

    private fun recovery() = UploadQueueRecovery(f.capturesRoot, f.repo, scheduler, f.staging) { it in active }

    /** A finished PoseCam recording: 8 plain files + 3 frame chunks (2,300 frames). */
    private fun session(id: String = "capture-20260917T090000-a3f9c1", complete: Boolean = true): File {
        val dir = f.sessionDir(id, status = if (complete) "complete" else "incomplete")
        listOf("poses.csv", "frame_metadata.csv", "imu.csv", "intrinsics.json", "device.json").forEach { f.file(dir, it, "x-$it") }
        // Not part of the cloud file set: must never be queued.
        f.file(dir, "scratch.tmp", "junk")
        f.file(dir, "trajectory.png", "png")
        f.frames(dir, 2_300)
        return dir
    }

    private val full = 6 + 3 // manifest + 5 plain + 3 chunks

    @Test
    fun adoptsAPreExistingRecordingWithEveryUploadableFileAndChunk() = runBlocking {
        val id = "capture-20260917T090000-a3f9c1"
        session(id)

        val report = recovery().run()

        val rows = f.repo.uploadsForSession(id)
        assertEquals(full, rows.size)
        assertTrue(rows.none { it.relativePath == "scratch.tmp" || it.relativePath == "trajectory.png" })
        assertTrue(rows.none { it.relativePath.endsWith(".jpg") }) // JPEGs never travel one by one
        assertTrue(f.repo.session(id)!!.recordingFinal)
        assertEquals(full, report.filesQueued)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun runningTwiceNeverCreatesDuplicates() = runBlocking {
        val id = "capture-20260917T090000-a3f9c1"
        session(id)

        recovery().run()
        val second = recovery().run()

        assertEquals(full, f.repo.uploadsForSession(id).size)
        assertEquals(0, second.filesQueued)
    }

    @Test
    fun aKilledTakeIsAdoptedAsIncompleteEvidence() = runBlocking {
        val id = "capture-20260917T090000-a3f9c1"
        session(id, complete = false)

        recovery().run()

        assertEquals("incomplete", f.repo.session(id)!!.recordingStatus)
        assertEquals(full, f.repo.uploadsForSession(id).size)
    }

    @Test
    fun neverQueuesFilesOfASessionThatIsStillBeingWritten() = runBlocking {
        session("capture-20260917T090000-a3f9c1")
        active = setOf("capture-20260917T090000-a3f9c1") // a writer is open in this process

        val report = recovery().run()

        assertTrue(f.dao.allSessions().isEmpty())
        assertEquals(1, report.sessionsSkippedWhileRecording)
    }

    @Test
    fun skipsFoldersTheBackendWouldRefuseForever() = runBlocking {
        session("capture-20260917T090000-a3f9c1")
        session("my-notes")                       // not a PoseCam id
        session("capture-20260917T090001-A3F9C1") // uppercase suffix: the backend regex is lowercase hex
        File(f.capturesRoot, "empty-folder").mkdirs() // no manifest at all

        recovery().run()

        assertEquals(listOf("capture-20260917T090000-a3f9c1"), f.dao.allSessions().map { it.sessionId })
    }

    @Test
    fun aRecordingWithoutFramesStillSyncsItsPlainFiles() = runBlocking {
        val id = "capture-20260917T090000-a3f9c1"
        f.sessionDir(id)

        recovery().run()

        assertEquals(listOf("manifest.json"), f.repo.uploadsForSession(id).map { it.relativePath })
    }

    @Test
    fun resumesInterruptedUploadsAndKeepsMultipartProgress() = runBlocking {
        val id = "capture-20260917T090000-a3f9c1"
        val dir = session(id)
        recovery().run()
        f.repo.markSessionCreated(id)
        val imu = f.row(id, "imu.csv")
        f.repo.markPreparing(imu.id)
        f.repo.markUploading(imu.id)
        f.repo.saveMultipart(imu.id, MultipartState("up-1", 4, 7).withPart(MultipartState.Part(1, "e1", "c1", 4)))
        val prep = f.row(id, "poses.csv")
        f.repo.markPreparing(prep.id)

        recovery().run() // app restarts

        assertEquals(UploadState.PENDING, f.row(id, "poses.csv").state) // PREPARING normalised
        val resumed = f.row(id, "imu.csv")
        assertEquals(UploadState.UPLOADING, resumed.state)
        assertEquals("up-1", resumed.multipartUploadId)
        assertEquals(listOf(1), MultipartState.decode(resumed.multipartState)!!.parts.map { it.partNumber })
        assertTrue(dir.exists())
    }

    @Test
    fun aVerifiedChunkIsNotResetByARecoveryRun() = runBlocking {
        // The queued size of a chunk is an estimate that becomes the zip's size once built: recovery must compare
        // the number of frames, never sizes, or every restart would re-upload every chunk.
        val id = "capture-20260917T090000-a3f9c1"
        session(id)
        recovery().run()
        f.repo.markSessionCreated(id)
        val chunk = f.row(id, "frames-00000.zip")
        f.repo.markPreparing(chunk.id); f.repo.markUploading(chunk.id); f.repo.markUploaded(chunk.id); f.repo.markVerified(chunk.id)
        f.repo.saveMaterialized(chunk.id, chunk.sizeBytes + 4_000) // the real zip is larger than the JPEG sum

        recovery().run()

        assertEquals(UploadState.VERIFIED, f.row(id, "frames-00000.zip").state)
        assertEquals(chunk.sizeBytes + 4_000, f.row(id, "frames-00000.zip").sizeBytes)
    }

    @Test
    fun retriesPendingCloudSessionCreationOnTheNextRun() = runBlocking {
        val id = "capture-20260917T090000-a3f9c1"
        session(id)
        f.api.multipartThreshold = Long.MAX_VALUE // the fake backend's default would split a 40 KB chunk into 4-byte parts
        recovery().run()
        f.api.failOnce("createSession", NETWORK_DOWN)
        assertEquals(QueueRunResult.RETRY, f.processor.runQueue()) // offline when the take finished
        assertFalse(f.repo.session(id)!!.cloudCreated)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue()) // network is back

        assertTrue(f.repo.session(id)!!.cloudCreated)
        assertTrue(f.repo.uploadsForSession(id).all { it.state == UploadState.VERIFIED })
    }

    @Test
    fun dropsRowsOfSessionsDeletedFromDiskAndTheirStagedChunks() = runBlocking {
        val id = "capture-20260917T090000-a3f9c1"
        val dir = session(id)
        recovery().run()
        dir.deleteRecursively()

        val report = recovery().run()

        assertEquals(1, report.orphansDropped)
        assertNull(f.repo.session(id))
        assertTrue(f.repo.uploadsForSession(id).isEmpty())
        assertEquals(listOf(id), f.forgotten)
    }
}
