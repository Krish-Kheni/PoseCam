package com.posecam.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRepositoryTest {
    private val f = SyncFixture()

    @Test
    fun enqueueIsIdempotentForTheSameFile() = runBlocking {
        val dir = f.sessionDir("s1")
        val seg = f.file(dir, "poses.csv", "frames")

        assertEquals(EnqueueResult.INSERTED, f.repo.enqueue(seg))
        assertEquals(EnqueueResult.ALREADY_QUEUED, f.repo.enqueue(seg))
        assertEquals(EnqueueResult.ALREADY_QUEUED, f.repo.enqueue(seg))

        assertEquals(1, f.repo.uploadsForSession("s1").size)
    }

    @Test
    fun theSameRelativePathInDifferentSessionsAreDistinctRows() = runBlocking {
        f.repo.enqueue(f.file(f.sessionDir("s1"), "manifest.json", "{}"))
        f.repo.enqueue(f.file(f.sessionDir("s2"), "manifest.json", "{}"))

        assertEquals(1, f.repo.uploadsForSession("s1").size)
        assertEquals(1, f.repo.uploadsForSession("s2").size)
    }

    @Test
    fun enqueueRecordsTypeRequirementAndKnownDigest() = runBlocking {
        val dir = f.sessionDir("s1")
        f.repo.enqueue(f.file(dir, "poses.csv", "frames").copy(sha256 = "abc"))
        f.repo.enqueue(f.file(dir, "export/2026-09-17-09_00_00-a3f9c1-s1/posecam_export.json", "mp4"))

        val segment = f.row("s1", "poses.csv")
        assertEquals(UploadFileType.TABLE, segment.fileType)
        assertTrue(segment.required)
        assertEquals("abc", segment.sha256)
        assertFalse(f.row("s1", "export/2026-09-17-09_00_00-a3f9c1-s1/posecam_export.json").required)
    }

    @Test
    fun refusesFilesTheBackendWouldRejectPermanently() {
        val dir = f.sessionDir("s1")
        val stray = f.file(dir, "notes.txt", "x")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { f.repo.enqueue(stray) } }
    }

    @Test
    fun aChangedFileIsRequeuedFromScratchAndClearsSyncedState() = runBlocking {
        val dir = f.sessionDir("s1")
        f.queueFinalized(dir, f.file(dir, "manifest.json", "{}"))
        val id = f.row("s1", "manifest.json").id
        f.repo.markPreparing(id); f.repo.markUploading(id); f.repo.markUploaded(id); f.repo.markVerified(id)
        f.repo.markSessionSynced("s1")

        val changed = f.file(dir, "manifest.json", """{"status":"recovered"}""")
        assertEquals(EnqueueResult.REQUEUED_CHANGED_FILE, f.repo.enqueue(changed))

        val row = f.row("s1", "manifest.json")
        assertEquals(UploadState.PENDING, row.state)
        assertEquals(changed.sizeBytes, row.sizeBytes)
        assertNull(f.repo.session("s1")!!.syncedAt)
    }

    @Test
    fun finalizeSessionQueuesEverythingAndMarksTheSessionFinalTogether() = runBlocking {
        val dir = f.sessionDir("s1")
        f.repo.finalizeSession(
            "s1", dir, "complete",
            listOf(f.file(dir, "manifest.json", "{}"), f.file(dir, "intrinsics.json", "{}")),
        )

        assertEquals(2, f.repo.uploadsForSession("s1").size)
        assertTrue(f.repo.session("s1")!!.recordingFinal)
        assertEquals("complete", f.repo.session("s1")!!.recordingStatus)
    }

    @Test
    fun transitionsFollowTheStateMachineAndRejectIllegalJumps() = runBlocking {
        f.queueFinalized(f.sessionDir("s1"), f.file(f.sessionDir("s1"), "manifest.json", "{}"))
        val id = f.row("s1", "manifest.json").id

        // PENDING cannot jump straight to UPLOADED/VERIFIED.
        assertThrows(IllegalStateException::class.java) { runBlocking { f.repo.markUploaded(id) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { f.repo.markVerified(id) } }

        f.repo.markPreparing(id); f.repo.markUploading(id); f.repo.markUploaded(id); f.repo.markVerified(id)
        assertEquals(UploadState.VERIFIED, f.row("s1", "manifest.json").state)
        assertNull(f.row("s1", "manifest.json").multipartState)
        // A verified file never silently goes back to uploading.
        assertThrows(IllegalStateException::class.java) { runBlocking { f.repo.markUploading(id) } }
        Unit
    }

    @Test
    fun retryFailedRequeuesFailedFilesAndForgivesARefusedSession() = runBlocking {
        val dir = f.sessionDir("s1")
        f.queueFinalized(dir, f.file(dir, "manifest.json", "{}"))
        val id = f.row("s1", "manifest.json").id
        f.repo.markPreparing(id); f.repo.markFailed(id, "HTTP 400")
        f.repo.markSessionFailed("s1", "refused")

        assertEquals(1, f.repo.retryFailed("s1"))

        assertEquals(UploadState.PENDING, f.row("s1", "manifest.json").state)
        assertFalse(f.repo.session("s1")!!.permanentFailure)
    }

    @Test
    fun interruptedPreparingRowsBecomeRunnableAgain() = runBlocking {
        val dir = f.sessionDir("s1")
        f.queueFinalized(dir, f.file(dir, "manifest.json", "{}"))
        f.repo.markPreparing(f.row("s1", "manifest.json").id)

        assertEquals(1, f.repo.normalizeInterrupted())
        assertEquals(UploadState.PENDING, f.row("s1", "manifest.json").state)
    }

    @Test
    fun retryCountNeverCapsRetrying() = runBlocking {
        val dir = f.sessionDir("s1")
        f.queueFinalized(dir, f.file(dir, "manifest.json", "{}"))
        val id = f.row("s1", "manifest.json").id
        repeat(50) { f.repo.recordRetry(id, "timeout") }

        val row = f.row("s1", "manifest.json")
        assertEquals(50, row.retryCount)
        assertTrue(row.state.isRunnable)
    }
}
