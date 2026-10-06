package com.posecam.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Asks the platform to run the upload queue. Abstracted so the coordinator can be unit-tested. */
interface UploadScheduler {
    /** Make sure a worker will run the queue when the network policy allows. Cheap and idempotent. */
    suspend fun schedule()

    /**
     * The user asked to sync now: run on any connected network, regardless of the policy. With [sessionId]
     * only that session is uploaded; the rest of the queue is left to the policy (or "Sync now").
     */
    suspend fun syncNow(sessionId: String? = null)

    /** The network policy changed: replace any waiting work so it picks up the new constraints. */
    suspend fun reschedule()

    /**
     * A take started: cancel every upload chain. Cancelling is safe at any instant: rows keep their state,
     * multipart progress is persisted per part, and a single PUT of a chunk (about 35 MB) restarts from zero.
     * [schedule] resumes everything after the take.
     */
    suspend fun pause()
}

/**
 * WorkManager-backed scheduler. There is exactly ONE unique chain ([UNIQUE_WORK_NAME]), so duplicate
 * workers can never run concurrently, and files are uploaded one after another rather than all at
 * once. Each worker run drains the Room queue; upload state is per file in Room, never per job.
 *
 * New work is appended (APPEND_OR_REPLACE) rather than "kept": a file that finalizes while a worker
 * is on its last loop would otherwise be missed. Appends are coalesced, so waiting on a long
 * upload never builds a pile of redundant nodes.
 */
class WorkManagerUploadScheduler(
    context: Context,
    private val settings: CloudSyncSettings,
) : UploadScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override suspend fun schedule() {
        val policy = settings.policy
        if (policy == SyncPolicy.MANUAL_ONLY) return
        val infos = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()
        val waiting = infos.any { (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED) && MANUAL_TAG !in it.tags }
        // A node that has not started yet will see the new rows when it does.
        if (waiting) return
        enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE, requireNotNull(policy.networkType))
    }

    override suspend fun syncNow(sessionId: String?) {
        if (sessionId != null) {
            syncOneSession(sessionId)
            return
        }
        val infos = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME).first()
        if (infos.any { it.state == WorkInfo.State.RUNNING }) {
            // An upload is in flight: don't cancel it (that would restart a large PUT). The running pass picks the
            // next file from the queue, and one more pass is queued behind it in case it is about to finish.
            if (infos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
                enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE, NetworkType.CONNECTED)
            }
            return
        }
        enqueue(ExistingWorkPolicy.REPLACE, NetworkType.CONNECTED)
    }

    /**
     * Its own chain, so a worker waiting for Wi-Fi on the main chain can't hold a manual upload back, and
     * a manual upload never cancels the main chain. Uploads themselves are serialized in the processor.
     */
    private fun syncOneSession(sessionId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_SESSION_ID to sessionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(MANUAL_TAG)
            .build()
        workManager.enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override suspend fun pause() {
        workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(MANUAL_WORK_NAME)
    }

    override suspend fun reschedule() {
        val networkType = settings.policy.networkType
        if (networkType == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        } else {
            enqueue(ExistingWorkPolicy.REPLACE, networkType)
        }
    }

    private fun enqueue(existing: ExistingWorkPolicy, networkType: NetworkType) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(networkType)
                    // A big backlog should not drain a nearly flat battery; a user's explicit "upload now" ignores this.
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            // Temporary failures retry forever with growing delays (WorkManager caps the backoff at 5 h).
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, existing, request)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "posecam-upload-queue"
        const val MANUAL_WORK_NAME = "posecam-upload-session"
        const val TAG = "posecam-upload"
        const val MANUAL_TAG = "posecam-upload-session"
        private const val BACKOFF_SECONDS = 30L
    }
}
