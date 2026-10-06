package com.posecam.core.sync

/** Something worth telling the user about (as a snackbar when the app is open, a notification when not). */
sealed interface CloudSyncEvent {
    /** The whole backlog finished: nothing is pending, waiting or failed any more. */
    data class AllSynced(val sessions: Int) : CloudSyncEvent

    /** Files or sessions failed permanently and need the user (retry). */
    data class Failed(val sessions: Int) : CloudSyncEvent

    /** Uploads have been failing temporarily for hours; not an error yet, but the user should know. */
    data object Stalled : CloudSyncEvent
}

/** Durable memory so announcements are made once, not once per worker run or per app launch. */
interface AnnouncementStore {
    /** Sessions that became synced since the last "all synced" announcement. */
    var unannouncedSynced: Int

    /** The failed-session count the user was last told about. */
    var notifiedFailed: Int

    /** When uploads first started failing temporarily in the current streak; null when healthy. */
    var stallSinceMs: Long?

    var stalledNotified: Boolean
}

/**
 * Decides WHICH events to raise from the queue's state. Pure logic (no Android): the rules are
 *  * "all synced" is announced once per backlog, only when nothing is left to do;
 *  * a failure is announced when the failed count rises, not on every run;
 *  * temporary failures never alert -- only a streak longer than [STALL_AFTER_MS], once.
 */
class SyncAnnouncer(
    private val store: AnnouncementStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun evaluate(overview: CloudOverview, newlySyncedSessions: Int, result: QueueRunResult): List<CloudSyncEvent> {
        val events = mutableListOf<CloudSyncEvent>()
        store.unannouncedSynced += newlySyncedSessions

        if (overview.kind == CloudOverview.Kind.NONE && store.unannouncedSynced > 0) {
            events += CloudSyncEvent.AllSynced(store.unannouncedSynced)
            store.unannouncedSynced = 0
        }

        if (overview.failed > store.notifiedFailed) events += CloudSyncEvent.Failed(overview.failed)
        // Also lowers the mark when failures were retried or resolved, so a later failure is announced again.
        store.notifiedFailed = overview.failed

        when (result) {
            QueueRunResult.DONE -> {
                store.stallSinceMs = null
                store.stalledNotified = false
            }
            QueueRunResult.RETRY -> {
                val now = clock()
                val since = store.stallSinceMs ?: now.also { store.stallSinceMs = it }
                if (!store.stalledNotified && now - since >= STALL_AFTER_MS) {
                    store.stalledNotified = true
                    events += CloudSyncEvent.Stalled
                }
            }
        }
        return events
    }

    companion object {
        const val STALL_AFTER_MS = 3L * 60 * 60 * 1000
    }
}

/** Where an event goes: never both, and never a notification for something the user is looking at. */
sealed interface EventRoute {
    data class Snackbar(val message: String, val action: SnackbarAction?) : EventRoute
    data class Notification(val id: Int, val title: String, val text: String) : EventRoute
}

enum class SnackbarAction(val label: String) { RETRY("Retry"), SYNC_NOW("Sync now") }

object CloudEventRouter {
    const val NOTIFICATION_ALL_SYNCED = 3002
    const val NOTIFICATION_FAILED = 3003
    const val NOTIFICATION_STALLED = 3004

    fun route(event: CloudSyncEvent, appInForeground: Boolean): EventRoute {
        val (title, text, action, id) = describe(event)
        return if (appInForeground) EventRoute.Snackbar(text, action) else EventRoute.Notification(id, title, text)
    }

    private data class Description(val title: String, val text: String, val action: SnackbarAction?, val id: Int)

    private fun describe(event: CloudSyncEvent): Description = when (event) {
        is CloudSyncEvent.AllSynced -> Description(
            "Recordings synced",
            "All recordings synced (${sessions(event.sessions)})",
            null,
            NOTIFICATION_ALL_SYNCED,
        )
        is CloudSyncEvent.Failed -> Description(
            "Sync failed",
            "Sync failed for ${sessions(event.sessions)}. Tap to retry.",
            SnackbarAction.RETRY,
            NOTIFICATION_FAILED,
        )
        CloudSyncEvent.Stalled -> Description(
            "Sync paused",
            "Sync is paused: can't reach the server",
            SnackbarAction.SYNC_NOW,
            NOTIFICATION_STALLED,
        )
    }

    private fun sessions(count: Int) = if (count == 1) "1 session" else "$count sessions"
}
