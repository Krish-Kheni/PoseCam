package com.posecam.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recording only reports two facts (started, finalized); the coordinator turns them into queue rows off-thread. */
class UploadCoordinatorTest {
    private val f = SyncFixture()
    private val scheduler = FakeScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinator = UploadCoordinator(f.repo, scheduler, f.staging, scope)
    private val id = SyncFixture.SESSION

    @After fun tearDown() { ActiveRecordingSessions.remove(id); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    @Test
    fun startingATakeMarksItActiveAndPausesTheUploadChain() = runBlocking {
        val dir = f.sessionDir(id, status = "recording")

        coordinator.onSessionStarted(id, dir)

        // Synchronous: the processor consults this set before every file, even before the consumer ran.
        assertTrue(ActiveRecordingSessions.contains(id))
        until { scheduler.paused == 1 && f.repo.session(id) != null }
        assertFalse(f.repo.session(id)!!.recordingFinal)
        assertEquals(0, f.repo.uploadsForSession(id).size) // nothing is queued until the take is over
        assertEquals(0, scheduler.scheduled)
    }

    @Test
    fun finalizationQueuesPlainFilesAndFrameChunksThenResumesUploads() = runBlocking {
        val dir = f.sessionDir(id)
        listOf("poses.csv", "frame_metadata.csv", "imu.csv", "intrinsics.json", "device.json").forEach { f.file(dir, it, "x-$it") }
        f.frames(dir, 2_500)
        coordinator.onSessionStarted(id, dir)

        coordinator.onSessionFinalized(id, dir, "complete")
        until { f.repo.session(id)?.recordingFinal == true }

        val rows = f.repo.uploadsForSession(id).map { it.relativePath }.toSet()
        assertEquals(
            setOf(
                "manifest.json", "device.json", "intrinsics.json", "poses.csv", "frame_metadata.csv", "imu.csv",
                "frames-00000.zip", "frames-00001.zip", "frames-00002.zip",
            ),
            rows,
        )
        assertEquals(listOf(1000, 1000, 500), f.repo.uploadsForSession(id).filter { it.kind == UploadSourceKind.FRAME_CHUNK }.map { it.itemCount })
        assertFalse(ActiveRecordingSessions.contains(id))
        until { scheduler.scheduled >= 1 }
        assertEquals("complete", f.repo.session(id)!!.recordingStatus)
    }

    @Test
    fun anIncompleteTakeIsQueuedAsIncomplete() = runBlocking {
        val dir = f.sessionDir(id, status = "incomplete")
        coordinator.onSessionStarted(id, dir)

        coordinator.onSessionFinalized(id, dir, "incomplete")
        until { f.repo.session(id)?.recordingFinal == true }

        assertEquals("incomplete", f.repo.session(id)!!.recordingStatus)
    }

    @Test
    fun aSessionWithNoFramesFolderStillFinalizesWithItsPlainFiles() = runBlocking {
        val dir = f.sessionDir(id)

        coordinator.onSessionFinalized(id, dir, "complete")
        until { f.repo.session(id)?.recordingFinal == true }

        assertEquals(listOf("manifest.json"), f.repo.uploadsForSession(id).map { it.relativePath })
    }

    @Test
    fun theRecordingFlagIsClearedEvenWhenQueueingFails() = runBlocking {
        // A directory that cannot be planned: finalize still must not leave the app thinking a take is running.
        val dir = f.sessionDir(id)
        coordinator.onSessionStarted(id, dir)
        f.dao.failNextInsert = true

        coordinator.onSessionFinalized(id, dir, "complete")

        until { !ActiveRecordingSessions.contains(id) }
        until { scheduler.scheduled >= 1 } // the rest of the backlog still resumes
    }

    @Test
    fun deletingASessionLocallyForgetsItsRowsAndStagedChunks() = runBlocking {
        val dir = f.sessionDir(id)
        f.repo.finalizeSession(id, dir, "complete", listOf(f.file(dir, "poses.csv", "x")))

        coordinator.onSessionDeletedLocally(id)

        until { f.repo.session(id) == null }
        assertEquals(listOf(id), f.forgotten)
    }

    @Test
    fun theListenerCallsNeverBlockOrThrow() {
        // Calls from the recording thread only enqueue an event; a burst of them returns immediately.
        val dir = f.sessionDir(id)
        repeat(1_000) {
            coordinator.onSessionStarted(id, dir)
            coordinator.onSessionFinalized(id, dir, "complete")
        }
    }
}
