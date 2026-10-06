package com.posecam.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAnnouncerTest {
    private class MemoryStore : AnnouncementStore {
        override var unannouncedSynced = 0
        override var notifiedFailed = 0
        override var stallSinceMs: Long? = null
        override var stalledNotified = false
    }

    private var now = 0L
    private val store = MemoryStore()
    private val announcer = SyncAnnouncer(store) { now }

    private fun overview(syncing: Int = 0, waiting: Int = 0, failed: Int = 0, synced: Int = 0) = CloudOverview(
        syncing = syncing, waiting = waiting, failed = failed, synced = synced, transferredBytes = 0, totalBytes = 0,
        policy = SyncPolicy.WIFI_ONLY, waitingForNetwork = false,
    )

    @Test fun announcesAllSyncedOnceThenStaysQuiet() {
        val first = announcer.evaluate(overview(synced = 3), newlySyncedSessions = 3, result = QueueRunResult.DONE)
        assertEquals(listOf<CloudSyncEvent>(CloudSyncEvent.AllSynced(3)), first)

        assertTrue(announcer.evaluate(overview(synced = 3), 0, QueueRunResult.DONE).isEmpty())
    }

    @Test fun waitsUntilTheWholeBacklogIsDoneAndAccumulatesAcrossRuns() {
        // Run 1 is cut short (e.g. the 10-minute worker limit) with sessions still queued.
        assertTrue(announcer.evaluate(overview(syncing = 1, synced = 2), 2, QueueRunResult.RETRY).isEmpty())
        // Run 2 finishes the rest: one announcement for all three.
        val done = announcer.evaluate(overview(synced = 3), 1, QueueRunResult.DONE)
        assertEquals(listOf<CloudSyncEvent>(CloudSyncEvent.AllSynced(3)), done)
    }

    @Test fun doesNotClaimAllSyncedWhileSomethingIsWaitingOrFailed() {
        assertTrue(announcer.evaluate(overview(waiting = 1, synced = 1), 1, QueueRunResult.DONE).isEmpty())
        val failedRun = announcer.evaluate(overview(failed = 1, synced = 1), 0, QueueRunResult.DONE)
        assertEquals(listOf<CloudSyncEvent>(CloudSyncEvent.Failed(1)), failedRun)
    }

    @Test fun aFailureIsAnnouncedWhenTheCountRisesNotOnEveryRun() {
        assertEquals(listOf<CloudSyncEvent>(CloudSyncEvent.Failed(1)), announcer.evaluate(overview(failed = 1), 0, QueueRunResult.DONE))
        assertTrue(announcer.evaluate(overview(failed = 1), 0, QueueRunResult.DONE).isEmpty())
        assertEquals(listOf<CloudSyncEvent>(CloudSyncEvent.Failed(2)), announcer.evaluate(overview(failed = 2), 0, QueueRunResult.DONE))
    }

    @Test fun aFailureThatIsResolvedAndLaterRepeatsIsAnnouncedAgain() {
        announcer.evaluate(overview(failed = 1), 0, QueueRunResult.DONE)
        announcer.evaluate(overview(), 0, QueueRunResult.DONE) // user retried, failure cleared
        assertEquals(listOf<CloudSyncEvent>(CloudSyncEvent.Failed(1)), announcer.evaluate(overview(failed = 1), 0, QueueRunResult.DONE))
    }

    @Test fun temporaryFailuresNeverAlertUntilAStreakOfHours() {
        val hour = 3_600_000L
        assertTrue(announcer.evaluate(overview(waiting = 1), 0, QueueRunResult.RETRY).isEmpty()) // streak starts
        now += hour
        assertTrue(announcer.evaluate(overview(waiting = 1), 0, QueueRunResult.RETRY).isEmpty())
        now += 2 * hour
        assertEquals(listOf<CloudSyncEvent>(CloudSyncEvent.Stalled), announcer.evaluate(overview(waiting = 1), 0, QueueRunResult.RETRY))
        now += hour
        assertTrue("announced only once", announcer.evaluate(overview(waiting = 1), 0, QueueRunResult.RETRY).isEmpty())
    }

    @Test fun aSuccessfulRunResetsTheStall() {
        val hour = 3_600_000L
        announcer.evaluate(overview(waiting = 1), 0, QueueRunResult.RETRY)
        now += 5 * hour
        announcer.evaluate(overview(), 0, QueueRunResult.DONE)
        assertEquals(null, store.stallSinceMs)
        now += hour
        assertTrue(announcer.evaluate(overview(waiting = 1), 0, QueueRunResult.RETRY).isEmpty())
    }
}

class CloudEventRouterTest {
    @Test fun appOnScreenGetsASnackbarAndNeverANotification() {
        val route = CloudEventRouter.route(CloudSyncEvent.AllSynced(3), appInForeground = true)
        assertEquals(EventRoute.Snackbar("All recordings synced (3 sessions)", null), route)
    }

    @Test fun appAwayGetsANotificationAndNeverASnackbar() {
        val route = CloudEventRouter.route(CloudSyncEvent.AllSynced(1), appInForeground = false) as EventRoute.Notification
        assertEquals(CloudEventRouter.NOTIFICATION_ALL_SYNCED, route.id)
        assertEquals("All recordings synced (1 session)", route.text)
    }

    @Test fun failureAndStallOfferTheRightAction() {
        assertEquals(SnackbarAction.RETRY, (CloudEventRouter.route(CloudSyncEvent.Failed(2), true) as EventRoute.Snackbar).action)
        assertEquals(SnackbarAction.SYNC_NOW, (CloudEventRouter.route(CloudSyncEvent.Stalled, true) as EventRoute.Snackbar).action)
        assertEquals("Sync failed for 2 sessions. Tap to retry.", (CloudEventRouter.route(CloudSyncEvent.Failed(2), false) as EventRoute.Notification).text)
    }

    @Test fun eachEventUsesItsOwnNotificationId() {
        val ids = listOf(CloudSyncEvent.AllSynced(1), CloudSyncEvent.Failed(1), CloudSyncEvent.Stalled)
            .map { (CloudEventRouter.route(it, false) as EventRoute.Notification).id }
        assertEquals(3, ids.toSet().size)
    }
}

class CloudProgressTextTest {
    private fun summary(status: SessionCloudStatus, verified: Int, total: Int, done: Long, size: Long) =
        SessionCloudSummary("s$verified$total", status, total, verified, size, done, null)

    @Test fun progressCoversOnlyTheSessionsBeingTransferredNotQueuedOnes() {
        val overview = CloudOverview.from(
            listOf(
                summary(SessionCloudStatus.UPLOADING, 3, 9, 120L * 1024 * 1024, 560L * 1024 * 1024),
                summary(SessionCloudStatus.PENDING, 0, 4, 0, 440L * 1024 * 1024),
                summary(SessionCloudStatus.SYNCED, 5, 5, 99, 99),
            ),
            SyncPolicy.WIFI_ONLY, false,
        )
        assertEquals("3 of 9 files · 120 MB / 560 MB", overview.progressText)
        assertEquals(21, overview.progressPercent)
    }

    @Test fun unknownTotalIsIndeterminate() {
        assertEquals(-1, CloudOverview.from(emptyList(), SyncPolicy.WIFI_ONLY, false).progressPercent)
    }
}
