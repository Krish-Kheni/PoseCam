package com.posecam.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.posecam.core.cloud.CloudLog
import java.util.concurrent.TimeUnit

/**
 * A safety net under the upload chain: every 15 minutes, if recordings are waiting and no upload worker is queued
 * for them, queue one. The chain normally keeps itself alive (each finished take schedules it, a lost network
 * resumes it), so this only matters after the unexpected -- a process killed at the wrong moment, a worker the system
 * dropped -- which used to leave a collector tapping "Sync now" to get uploads moving again.
 */
class UploadWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = CloudSync.get(applicationContext)
        if (!sync.config.enabled || !sync.auth.isSignedIn) return Result.success()
        runCatching {
            if (sync.processor.hasWork()) {
                CloudLog.i("watchdog_schedule")
                sync.coordinator.resumeUploads()
            }
        }
        return Result.success()
    }
}

object UploadWatchdog {
    private const val NAME = "posecam-upload-watchdog"

    /** Idempotent: an existing schedule is kept, so calling this on every launch costs nothing. */
    fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<UploadWatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
