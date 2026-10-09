package com.posecam.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** PoseCam-specific behaviour of the upload processor: frame chunks, and uploading while a take is recorded. */
class UploadProcessorChunkTest {
    private val f = SyncFixture()
    private val id = "capture-20260917T090000-a3f9c1"

    init {
        f.api.multipartThreshold = Long.MAX_VALUE // chunks are ~35 MB in real life: always a single PUT
    }

    /** A finished session with [frameCount] JPEGs and a few plain files, queued the way the coordinator does. */
    private suspend fun finalizedSession(frameCount: Int, created: Boolean = true): File {
        val dir = f.sessionDir(id)
        listOf("poses.csv", "intrinsics.json").forEach { f.file(dir, it, "data-$it") }
        f.frames(dir, frameCount)
        f.repo.finalizeSession(id, dir, "complete", UploadPlan.forSession(id, dir, f.staging))
        if (created) f.repo.markSessionCreated(id)
        return dir
    }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- chunks end to end ------------------------------------------------------------------

    @Test
    fun uploadsEveryChunkAsAZipOfExactlyItsJpegsThenSyncsTheSession() = runBlocking {
        finalizedSession(2_300)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        val rows = f.repo.uploadsForSession(id)
        assertTrue(rows.all { it.state == UploadState.VERIFIED })
        assertEquals(3, rows.count { it.kind == UploadSourceKind.FRAME_CHUNK })
        assertNotNull(f.repo.session(id)!!.syncedAt)

        // What reached S3 for chunk 1 is a valid zip of frames 1000..1999, byte for byte what is on disk.
        val chunk = f.s3.puts.first { it.url.endsWith("frames-00001.zip") }
        val zipFile = File.createTempFile("received", ".zip").apply { writeBytes(chunk.content); deleteOnExit() }
        ZipFile(zipFile).use { zip ->
            assertEquals(1_000, zip.size())
            val first = zip.entries().nextElement()
            assertArrayEquals(File(File(f.capturesRoot, id), "frames/${first.name}").readBytes(), zip.getInputStream(first).readBytes())
        }
    }

    @Test
    fun theBackendIsToldTheRealSizeAndDigestOfTheBuiltZip_notTheEstimate() = runBlocking {
        finalizedSession(10)
        val estimate = f.row(id, "frames-00000.zip").sizeBytes // sum of JPEG sizes

        f.processor.runQueue()

        val chunk = f.s3.puts.first { it.url.endsWith("frames-00000.zip") }
        val row = f.row(id, "frames-00000.zip")
        assertEquals(chunk.content.size.toLong(), row.sizeBytes)
        assertTrue("zip adds headers to the JPEG bytes", row.sizeBytes > estimate)
        assertEquals(sha256Hex(chunk.content), row.sha256)
        assertEquals(sha256Hex(chunk.content), chunk.headers["x-amz-checksum-sha256"])
        val sent = f.api.completedSessions.single().first { it.relativePath == "frames-00000.zip" }
        assertEquals(row.sizeBytes, sent.sizeBytes)
    }

    @Test
    fun theStagedZipIsDeletedOnceItsRowIsVerified() = runBlocking {
        finalizedSession(10)
        val staged = f.staging.chunkFile(id, "frames-00000.zip")

        f.processor.runQueue()

        assertFalse("staged chunk should be released after verification", staged.exists())
        assertTrue(File(f.capturesRoot, "$id/frames").listFiles()!!.size == 10) // the JPEGs themselves stay
    }

    @Test
    fun theSessionsStagingFolderDisappearsWithItsLastChunk() = runBlocking {
        finalizedSession(2_500)

        f.processor.runQueue()

        assertFalse(f.staging.chunkFile(id, "frames-00000.zip").parentFile!!.exists())
    }

    @Test
    fun aChunkIsOnlyBuiltWhenItsTurnComes_notAllAtOnce() = runBlocking {
        finalizedSession(2_500)
        var maxStaged = 0
        f.api.verifyBehavior = { maxStaged = maxOf(maxStaged, f.staging.chunkFile(id, "frames-00000.zip").parentFile!!.listFiles().orEmpty().size) }

        f.processor.runQueue()

        assertTrue("at most one chunk staged at a time, saw $maxStaged", maxStaged <= 1)
    }

    @Test
    fun aRebuiltChunkAfterACrashHasTheSameDigestSoTheBackendRecordStillMatches() = runBlocking {
        finalizedSession(40)
        val staged = f.staging.chunkFile(id, "frames-00000.zip")
        f.api.failOnceFor("requestUpload", "frames-00000.zip", http(503)) // dies after hashing, before sending

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        val firstDigest = f.row(id, "frames-00000.zip").sha256
        val firstBytes = staged.readBytes()
        staged.delete() // the staged copy is gone (app data trimmed, crash mid-build, ...)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(firstDigest, f.row(id, "frames-00000.zip").sha256)
        assertArrayEquals(firstBytes, f.s3.puts.first { it.url.endsWith("frames-00000.zip") }.content)
    }

    @Test
    fun aChunkWaitingOnlyForVerificationIsNeverRebuilt() = runBlocking {
        finalizedSession(10)
        val staged = f.staging.chunkFile(id, "frames-00000.zip")
        f.api.failOnceFor("verify", "frames-00000.zip", NETWORK_DOWN) // bytes reached S3, verification could not be asked

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        assertEquals(UploadState.UPLOADED, f.row(id, "frames-00000.zip").state)
        staged.delete()

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertFalse("verification needs no bytes, so nothing may be rebuilt", staged.exists())
        assertEquals(1, f.s3.puts.count { it.url.endsWith("frames-00000.zip") })
        assertEquals(UploadState.VERIFIED, f.row(id, "frames-00000.zip").state)
    }

    @Test
    fun failedVerificationRebuildsAndResendsTheChunkOnTheNextRun() = runBlocking {
        finalizedSession(10)
        f.api.failOnceFor("verify", "frames-00000.zip", http(409, "VERIFICATION_FAILED", "{\"reason\":\"CHECKSUM_MISMATCH\"}"))

        // The first run resets the chunk and skips it for the rest of that run, so one bad file never starves the queue.
        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        assertEquals(UploadState.PENDING, f.row(id, "frames-00000.zip").state)
        assertEquals(1, f.row(id, "frames-00000.zip").verifyFailures)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(2, f.s3.puts.count { it.url.endsWith("frames-00000.zip") })
        assertEquals(UploadState.VERIFIED, f.row(id, "frames-00000.zip").state)
    }

    @Test
    fun aChunkWhoseJpegsAreAllGoneFailsAsMissingInsteadOfUploadingAnEmptyZip() = runBlocking {
        val dir = finalizedSession(10)
        File(dir, "frames").deleteRecursively()

        f.processor.runQueue()

        val row = f.row(id, "frames-00000.zip")
        assertEquals(UploadState.FAILED, row.state)
        assertEquals("Local file is missing", row.lastError)
        assertEquals(0, f.s3.puts.count { it.url.endsWith("frames-00000.zip") })
        assertNull(f.repo.session(id)!!.syncedAt) // a session with a failed required file is never SYNCED
    }

    @Test
    fun anAlreadyVerifiedChunkIsNotSentAgainAndItsStagedFileIsReleased() = runBlocking {
        finalizedSession(10)
        f.api.alreadyVerified = setOf("frames-00000.zip")

        f.processor.runQueue()

        assertEquals(0, f.s3.puts.count { it.url.endsWith("frames-00000.zip") })
        assertFalse(f.staging.chunkFile(id, "frames-00000.zip").exists())
        assertEquals(UploadState.VERIFIED, f.row(id, "frames-00000.zip").state)
    }

    @Test
    fun aLargeChunkUsesMultipartAndKeepsItsPartsAcrossARestart() = runBlocking {
        finalizedSession(30)
        f.api.multipartThreshold = 10
        f.api.partSizeBytes = 2_000
        f.s3.failNext(NETWORK_DOWN) // the first part's PUT fails: nothing persisted yet

        assertEquals(QueueRunResult.RETRY, f.processor.runQueue())
        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(UploadState.VERIFIED, f.row(id, "frames-00000.zip").state)
        assertTrue(f.api.partUrlRequests.isNotEmpty())
    }

    // ---- while recording ----------------------------------------------------------------------

    @Test
    fun finishedRecordingsKeepUploadingWhileANewTakeIsBeingRecorded() = runBlocking {
        finalizedSession(10)
        f.recording = true

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertTrue(f.repo.uploadsForSession(id).all { it.state == UploadState.VERIFIED })
        assertNotNull(f.repo.session(id)!!.syncedAt)
    }

    @Test
    fun aTakeStartingMidQueueDoesNotStopTheUpload() = runBlocking {
        finalizedSession(10)
        var verifies = 0
        f.api.verifyBehavior = { if (++verifies == 2) f.recording = true } // the user presses Record during file 2

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertTrue("the whole queue finished", f.repo.uploadsForSession(id).all { it.state == UploadState.VERIFIED })
        assertNotNull(f.repo.session(id)!!.syncedAt)
    }

    @Test
    fun incompleteTakesAreReportedToTheBackendAsIncomplete() = runBlocking {
        val dir = f.sessionDir(id, status = "incomplete")
        f.repo.finalizeSession(id, dir, "incomplete", UploadPlan.forSession(id, dir, f.staging))
        f.repo.markSessionCreated(id)

        f.processor.runQueue()

        assertEquals("incomplete", f.api.completedStatuses.single())
    }

    @Test
    fun theCloudSessionIsCreatedWithTheManifestStartTimeAndStatus() = runBlocking {
        val dir = f.sessionDir(id)
        f.repo.finalizeSession(id, dir, "complete", UploadPlan.forSession(id, dir, f.staging))

        f.processor.runQueue()

        assertEquals("2026-09-17T09:00:00.000Z", f.api.createdAt[id])
        assertEquals("complete", f.api.createdStatuses[id])
    }
}
