package com.posecam.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** A synced recording may only be deleted if every JPEG on the phone is inside a verified chunk. */
class LocalRetentionFramesTest {
    private val f = SyncFixture()
    private val id = "capture-20260917T090000-a3f9c1"

    private fun manager() = LocalRetentionManager(
        repository = f.repo, enabled = { true }, retentionDays = { 7 }, storagePressure = { false },
        clock = { f.now }, isActivelyRecording = { false },
    )

    /** Queued, uploaded (by hand), verified and SYNCED: the state the backend confirmed. */
    private suspend fun synced(frameCount: Int, status: String = "complete"): File {
        val dir = f.sessionDir(id, status = status)
        listOf("poses.csv", "intrinsics.json").forEach { f.file(dir, it, "data-$it") }
        f.frames(dir, frameCount)
        f.repo.finalizeSession(id, dir, status, UploadPlan.forSession(id, dir, f.staging))
        f.repo.markSessionCreated(id)
        f.repo.uploadsForSession(id).forEach {
            f.repo.markPreparing(it.id); f.repo.markUploading(it.id); f.repo.markUploaded(it.id); f.repo.markVerified(it.id)
        }
        f.repo.markSessionSynced(id)
        f.now += TimeUnit.DAYS.toMillis(8)
        return dir
    }

    @Test
    fun deletesASyncedRecordingWhoseEveryJpegIsInAVerifiedChunk() = runBlocking {
        val dir = synced(2_300)

        assertNull(manager().blockedReason(f.repo.session(id)!!))
        assertEquals(listOf(id), manager().cleanup().deletedSessions)

        assertFalse(dir.exists())
        assertNull(f.repo.session(id))
        assertEquals(listOf(id), f.forgotten) // staged chunks are purged with the rows
    }

    @Test
    fun aJpegThatIsInNoVerifiedChunkBlocksDeletion() = runBlocking {
        val dir = synced(2_300)
        // A frame that appeared after the sync: it was never uploaded.
        File(dir, "frames/002300_99.jpg").writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(RetentionBlock.FRAMES_NOT_COVERED, manager().blockedReason(f.repo.session(id)!!))
        assertTrue(manager().cleanup().deletedSessions.isEmpty())
        assertTrue(dir.exists())
    }

    @Test
    fun aFrameAddedToAnExistingChunkRangeAlsoBlocksDeletion() = runBlocking {
        val dir = synced(2_300)
        File(dir, "frames/000500_1.jpg").writeBytes(byteArrayOf(9)) // same index range as chunk 0, different content

        assertEquals(RetentionBlock.FRAMES_NOT_COVERED, manager().blockedReason(f.repo.session(id)!!))
        assertTrue(dir.exists())
    }

    @Test
    fun aMissingJpegDoesNotBlockDeletionBecauseNothingLocalIsUncovered() = runBlocking {
        // One frame failed to write during the take (poses.csv still says "saved"): the chunk held what existed.
        val dir = f.sessionDir(id)
        f.frames(dir, 50)
        File(dir, "frames").listFiles()!!.first { it.name.startsWith("000007_") }.delete()
        f.repo.finalizeSession(id, dir, "complete", UploadPlan.forSession(id, dir, f.staging))
        f.repo.markSessionCreated(id)
        f.repo.uploadsForSession(id).forEach {
            f.repo.markPreparing(it.id); f.repo.markUploading(it.id); f.repo.markUploaded(it.id); f.repo.markVerified(it.id)
        }
        f.repo.markSessionSynced(id)
        f.now += TimeUnit.DAYS.toMillis(8)

        assertNull(manager().blockedReason(f.repo.session(id)!!))
    }

    @Test
    fun anIncompleteTakeThatSyncedIsDeletableLikeAnyOther() = runBlocking {
        val dir = synced(30, status = "incomplete")

        assertNull(manager().blockedReason(f.repo.session(id)!!))
        assertEquals(listOf(id), manager().cleanup().deletedSessions)
        assertFalse(dir.exists())
    }

    @Test
    fun aSessionThatIsNotSyncedIsNeverTouchedWhateverTheStoragePressure() = runBlocking {
        val dir = f.sessionDir(id)
        f.frames(dir, 30)
        f.repo.finalizeSession(id, dir, "complete", UploadPlan.forSession(id, dir, f.staging))
        f.repo.markSessionCreated(id)
        f.now += TimeUnit.DAYS.toMillis(30)

        val pressured = LocalRetentionManager(f.repo, { true }, { 0 }, { true }, { f.now }, isActivelyRecording = { false })
        assertTrue(pressured.cleanup().deletedSessions.isEmpty())
        assertTrue(dir.exists())
        assertNotNull(f.repo.session(id))
    }

    @Test
    fun aPlainFileChangedAfterTheSyncAlsoBlocksDeletion() = runBlocking {
        val dir = synced(30)
        File(dir, "poses.csv").writeText("changed after sync, so never uploaded")

        assertEquals(RetentionBlock.LOCAL_FILE_NOT_COVERED, manager().blockedReason(f.repo.session(id)!!))
    }
}
