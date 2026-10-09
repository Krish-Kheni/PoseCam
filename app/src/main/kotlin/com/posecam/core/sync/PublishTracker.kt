package com.posecam.core.sync

import com.posecam.core.cloud.CloudApi
import com.posecam.core.cloud.CloudException
import com.posecam.core.cloud.CloudLog
import com.posecam.core.cloud.CloudSessionView
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Learns whether the backend has published a synced recording to the website, so the Recordings screen can say "Done"
 * only once the card is really there (SYNCED only means the files arrived).
 *
 * It asks the backend (`GET /v1/sessions/{id}`) and stores the answer on the session row. Each unfinished recording is
 * asked every [FAST_INTERVAL_MS] for its first [FAST_WINDOW_MS] after syncing, then every [SLOW_INTERVAL_MS] (publishing
 * normally takes a minute or two, but a busy or restarted backend can take longer, and a failed one may be fixed later).
 * A recording whose answer is final is never asked again. Nothing here uploads anything or changes a file, and every
 * failure is swallowed: a missed check just means the next one happens later.
 */
class PublishTracker(
    private val repository: UploadRepository,
    private val api: CloudApi,
    private val clock: () -> Long = System::currentTimeMillis,
    /** True while a take is recorded: no network work then, exactly like the upload queue. */
    private val isRecording: () -> Boolean = { ActiveRecordingSessions.snapshot().isNotEmpty() },
) {
    private val lastChecked = ConcurrentHashMap<String, Long>()
    private val lock = Mutex()

    /** Asks about every recording that is due; returns how many changed their answer. [force] ignores the schedule. */
    suspend fun checkDue(force: Boolean = false): Int = lock.withLock {
        if (isRecording()) return 0
        var changed = 0
        for (session in repository.allSessions()) {
            if (!isAwaiting(session)) {
                lastChecked.remove(session.sessionId)
                continue
            }
            val now = clock()
            val last = lastChecked[session.sessionId]
            if (!force && last != null && now - last < intervalFor(session, now)) continue
            lastChecked[session.sessionId] = now
            val view = try {
                api.getSession(session.sessionId)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: CloudException) {
                CloudLog.w("publish_check_failed", "session" to session.sessionId, "error" to error.message)
                continue
            }
            val state = stateOf(view)
            val note = view.publishError.takeIf { state == PublishState.FAILED }
            if (state != session.publishState || view.publishedSets != session.publishedSets || note != session.publishNote) {
                repository.recordPublish(session.sessionId, state, view.publishedSets, note)
                CloudLog.i("publish_state", "session" to session.sessionId, "state" to state.name, "sets" to view.publishedSets)
                changed++
            }
        }
        changed
    }

    private fun stateOf(view: CloudSessionView): PublishState = when (view.publishStatus) {
        null -> PublishState.UNSUPPORTED
        "done" -> PublishState.DONE
        "failed" -> PublishState.FAILED
        else -> PublishState.PENDING
    }

    private fun intervalFor(session: CloudSessionEntity, now: Long): Long {
        val syncedFor = now - (session.syncedAt ?: now)
        return if (session.publishState == PublishState.FAILED || syncedFor > FAST_WINDOW_MS) SLOW_INTERVAL_MS else FAST_INTERVAL_MS
    }

    companion object {
        const val FAST_INTERVAL_MS = 15_000L
        const val FAST_WINDOW_MS = 10 * 60_000L
        const val SLOW_INTERVAL_MS = 5 * 60_000L

        /**
         * Whether the backend's answer for [session] can still change. Only synced recordings are asked about (before
         * that there is nothing to publish). A recording published with zero sets stays open while its pipeline export
         * is still expected: the backend publishes a raw-only recording first and again once the export arrives.
         */
        fun isAwaiting(session: CloudSessionEntity): Boolean = when {
            session.syncedAt == null -> false
            session.publishState == PublishState.UNSUPPORTED -> false
            session.publishState == PublishState.DONE -> session.publishedSets == 0 && session.exportState == ExportState.DONE
            else -> true
        }
    }
}
