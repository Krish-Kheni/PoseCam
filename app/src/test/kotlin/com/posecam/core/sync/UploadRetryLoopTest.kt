package com.posecam.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadRetryLoopTest {
    private val retry = QueueRunReport(QueueRunResult.RETRY, 0)
    private val done = QueueRunReport(QueueRunResult.DONE, 1)

    private fun script(vararg reports: QueueRunReport): suspend () -> QueueRunReport {
        val queue = reports.toMutableList()
        return { queue.removeAt(0) }
    }

    @Test
    fun aSettledQueueRunsOnce() = runBlocking {
        val waits = mutableListOf<Long>()

        val report = UploadRetryLoop.run(script(done), { true }) { waits += it }

        assertEquals(QueueRunResult.DONE, report.result)
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun aTemporaryFailureIsRetriedAfterAShortWaitNotAfterHours() = runBlocking {
        val waits = mutableListOf<Long>()

        val report = UploadRetryLoop.run(script(retry, done), { true }) { waits += it }

        assertEquals(QueueRunResult.DONE, report.result)
        assertEquals(listOf(15_000L), waits)
    }

    @Test
    fun theWaitDoublesButIsCappedAtOneMinute() = runBlocking {
        val waits = mutableListOf<Long>()

        UploadRetryLoop.run(script(retry, retry, retry, retry, retry, retry, retry, done), { true }) { waits += it }

        assertEquals(listOf(15_000L, 30_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L), waits)
    }

    @Test
    fun recordingsSyncedAcrossRetriesAreAllCounted() = runBlocking {
        val report = UploadRetryLoop.run(
            script(QueueRunReport(QueueRunResult.RETRY, 2), QueueRunReport(QueueRunResult.DONE, 1)), { true }) { }

        assertEquals(3, report.sessionsSynced)
    }

    @Test
    fun signingOutEndsTheLoopInsteadOfRetryingForever() = runBlocking {
        var signedIn = true

        val report = UploadRetryLoop.run(script(retry, retry), { signedIn }) { signedIn = false }

        assertEquals(QueueRunResult.RETRY, report.result)
    }
}
