package com.posecam.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.posecam.core.cloud.CloudLog

/**
 * WorkManager entry point: drains the Room upload queue under the configured network
 * constraint. The work is an idempotent "make progress" pass -- all state is in Room -- so being
 * stopped (constraints lost, process death) loses nothing.
 *
 * It runs while a take is being recorded: a collector filming back to back must not have to wait for a backlog, and
 * nothing has to be paused and restarted around a take. The upload threads are background priority, and the one heavy
 * job (the pipeline export) waits for Stop.
 *
 * A temporary failure (no signal, a server hiccup) is retried here, after a short delay, instead of through
 * WorkManager's backoff, which grows to hours: a collector who walks back into coverage should see uploads resume in
 * a minute or so, not tap "Sync now". When the network constraint is lost WorkManager stops this worker and runs it
 * again the moment the network is back, with no backoff at all.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = CloudSync.get(applicationContext)
        if (!sync.config.enabled) return Result.success()
        // Nobody is signed in, so nothing can be sent. Signing in schedules the queue again.
        if (!sync.auth.isSignedIn) return Result.success()
        val onlySession = inputData.getString(KEY_SESSION_ID)
        // Nothing to do (e.g. recordings still waiting for their pipe): no foreground service, no notification flicker.
        if (onlySession == null && !sync.processor.hasWork()) return Result.success()
        return try {
            // A foreground service (with its ongoing progress notification) keeps long uploads alive. Best-effort:
            // Android 12+ can refuse to start one from the background, in which case the upload continues without it.
            runCatching { setForeground(sync.notifier.foregroundInfo(null)) }
            val report = coroutineScope {
                val progress = launch { trackProgress(sync) }
                try {
                    // Background-priority threads: a recording on the same phone always wins the CPU.
                    withContext(UploadThreads.dispatcher) { runUntilSettled(sync, onlySession) }
                } finally {
                    progress.cancel()
                }
            }
            sync.notifier.clearProgress()
            // Recordings that just synced: ask now rather than on the next visit, so "Done" is not stale on opening.
            runCatching { sync.publishTracker.checkDue() }
            announce(sync, report)
            // Free space held by sessions that are fully synced and past retention; never fatal.
            runCatching { sync.retention.cleanup() }
                .onFailure { CloudLog.e("cleanup_failed", it) }
            Result.success()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A bug or an unexpected failure must not wedge the queue: back off and try again.
            CloudLog.e("worker_crashed", error)
            Result.retry()
        }
    }

    private suspend fun runUntilSettled(sync: CloudSync, onlySession: String?): QueueRunReport =
        UploadRetryLoop.run(
            runOnce = { sync.processor.runQueueReport(onlySession) },
            signedIn = { sync.auth.isSignedIn },
        )

    /** Keeps the ongoing notification current (at most about once a second). */
    @OptIn(FlowPreview::class)
    private suspend fun trackProgress(sync: CloudSync) {
        sync.repository.observeSummaries().sample(1_000).collect { summaries ->
            val overview = CloudOverview.from(summaries.values, sync.settings.policy, waitingForNetwork = false)
            if (overview.isBusy) sync.notifier.updateProgress(overview)
        }
    }

    /** Raises the user-facing events for this run: all-synced, failed, stalled. */
    private suspend fun announce(sync: CloudSync, report: QueueRunReport) {
        runCatching {
            val summaries = sync.repository.observeSummaries().first().values
            val overview = CloudOverview.from(summaries, sync.settings.policy, waitingForNetwork = false)
            sync.announcer.evaluate(overview, report.sessionsSynced, report.result).forEach { sync.notifier.announce(it) }
        }.onFailure { CloudLog.e("announce_failed", it) }
    }

    companion object {
        /** Optional input: upload only this session (the user tapped upload on its card). */
        const val KEY_SESSION_ID = "sessionId"
    }
}

/**
 * Runs the upload queue, and runs it again after a short, capped delay (15 s, doubling to 1 min) while something still has to be retried. Ends
 * when the queue is settled, when the collector signs out, or when the caller's coroutine is cancelled (WorkManager
 * stopping the worker because its network constraint was lost).
 */
internal object UploadRetryLoop {
    const val FIRST_WAIT_MS = 15_000L
    const val MAX_WAIT_MS = 60_000L

    suspend fun run(
        runOnce: suspend () -> QueueRunReport,
        signedIn: () -> Boolean,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): QueueRunReport {
        var synced = 0
        var next = FIRST_WAIT_MS
        while (true) {
            val report = runOnce()
            synced += report.sessionsSynced
            if (report.result == QueueRunResult.DONE || !signedIn()) return report.copy(sessionsSynced = synced)
            CloudLog.i("worker_retry_wait", "seconds" to next / 1000)
            wait(next)
            next = minOf(next * 2, MAX_WAIT_MS)
        }
    }
}
