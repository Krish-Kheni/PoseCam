package com.posecam.core.sync

import com.posecam.SessionFileListener
import com.posecam.core.cloud.CloudLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Bridges recording and the upload queue. It is the [SessionFileListener] handed to
 * [com.posecam.PoseRecorder]: the recording thread only calls [Channel.trySend] (never blocks, never
 * touches Room or the network); a single background consumer then does the database work in event
 * order and talks to the scheduler.
 *
 * PoseCam never uploads while a take is being recorded: its known failure mode under load is dropped
 * images (`queue_full`), and tracking quality depends on CPU and heat headroom. So `onSessionStarted`
 * pauses the upload chain and `onSessionFinalized` resumes it.
 */
class UploadCoordinator(
    private val repository: UploadRepository,
    private val scheduler: UploadScheduler,
    private val staging: UploadStaging,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SessionFileListener {

    private sealed interface Event {
        data class Started(val sessionId: String, val directory: File) : Event
        data class Finalized(val sessionId: String, val directory: File, val status: String) : Event
        data class ChoosePipe(val sessionId: String, val pipe: Pipe) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    // The queue is rebuilt from the session directories by UploadQueueRecovery on
                    // the next launch, so a failed enqueue is never a permanent loss.
                    CloudLog.e("enqueue_failed", error)
                }
            }
        }
    }

    // ---- SessionFileListener (called on the recording thread: must not block) --------------

    override fun onSessionStarted(sessionId: String, directory: File) {
        // Synchronous and cheap: the upload processor checks this set before every file, so uploads
        // stop at the next file boundary even before the consumer below has cancelled the worker.
        ActiveRecordingSessions.add(sessionId)
        events.trySend(Event.Started(sessionId, directory))
    }

    override fun onSessionFinalized(sessionId: String, directory: File, recordingStatus: String) {
        events.trySend(Event.Finalized(sessionId, directory, recordingStatus))
    }

    // ---- actions ---------------------------------------------------------------------------

    /**
     * The collector chose where this recording goes. Sent through the same ordered channel as the recorder's events, so
     * it can never overtake "started" / "finalized"; uploading of this session begins as soon as it is handled.
     */
    fun choosePipe(sessionId: String, pipe: Pipe) {
        events.trySend(Event.ChoosePipe(sessionId, pipe))
    }

    fun requestSync() {
        scope.launch { scheduler.syncNow() }
    }

    /** The user tapped upload on one session: upload just that session now, whatever the policy. */
    fun syncSession(sessionId: String, retryFailedFiles: Boolean = false) {
        scope.launch {
            if (retryFailedFiles) repository.retryFailed(sessionId)
            // Also first in line, so a whole-queue run that happens to be going picks it up next.
            repository.prioritizeSession(sessionId)
            scheduler.syncNow(sessionId)
        }
    }

    fun retryFailed(sessionId: String?) {
        scope.launch {
            repository.retryFailed(sessionId)
            scheduler.syncNow(sessionId)
        }
    }

    fun onNetworkPolicyChanged() {
        scope.launch { scheduler.reschedule() }
    }

    fun onSessionDeletedLocally(sessionId: String) {
        scope.launch { repository.forgetSession(sessionId) }
    }

    // ---- event handling --------------------------------------------------------------------

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Started -> {
                // Pause first: stopping the upload chain matters more than the bookkeeping below.
                scheduler.pause()
                repository.registerSession(event.sessionId, event.directory)
            }
            is Event.Finalized -> {
                val failure = runCatching {
                    val files = UploadPlan.forSession(event.sessionId, event.directory, staging)
                    repository.finalizeSession(event.sessionId, event.directory, event.status, files)
                }.exceptionOrNull()
                // Cleared only after the final rows are queued, so recovery never races a half-enqueued
                // session -- but cleared even if queueing failed, or uploads would stay blocked until the app
                // restarts (recovery re-queues the session then).
                ActiveRecordingSessions.remove(event.sessionId)
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (failure != null) CloudLog.e("finalize_failed", failure, "session" to event.sessionId)
                scheduler.schedule()
            }
            is Event.ChoosePipe -> {
                if (repository.setPipe(event.sessionId, event.pipe)) scheduler.schedule()
            }
        }
    }
}
