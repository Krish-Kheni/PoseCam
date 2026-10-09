package com.posecam.core.sync

/** What tapping the cloud badge on a session card does. */
enum class CloudCardAction {
    /** Not uploaded yet: start uploading this session now. */
    START,

    /** Some files failed: retry them now. */
    RETRY,

    /** The recording has no pipe yet: offer "White pipe" / "Black pipe". */
    CHOOSE_PIPE,

    /** Nothing to start: just tell the user the state. */
    INFO,
    ;

    companion object {
        fun forStatus(status: SessionCloudStatus, recordingInProgress: Boolean): CloudCardAction = when {
            recordingInProgress -> INFO
            status == SessionCloudStatus.AWAITING_PIPE -> CHOOSE_PIPE
            status == SessionCloudStatus.FAILED -> RETRY
            status == SessionCloudStatus.LOCAL_ONLY || status == SessionCloudStatus.PENDING ||
                status == SessionCloudStatus.WAITING_FOR_WIFI -> START
            else -> INFO // uploading, verifying, synced
        }

        /** The message for an [INFO] tap. */
        fun infoMessage(status: SessionCloudStatus, summary: SessionCloudSummary?, recordingInProgress: Boolean): String = when {
            recordingInProgress -> "Uploads start after this recording finishes"
            status == SessionCloudStatus.UPLOADING -> {
                val percent = if (summary != null && summary.totalBytes > 0) {
                    " ${(summary.transferredBytes * 100 / summary.totalBytes).toInt().coerceIn(0, 100)}%"
                } else {
                    ""
                }
                // One number for the whole recording: how many files that is made of is not the collector's concern.
                "Uploading$percent"
            }
            status == SessionCloudStatus.VERIFYING -> "Verifying the upload…"
            status == SessionCloudStatus.SYNCED -> "Synced to the cloud"
            else -> ""
        }
    }
}

/** When a manual upload must ask first: it would use mobile data, the user has not opted out of the question. */
object MobileDataGuard {
    fun needsConfirmation(policy: SyncPolicy, onMeteredNetwork: Boolean, confirmEnabled: Boolean): Boolean =
        confirmEnabled && onMeteredNetwork && policy != SyncPolicy.ANY_NETWORK
}
