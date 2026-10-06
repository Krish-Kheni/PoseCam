package com.posecam.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudUiTextTest {
    private fun summary(status: SessionCloudStatus, done: Long = 0, total: Long = 0, verified: Int = 0, files: Int = 0) =
        SessionCloudSummary("capture-x", status, files, verified, total, done, null)

    private fun row(s: SessionCloudSummary?, policy: SyncPolicy = SyncPolicy.WIFI_ONLY, waiting: Boolean = false) =
        CloudUiText.rowStatus(s, policy, waiting)

    @Test fun everyStatusHasAHumanLine() {
        assertEquals("On this phone only", row(null))
        assertEquals("On this phone only", row(summary(SessionCloudStatus.LOCAL_ONLY)))
        assertEquals("Queued for upload", row(summary(SessionCloudStatus.PENDING)))
        assertEquals("Verifying…", row(summary(SessionCloudStatus.VERIFYING)))
        assertEquals("Synced", row(summary(SessionCloudStatus.SYNCED)))
        assertEquals("Upload failed, tap to retry", row(summary(SessionCloudStatus.FAILED)))
    }

    @Test fun pendingWorkThatCannotStartReadsWaitingForWifi() {
        assertEquals("Waiting for Wi-Fi", row(summary(SessionCloudStatus.PENDING), waiting = true))
    }

    @Test fun manualModeSaysSoInsteadOfPromisingAnUpload() {
        assertEquals("Not uploaded (manual mode)", row(summary(SessionCloudStatus.PENDING), SyncPolicy.MANUAL_ONLY))
    }

    @Test fun uploadingShowsPercentAndFiles() {
        assertEquals("Uploading 45% · 3 of 9 files", row(summary(SessionCloudStatus.UPLOADING, done = 45, total = 100, verified = 3, files = 9)))
    }

    @Test fun onlyStartAndRetryHaveAMenuAction() {
        assertEquals("Upload to cloud now", CloudUiText.actionLabel(CloudCardAction.START))
        assertEquals("Retry failed upload", CloudUiText.actionLabel(CloudCardAction.RETRY))
        assertNull(CloudUiText.actionLabel(CloudCardAction.INFO))
    }

    @Test fun deleteWarningTellsTheTruthAboutTheCloud() {
        assertTrue(CloudUiText.deleteWarning(false, null).contains("shared or saved first")) // exactly the original wording
        assertTrue(CloudUiText.deleteWarning(true, summary(SessionCloudStatus.SYNCED)).contains("synced to the cloud"))
        assertTrue(CloudUiText.deleteWarning(true, summary(SessionCloudStatus.UPLOADING)).contains("only copy"))
        assertTrue(CloudUiText.deleteWarning(true, null).contains("only copy"))
    }

    @Test fun storageSummaryCountsEachGroupAndStaysEmptyWhenThereIsNothing() {
        assertEquals("", CloudUiText.storageSummary(emptyList()))
        assertEquals("", CloudUiText.storageSummary(listOf(summary(SessionCloudStatus.LOCAL_ONLY))))
        assertEquals(
            "2 synced, 3 waiting, 1 failed",
            CloudUiText.storageSummary(
                listOf(SessionCloudStatus.SYNCED, SessionCloudStatus.SYNCED, SessionCloudStatus.PENDING, SessionCloudStatus.UPLOADING,
                    SessionCloudStatus.WAITING_FOR_WIFI, SessionCloudStatus.FAILED).map { summary(it) },
            ),
        )
    }

    @Test fun labelsForSettings() {
        assertEquals("Wi-Fi only", CloudUiText.policyLabel(SyncPolicy.WIFI_ONLY))
        assertEquals("off", CloudUiText.retentionLabel(false, 7))
        assertEquals("after 1 day", CloudUiText.retentionLabel(true, 1))
        assertEquals("after 7 days", CloudUiText.retentionLabel(true, 7))
    }

    @Test fun mobileDataMessageNamesTheSizeWhenKnown() {
        assertTrue(CloudUiText.mobileDataMessage(200L * 1024 * 1024).contains("200 MB"))
        assertTrue(CloudUiText.mobileDataMessage(0).contains("this recording"))
    }
}
