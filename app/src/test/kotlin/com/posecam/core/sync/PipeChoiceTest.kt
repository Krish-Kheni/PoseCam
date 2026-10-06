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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A finished recording is uploaded only after the collector files it under a pipe, and goes to that pipe's folder. */
class PipeChoiceTest {
    private val f = SyncFixture()
    private val scheduler = FakeScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinator = UploadCoordinator(f.repo, scheduler, f.staging, scope)
    private val id = "capture-20260917T090000-a3f9c1"

    init {
        f.dao.defaultPipe = null // production behaviour: a new session has no pipe yet
        f.api.multipartThreshold = Long.MAX_VALUE
    }

    @After fun tearDown() { ActiveRecordingSessions.remove(id); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    private suspend fun finishedTake(frames: Int = 20): File {
        val dir = f.sessionDir(id)
        f.file(dir, "poses.csv", "poses")
        f.frames(dir, frames)
        coordinator.onSessionStarted(id, dir)
        coordinator.onSessionFinalized(id, dir, "complete")
        until { f.repo.session(id)?.recordingFinal == true && !ActiveRecordingSessions.contains(id) }
        return dir
    }

    @Test
    fun nothingIsSentUntilAPipeIsChosen_notEvenTheSession() = runBlocking {
        finishedTake()

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(0, f.api.calls.size)
        assertEquals(0, f.s3.puts.size)
        assertFalse(f.processor.hasWork())
        assertNull(f.repo.session(id)!!.pipe)
        assertTrue(f.repo.uploadsForSession(id).all { it.state == UploadState.PENDING })
        assertEquals(SessionCloudStatus.AWAITING_PIPE, summary().status)
    }

    @Test
    fun choosingAPipeStartsTheUploadIntoThatFolder() = runBlocking {
        finishedTake()
        val scheduledBefore = scheduler.scheduled

        coordinator.choosePipe(id, Pipe.BLACK)
        until { f.repo.session(id)?.pipe == "black" }
        until { scheduler.scheduled > scheduledBefore }
        assertTrue(f.processor.hasWork())
        assertEquals(SessionCloudStatus.PENDING, summary().status)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals("black", f.api.createdPipes[id]) // the backend files everything under black-pipe/
        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertEquals(SessionCloudStatus.SYNCED, summary().status)
    }

    @Test
    fun theWhitePipeGoesToTheWhiteFolder() = runBlocking {
        finishedTake()
        coordinator.choosePipe(id, Pipe.WHITE)
        until { f.repo.session(id)?.pipe == "white" }

        f.processor.runQueue()

        assertEquals("white", f.api.createdPipes[id])
    }

    @Test
    fun twoRecordingsCanGoToDifferentPipes() = runBlocking {
        val other = "capture-20260917T091000-b00002"
        finishedTake()
        val dir2 = f.sessionDir(other)
        coordinator.onSessionStarted(other, dir2)
        coordinator.onSessionFinalized(other, dir2, "complete")
        until { f.repo.session(other)?.recordingFinal == true }

        coordinator.choosePipe(id, Pipe.WHITE)
        coordinator.choosePipe(other, Pipe.BLACK)
        until { f.repo.session(id)?.pipe != null && f.repo.session(other)?.pipe != null }
        f.processor.runQueue()

        assertEquals(mapOf(id to "white", other to "black"), f.api.createdPipes)
        ActiveRecordingSessions.remove(other)
    }

    @Test
    fun theChoiceCannotOvertakeTheRecordersOwnEvents() = runBlocking {
        // Sent straight after "finalized", before the consumer has necessarily processed anything.
        val dir = f.sessionDir(id)
        coordinator.onSessionStarted(id, dir)
        coordinator.onSessionFinalized(id, dir, "complete")
        coordinator.choosePipe(id, Pipe.WHITE)

        until { f.repo.session(id)?.pipe == "white" }
        assertTrue(f.repo.session(id)!!.recordingFinal)
    }

    @Test
    fun aPipeCannotBeChangedOnceTheSessionIsInTheCloud() = runBlocking {
        finishedTake()
        coordinator.choosePipe(id, Pipe.WHITE)
        until { f.repo.session(id)?.pipe == "white" }
        f.processor.runQueue()

        coordinator.choosePipe(id, Pipe.BLACK) // a second tap, or a stale dialog
        delay(200)

        assertEquals("white", f.repo.session(id)!!.pipe)
    }

    @Test
    fun choosingForAnUnknownSessionDoesNothingAndSchedulesNothing() = runBlocking {
        val before = scheduler.scheduled

        coordinator.choosePipe("capture-20260101T000000-ffffff", Pipe.WHITE)
        delay(200)

        assertEquals(before, scheduler.scheduled)
        assertTrue(f.dao.allSessions().isEmpty())
    }

    @Test
    fun recoveryAdoptsOlderRecordingsButNeverUploadsThemWithoutAChoice() = runBlocking {
        val dir = f.sessionDir(id)
        f.frames(dir, 10)
        UploadQueueRecovery(f.capturesRoot, f.repo, scheduler, f.staging) { false }.run()

        f.processor.runQueue()

        assertEquals(0, f.api.calls.size) // no surprise backlog upload after the update
        assertEquals(SessionCloudStatus.AWAITING_PIPE, summary().status)

        f.repo.setPipe(id, Pipe.BLACK) // the collector files it from the Recordings screen
        f.processor.runQueue()
        assertEquals("black", f.api.createdPipes[id])
    }

    @Test
    fun aKilledTakeCanBeFiledToo() = runBlocking {
        val dir = f.sessionDir(id, status = "incomplete")
        coordinator.onSessionFinalized(id, dir, "incomplete")
        until { f.repo.session(id)?.recordingFinal == true }

        coordinator.choosePipe(id, Pipe.WHITE)
        until { f.repo.session(id)?.pipe == "white" }
        f.processor.runQueue()

        assertEquals("incomplete", f.api.completedStatuses.single())
    }

    @Test
    fun theOverviewDoesNotCallAnUnfiledRecordingWaitingOrSyncing() {
        val overview = CloudOverview.from(listOf(summary(SessionCloudStatus.AWAITING_PIPE)), SyncPolicy.WIFI_ONLY, false)
        assertEquals(CloudOverview.Kind.NONE, overview.kind)
        assertEquals(0, overview.waiting)
    }

    @Test
    fun theMenuOffersOneEntryPerPipeForAnUnfiledRecording() {
        assertEquals(CloudCardAction.CHOOSE_PIPE, CloudCardAction.forStatus(SessionCloudStatus.AWAITING_PIPE, false))
        assertEquals(CloudCardAction.INFO, CloudCardAction.forStatus(SessionCloudStatus.AWAITING_PIPE, true))
        assertEquals(listOf("Upload as White pipe", "Upload as Black pipe"), Pipe.entries.map { CloudUiText.pipeActionLabel(it) })
    }

    @Test
    fun uiTextForTheUnfiledAndFiledStates() {
        val s = summary(SessionCloudStatus.AWAITING_PIPE)
        assertEquals("Choose a pipe to upload", CloudUiText.rowStatus(s, SyncPolicy.WIFI_ONLY, false))
        assertEquals("2 need a pipe", CloudUiText.storageSummary(listOf(s, s)))
        assertEquals("Uploading to White pipe (Wi-Fi only).", CloudUiText.pipeChosenMessage(Pipe.WHITE, SyncPolicy.WIFI_ONLY, true))
        assertEquals("Black pipe: waiting for Wi-Fi to upload.", CloudUiText.pipeChosenMessage(Pipe.BLACK, SyncPolicy.WIFI_ONLY, false))
        assertTrue(CloudUiText.pipeChosenMessage(Pipe.BLACK, SyncPolicy.MANUAL_ONLY, true).contains("manual"))
    }

    @Test
    fun pipeNamesRoundTripTheWireFormat() {
        assertEquals(Pipe.WHITE, Pipe.fromWire("white"))
        assertEquals(Pipe.BLACK, Pipe.fromWire("black"))
        assertNull(Pipe.fromWire("White"))
        assertNull(Pipe.fromWire(null))
        assertEquals(listOf("White pipe", "Black pipe"), Pipe.entries.map { it.label })
    }

    private suspend fun summary() = SessionCloudSummary.from(
        f.dao.aggregates().firstOrNull { it.sessionId == id }, f.repo.session(id),
    )

    private fun summary(status: SessionCloudStatus) = SessionCloudSummary("capture-x", status, 0, 0, 0, 0, null)
}
