package com.posecam.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudOverviewTest {
    private fun summary(status: SessionCloudStatus, done: Long = 0, total: Long = 0) =
        SessionCloudSummary("s${status.name}${done}", status, 4, 1, total, done, null)

    private fun overview(vararg s: SessionCloudSummary, policy: SyncPolicy = SyncPolicy.WIFI_ONLY, waiting: Boolean = false) =
        CloudOverview.from(s.toList(), policy, waiting)

    @Test fun nothingToReportWhenEverythingIsSyncedOrLocal() {
        assertEquals(CloudOverview.Kind.NONE, overview(summary(SessionCloudStatus.SYNCED), summary(SessionCloudStatus.LOCAL_ONLY)).kind)
        assertEquals("All recordings synced", overview(summary(SessionCloudStatus.SYNCED)).message)
    }

    @Test fun countsSyncingSessionsAndTheirBytes() {
        val o = overview(summary(SessionCloudStatus.UPLOADING, 100, 400), summary(SessionCloudStatus.VERIFYING, 50, 50), summary(SessionCloudStatus.UPLOADING, 1, 3))
        assertEquals("Uploading 3 recordings", o.message)
        assertEquals(151L, o.transferredBytes)
        assertEquals(453L, o.totalBytes)
    }

    @Test fun queuedSessionsAreNotAddedToTheBytesOfTheOneUploading() {
        val o = overview(summary(SessionCloudStatus.UPLOADING, 50, 120), summary(SessionCloudStatus.PENDING, 0, 300))
        assertEquals("Uploading 1 recording", o.message)
        assertEquals(50L, o.transferredBytes)
        assertEquals(120L, o.totalBytes)
    }

    @Test fun waitingSessionsShowTheirOwnBytesWhenNothingIsTransferring() {
        val o = overview(summary(SessionCloudStatus.PENDING, 0, 300), summary(SessionCloudStatus.WAITING_FOR_WIFI, 0, 100))
        assertEquals(400L, o.totalBytes)
    }

    @Test fun failureOutranksEverythingElse() {
        val o = overview(summary(SessionCloudStatus.FAILED), summary(SessionCloudStatus.UPLOADING))
        assertEquals(CloudOverview.Kind.FAILED, o.kind)
        assertEquals("1 upload failed", o.message)
    }

    @Test fun waitingWordingFollowsPolicyAndConnectivity() {
        val pending = summary(SessionCloudStatus.PENDING)
        assertEquals("Waiting for Wi-Fi · 2 recordings", overview(pending, summary(SessionCloudStatus.WAITING_FOR_WIFI), waiting = true).message)
        assertEquals("1 recording queued", overview(pending).message)
        assertEquals("1 recording waiting · manual", overview(pending, policy = SyncPolicy.MANUAL_ONLY).message)
    }
}
