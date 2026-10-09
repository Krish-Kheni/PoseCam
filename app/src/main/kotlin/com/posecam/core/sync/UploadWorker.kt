package com.posecam.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.posecam.core.cloud.CloudLog

/**
 * WorkManager entry point: drains the Room upload queue under the configured network
 * constraint. The work is an idempotent "make progress" pass -- all state is in Room -- so being
 * stopped (constraints lost, 10-minute limit, process death) loses nothing; WorkManager's
 * exponential backoff handles temporary failures.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = CloudSync.get(applicationContext)
        if (!sync.config.enabled) return Result.success()
        // A take is being recorded (this covers a worker that started in the gap before it was cancelled). The
        // chain is rescheduled when the take is finalized; the queue itself is untouched.
        if (ActiveRecordingSessions.snapshot().isNotEmpty()) return Result.success()
        // Nothing to do (e.g. recordings still waiting for their pipe): no foreground service, no notification flicker.
        if (inputData.getString(KEY_SESSION_ID) == null && !sync.processor.hasWork()) return Result.success()
        return try {
            // A foreground service (with its ongoing progress notification) keeps long uploads alive. PoseCam has no
            // recording service and never uploads during a take, so this never overlaps a recording. Best-effort:
            // Android 12+ can refuse to start one from the background, in which case the upload continues without it.
            runCatching { setForeground(sync.notifier.foregroundInfo(null)) }
            val report = coroutineScope {
                val progress = launch { trackProgress(sync) }
                try {
                    // Background-priority threads: a recording on the same phone always wins the CPU.
                    withContext(UploadThreads.dispatcher) { sync.processor.runQueueReport(inputData.getString(KEY_SESSION_ID)) }
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
            when (report.result) {
                QueueRunResult.DONE -> Result.success()
                QueueRunResult.RETRY -> Result.retry()
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A bug or an unexpected failure must not wedge the queue: back off and try again.
            CloudLog.e("worker_crashed", error)
            Result.retry()
        }
    }

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
