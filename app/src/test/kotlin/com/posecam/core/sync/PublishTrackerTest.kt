package com.posecam.core.sync

import com.posecam.core.cloud.CloudNetworkException
import com.posecam.core.cloud.CloudSessionView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PublishTrackerTest {
    private val f = SyncFixture()
    private var recording = false
    private val tracker = PublishTracker(f.repo, f.api, { f.now }, { recording })
    private val id = SyncFixture.SESSION

    private suspend fun syncedSession() {
        val dir = f.sessionDir(id)
        f.queueFinalized(dir, f.file(dir, "poses.csv", "p"))
        f.repo.uploadsForSession(id).forEach {
            f.repo.markPreparing(it.id); f.repo.markUploading(it.id); f.repo.markUploaded(it.id); f.repo.markVerified(it.id)
        }
        f.repo.markSessionSynced(id)
    }

    private fun serverSays(status: String?, sets: Int = 0, error: String? = null) {
        f.api.publishOnServer[id] = CloudSessionView(id, "d", null, null, "SYNCED", 0, 0, 0, 0, null, status, sets, null, error)
    }

    private suspend fun session() = f.repo.session(id)!!

    @Test
    fun aNewlySyncedRecordingIsPendingUntilTheBackendSaysOtherwise() = runBlocking {
        syncedSession()

        assertEquals(PublishState.PENDING, session().publishState)
        assertEquals(0, tracker.checkDue()) // asked, but the answer is still "pending": nothing changed
        assertEquals(1, f.api.callsTo("getSession"))
    }

    @Test
    fun recordsDoneWithTheNumberOfSets() = runBlocking {
        syncedSession()
        serverSays("done", sets = 2)

        assertEquals(1, tracker.checkDue())

        assertEquals(PublishState.DONE, session().publishState)
        assertEquals(2, session().publishedSets)
        assertTrue(SessionCloudSummary.from(null, session()).isLiveOnWebsite)
    }

    @Test
    fun recordsAFailureWithItsReason() = runBlocking {
        syncedSession()
        serverSays("failed", error = "ffmpeg exited 1")

        tracker.checkDue()

        assertEquals(PublishState.FAILED, session().publishState)
        assertEquals("ffmpeg exited 1", session().publishNote)
    }

    @Test
    fun aBackendWithoutPublishStatusIsUnsupportedAndNeverAskedAgain() = runBlocking {
        syncedSession()
        serverSays(null)

        tracker.checkDue()
        f.now += 60 * 60_000L
        tracker.checkDue()

        assertEquals(PublishState.UNSUPPORTED, session().publishState)
        assertEquals(1, f.api.callsTo("getSession"))
    }

    @Test
    fun asksEveryFifteenSecondsAtFirstAndEveryFiveMinutesAfterTenMinutes() = runBlocking {
        syncedSession()

        tracker.checkDue()
        f.now += 10_000
        tracker.checkDue()
        assertEquals("too soon", 1, f.api.callsTo("getSession"))
        f.now += 5_000
        tracker.checkDue()
        assertEquals("15 s later", 2, f.api.callsTo("getSession"))

        f.now += 11 * 60_000L // past the fast window
        tracker.checkDue()
        assertEquals(3, f.api.callsTo("getSession"))
        f.now += 60_000
        tracker.checkDue()
        assertEquals("slow: not again after one minute", 3, f.api.callsTo("getSession"))
        f.now += 4 * 60_000L
        tracker.checkDue()
        assertEquals("slow: again after five", 4, f.api.callsTo("getSession"))
    }

    @Test
    fun forceIgnoresTheSchedule() = runBlocking {
        syncedSession()
        tracker.checkDue()
        tracker.checkDue(force = true)
        assertEquals(2, f.api.callsTo("getSession"))
    }

    @Test
    fun stopsAskingOnceTheAnswerIsFinal() = runBlocking {
        syncedSession()
        serverSays("done", sets = 1)
        tracker.checkDue()

        f.now += 60 * 60_000L
        tracker.checkDue()

        assertEquals(1, f.api.callsTo("getSession"))
    }

    @Test
    fun keepsAskingAboutARawOnlyPublishWhileTheExportIsExpected() = runBlocking {
        syncedSession()
        f.dao.updateSession(session().copy(exportState = ExportState.DONE))
        serverSays("done", sets = 0)
        tracker.checkDue()
        assertTrue(PublishTracker.isAwaiting(session()))

        serverSays("done", sets = 1)
        f.now += 20_000
        tracker.checkDue()

        assertEquals(1, session().publishedSets)
        assertFalse(PublishTracker.isAwaiting(session()))
    }

    @Test
    fun aRawOnlyPublishIsFinalWhenNoExportCanExist() = runBlocking {
        syncedSession()
        f.dao.updateSession(session().copy(exportState = ExportState.OFF_PROTOCOL))
        serverSays("done", sets = 0)

        tracker.checkDue()

        assertFalse(PublishTracker.isAwaiting(session()))
    }

    @Test
    fun aRecordingThatIsNotSyncedIsNotAsked() = runBlocking {
        val dir = f.sessionDir(id)
        f.queueFinalized(dir, f.file(dir, "poses.csv", "p"))

        tracker.checkDue()

        assertEquals(0, f.api.callsTo("getSession"))
    }

    @Test
    fun aNetworkErrorChangesNothingAndIsRetriedOnSchedule() = runBlocking {
        syncedSession()
        serverSays("done", sets = 1)
        f.api.failOnce("getSession", CloudNetworkException(IOException("down")))

        assertEquals(0, tracker.checkDue())
        assertEquals(PublishState.PENDING, session().publishState)

        f.now += 15_000
        assertEquals(1, tracker.checkDue())
        assertEquals(PublishState.DONE, session().publishState)
    }

    @Test
    fun doesNothingWhileATakeIsBeingRecorded() = runBlocking {
        syncedSession()
        recording = true

        tracker.checkDue()

        assertEquals(0, f.api.callsTo("getSession"))
    }

    @Test
    fun syncingAgainForgetsAnEarlierWebsiteVerdict() = runBlocking {
        syncedSession()
        f.repo.recordPublish(id, PublishState.DONE, 1, null)

        f.repo.markSessionSynced(id)

        assertEquals(PublishState.PENDING, session().publishState)
        assertEquals(0, session().publishedSets)
        assertNull(session().publishNote)
    }
}
