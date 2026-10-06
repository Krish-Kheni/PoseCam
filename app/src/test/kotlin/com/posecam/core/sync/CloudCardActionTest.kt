package com.posecam.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCardActionTest {
    private fun action(status: SessionCloudStatus, recording: Boolean = false) = CloudCardAction.forStatus(status, recording)

    @Test fun notYetUploadedSessionsStartOnTap() {
        listOf(SessionCloudStatus.LOCAL_ONLY, SessionCloudStatus.PENDING, SessionCloudStatus.WAITING_FOR_WIFI)
            .forEach { assertEquals(it.name, CloudCardAction.START, action(it)) }
    }

    @Test fun failedSessionsRetryOnTap() {
        assertEquals(CloudCardAction.RETRY, action(SessionCloudStatus.FAILED))
    }

    @Test fun inFlightAndFinishedSessionsOnlyExplainTheirState() {
        listOf(SessionCloudStatus.UPLOADING, SessionCloudStatus.VERIFYING, SessionCloudStatus.SYNCED)
            .forEach { assertEquals(it.name, CloudCardAction.INFO, action(it)) }
    }

    @Test fun aSessionStillBeingRecordedNeverStartsAnUpload() {
        assertEquals(CloudCardAction.INFO, action(SessionCloudStatus.PENDING, recording = true))
        assertEquals(CloudCardAction.INFO, action(SessionCloudStatus.FAILED, recording = true))
    }

    @Test fun infoMessagesShowProgress() {
        val summary = SessionCloudSummary("s", SessionCloudStatus.UPLOADING, 9, 3, 1000, 450, null)
        assertEquals("Uploading 45% · 3 of 9 files", CloudCardAction.infoMessage(SessionCloudStatus.UPLOADING, summary, false))
        assertEquals("Uploading", CloudCardAction.infoMessage(SessionCloudStatus.UPLOADING, null, false))
        assertEquals("Synced to the cloud", CloudCardAction.infoMessage(SessionCloudStatus.SYNCED, null, false))
        assertEquals("Uploads start after this recording finishes", CloudCardAction.infoMessage(SessionCloudStatus.PENDING, null, true))
    }
}

class MobileDataGuardTest {
    @Test fun asksOnlyOnAMeteredNetworkWhenTheUserHasNotOptedOut() {
        assertTrue(MobileDataGuard.needsConfirmation(SyncPolicy.WIFI_ONLY, onMeteredNetwork = true, confirmEnabled = true))
        assertTrue(MobileDataGuard.needsConfirmation(SyncPolicy.MANUAL_ONLY, onMeteredNetwork = true, confirmEnabled = true))
    }

    @Test fun neverAsksOnWifiOrWhenOptedOutOrWhenMobileDataIsAllowed() {
        assertFalse(MobileDataGuard.needsConfirmation(SyncPolicy.WIFI_ONLY, onMeteredNetwork = false, confirmEnabled = true))
        assertFalse(MobileDataGuard.needsConfirmation(SyncPolicy.WIFI_ONLY, onMeteredNetwork = true, confirmEnabled = false))
        assertFalse(MobileDataGuard.needsConfirmation(SyncPolicy.ANY_NETWORK, onMeteredNetwork = true, confirmEnabled = true))
    }
}

class PrioritizeSessionTest {
    private val f = SyncFixture()

    private suspend fun queue(id: String): java.io.File {
        val dir = f.sessionDir(id)
        f.queueFinalized(dir, f.file(dir, "manifest.json", "{}"), f.file(dir, "intrinsics.json", "{}"))
        f.now += 10
        return dir
    }

    @Test
    fun aFullRunStillUploadsEverythingAfterPrioritising() = runBlocking {
        queue("oldest"); queue("newest")
        f.repo.prioritizeSession("newest")

        f.processor.runQueue()

        assertTrue(f.repo.uploadsForSession("oldest").all { it.state == UploadState.VERIFIED })
        assertTrue(f.repo.uploadsForSession("newest").all { it.state == UploadState.VERIFIED })
        assertEquals(4, f.api.verified.size)
    }

    @Test
    fun theFirstFilePickedBelongsToThePrioritisedSession() = runBlocking {
        queue("a"); queue("b"); queue("c")
        f.repo.prioritizeSession("c")

        val first = f.repo.runnable().first()

        assertEquals("c", first.sessionId)
        // Other sessions keep their relative order behind it.
        assertEquals(listOf("c", "c", "a", "a", "b", "b"), f.repo.runnable().map { it.sessionId })
    }

    @Test
    fun prioritisingTwiceOrAnAlreadyFirstSessionIsHarmless() = runBlocking {
        queue("a"); queue("b")
        f.repo.prioritizeSession("a")
        f.repo.prioritizeSession("a")
        f.repo.prioritizeSession("b")

        assertEquals(listOf("b", "b", "a", "a"), f.repo.runnable().map { it.sessionId })
    }

    @Test
    fun finishedFilesAreNotReorderedAndNothingBreaksForAnUnknownSession() = runBlocking {
        queue("a")
        f.repo.prioritizeSession("does-not-exist")
        assertEquals(2, f.repo.runnable().size)
    }

    @Test
    fun aRunScopedToOneSessionUploadsOnlyThatSession() = runBlocking {
        queue("a"); queue("b"); queue("c")

        assertEquals(QueueRunResult.DONE, f.processor.runQueue(onlySessionId = "b"))

        assertTrue(f.repo.uploadsForSession("b").all { it.state == UploadState.VERIFIED })
        assertTrue(f.repo.uploadsForSession("a").none { it.state == UploadState.VERIFIED })
        assertTrue(f.repo.uploadsForSession("c").none { it.state == UploadState.VERIFIED })
        assertEquals(2, f.api.verified.size)
        assertEquals(1, f.api.completedSessions.size)

        // The rest is still queued for a normal run.
        f.processor.runQueue()
        assertEquals(6, f.api.verified.size)
    }
}
