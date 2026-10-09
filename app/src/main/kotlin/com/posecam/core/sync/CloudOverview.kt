package com.posecam.core.sync

/** App-wide roll-up of every session's cloud state, for the "Syncing 3 sessions" banner. */
data class CloudOverview(
    val syncing: Int,
    val waiting: Int,
    val failed: Int,
    val synced: Int,
    val transferredBytes: Long,
    val totalBytes: Long,
    /** Files verified / total across the sessions the progress covers (drives the notification). */
    val verifiedFiles: Int = 0,
    val totalFiles: Int = 0,
    val policy: SyncPolicy,
    val waitingForNetwork: Boolean,
) {
    enum class Kind { NONE, FAILED, SYNCING, WAITING }

    /** What the banner should say, most important first: failures, then active transfer, then queued work. */
    val kind: Kind
        get() = when {
            failed > 0 -> Kind.FAILED
            syncing > 0 -> Kind.SYNCING
            waiting > 0 -> Kind.WAITING
            else -> Kind.NONE
        }

    val message: String
        get() = when (kind) {
            Kind.FAILED -> if (failed == 1) "1 upload failed" else "$failed uploads failed"
            Kind.SYNCING -> "Uploading ${recordings(syncing)}"
            Kind.WAITING -> when {
                policy == SyncPolicy.MANUAL_ONLY -> "${recordings(waiting).replaceFirstChar { it.uppercase() }} waiting · manual"
                waitingForNetwork -> "Waiting for Wi-Fi · ${recordings(waiting)}"
                else -> "${recordings(waiting).replaceFirstChar { it.uppercase() }} queued"
            }
            Kind.NONE -> if (synced > 0) "All recordings synced" else ""
        }

    /** Whether any upload work is outstanding or has failed; false means "nothing left to say". */
    val isBusy: Boolean get() = kind != Kind.NONE

    /** "120 MB of 560 MB": how much of what is being sent has gone; the bar and percentage say the rest. */
    val progressText: String
        get() = "${formatBytes(transferredBytes)} of ${formatBytes(totalBytes)}"

    /** 0..100, or -1 when the total is unknown (indeterminate). */
    val progressPercent: Int
        get() = if (totalBytes > 0) ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else -1

    private fun recordings(count: Int) = if (count == 1) "1 recording" else "$count recordings"

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 MB"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb < 1024) "%.0f MB".format(mb) else "%.1f GB".format(mb / 1024.0)
        }

        /**
         * The progress numbers cover the same sessions the headline names: the ones transferring right now, or
         * -- when none are -- the ones waiting. Queued sessions are not added to a transfer that is not theirs.
         */
        fun from(summaries: Collection<SessionCloudSummary>, policy: SyncPolicy, waitingForNetwork: Boolean): CloudOverview {
            var failed = 0
            var synced = 0
            val syncing = mutableListOf<SessionCloudSummary>()
            val waiting = mutableListOf<SessionCloudSummary>()
            for (summary in summaries) {
                when (summary.status) {
                    SessionCloudStatus.FAILED -> failed++
                    SessionCloudStatus.UPLOADING, SessionCloudStatus.VERIFYING -> syncing += summary
                    SessionCloudStatus.PENDING, SessionCloudStatus.WAITING_FOR_WIFI -> waiting += summary
                    SessionCloudStatus.SYNCED -> synced++
                    // Not "waiting": nothing is queued to be sent until the collector chooses a pipe.
                    SessionCloudStatus.LOCAL_ONLY, SessionCloudStatus.AWAITING_PIPE -> Unit
                }
            }
            val shown = syncing.ifEmpty { waiting }
            return CloudOverview(
                syncing = syncing.size,
                waiting = waiting.size,
                failed = failed,
                synced = synced,
                transferredBytes = shown.sumOf { it.transferredBytes },
                totalBytes = shown.sumOf { it.totalBytes },
                verifiedFiles = shown.sumOf { it.verifiedFiles },
                totalFiles = shown.sumOf { it.totalFiles },
                policy = policy,
                waitingForNetwork = waitingForNetwork,
            )
        }
    }
}
