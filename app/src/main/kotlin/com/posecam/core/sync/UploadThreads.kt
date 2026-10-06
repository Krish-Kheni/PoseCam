package com.posecam.core.sync

import android.os.Process
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * All upload work -- hashing, file reads, TLS and socket I/O -- runs on threads at
 * [Process.THREAD_PRIORITY_BACKGROUND], so the scheduler always favours the foreground UI and anything else
 * that is running (uploads never overlap a take, but the user may be browsing recordings meanwhile).
 */
object UploadThreads {
    private val counter = AtomicInteger()

    private val factory = ThreadFactory { runnable ->
        Thread(
            {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runnable.run()
            },
            "posecam-upload-${counter.incrementAndGet()}",
        ).apply { isDaemon = true }
    }

    /** Same shape as OkHttp's default dispatcher pool, but with background-priority threads. */
    val executor: ThreadPoolExecutor = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(), factory)

    val dispatcher = executor.asCoroutineDispatcher()
}

/**
 * Optional cap on upload throughput; `null` means unlimited. PoseCam does not throttle: it never uploads
 * while a take is being recorded (see [UploadCoordinator]), so there is nothing to share the CPU with.
 */
fun interface UploadThrottle {
    fun maxBytesPerSecond(): Long?

    companion object {
        val None = UploadThrottle { null }
    }
}
