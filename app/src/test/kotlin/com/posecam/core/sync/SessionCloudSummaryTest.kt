package com.posecam.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCloudSummaryTest {
    private fun session(
        synced: Long? = null, final: Boolean = true, failed: Boolean = false, created: Boolean = true, pipe: String? = "white",
    ) = CloudSessionEntity("s", "/x", "complete", final, created, failed, synced, null, 0, 0, pipe)

    private fun agg(total: Int = 4, verified: Int = 0, failed: Int = 0, active: Int = 0, awaiting: Int = 0) =
        SessionUploadAggregate("s", total, verified, failed, active, awaiting, 400, 100, null)

    private fun status(a: SessionUploadAggregate?, s: CloudSessionEntity?) = SessionCloudSummary.from(a, s).status

    @Test fun pendingBecomesWaitingForWifiOnlyWhenTheNetworkDoesNotQualify() {
        val summary = SessionCloudSummary.from(agg(), session())
        assertEquals(SessionCloudStatus.PENDING, summary.displayStatus(waitingForNetwork = false))
        assertEquals(SessionCloudStatus.WAITING_FOR_WIFI, summary.displayStatus(waitingForNetwork = true))
    }

    @Test fun activeTransferIsUploadingAndNeverWaitingForWifi() {
        val summary = SessionCloudSummary.from(agg(active = 1), session())
        assertEquals(SessionCloudStatus.UPLOADING, summary.displayStatus(waitingForNetwork = true))
    }

    @Test fun everythingUploadedButNotConfirmedIsVerifying() {
        assertEquals(SessionCloudStatus.VERIFYING, status(agg(active = 2, awaiting = 2), session()))
        assertEquals(SessionCloudStatus.VERIFYING, status(agg(total = 4, verified = 4), session()))
    }

    @Test fun syncedNeedsBackendConfirmationAndAllFilesVerified() {
        assertEquals(SessionCloudStatus.SYNCED, status(agg(verified = 4), session(synced = 5)))
        // Re-queued file after sync: no longer synced.
        assertEquals(SessionCloudStatus.PENDING, status(agg(verified = 3), session(synced = 5)))
    }

    @Test fun anyFailureOrRefusedSessionIsFailed() {
        assertEquals(SessionCloudStatus.FAILED, status(agg(failed = 1), session()))
        assertEquals(SessionCloudStatus.FAILED, status(null, session(failed = true)))
    }

    @Test fun aSessionWithOnlyAPendingCreationIsPending() {
        assertEquals(SessionCloudStatus.PENDING, status(null, session(created = false, final = false)))
    }

    @Test fun aRecordingWithoutAPipeAwaitsTheCollectorsChoiceAndNothingElse() {
        assertEquals(SessionCloudStatus.AWAITING_PIPE, status(agg(), session(created = false, pipe = null)))
        assertEquals(SessionCloudStatus.AWAITING_PIPE, status(null, session(created = false, final = false, pipe = null)))
    }

    @Test fun choosingAPipeMakesItPendingAndAFailureOrSyncStillWins() {
        assertEquals(SessionCloudStatus.PENDING, status(agg(), session(created = false, pipe = "black")))
        assertEquals(SessionCloudStatus.FAILED, status(agg(failed = 1), session(created = false, pipe = null)))
        assertEquals(SessionCloudStatus.SYNCED, status(agg(verified = 4), session(synced = 1, created = false, pipe = null)))
    }

    @Test fun theChosenPipeIsCarriedToTheUi() {
        assertEquals("white", SessionCloudSummary.from(agg(), session()).pipe)
        assertEquals(null, SessionCloudSummary.from(agg(), session(pipe = null, created = false)).pipe)
    }
}
