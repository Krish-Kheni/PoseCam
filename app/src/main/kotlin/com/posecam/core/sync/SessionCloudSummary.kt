package com.posecam.core.sync

/**
 * What the UI knows about one session's cloud state. It is independent of the recording
 * status: a session can be "complete" and "uploading", or "recovered-interrupted" and "synced".
 */
data class SessionCloudSummary(
    val sessionId: String,
    /** Cloud status ignoring connectivity; use [displayStatus] for what to show. */
    val status: SessionCloudStatus,
    val totalFiles: Int,
    val verifiedFiles: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
    val lastError: String?,
    /** [Pipe.wire] chosen for this recording, or null while it still has to be chosen. */
    val pipe: String? = null,
) {
    /** PENDING work that cannot start because the network policy is not satisfied reads "Waiting for Wi-Fi". */
    fun displayStatus(waitingForNetwork: Boolean): SessionCloudStatus =
        if (status == SessionCloudStatus.PENDING && waitingForNetwork) SessionCloudStatus.WAITING_FOR_WIFI else status

    val isSynced: Boolean get() = status == SessionCloudStatus.SYNCED

    companion object {
        fun from(aggregate: SessionUploadAggregate?, session: CloudSessionEntity?): SessionCloudSummary {
            val id = aggregate?.sessionId ?: requireNotNull(session).sessionId
            val total = aggregate?.totalFiles ?: 0
            val verified = aggregate?.verifiedFiles ?: 0
            val allVerified = total > 0 && verified == total
            val status = when {
                session?.permanentFailure == true || (aggregate?.failedFiles ?: 0) > 0 -> SessionCloudStatus.FAILED
                // Confirmed by the backend, and nothing has been re-queued since.
                session?.syncedAt != null && (total == 0 || allVerified) -> SessionCloudStatus.SYNCED
                // Not chosen yet, and not in the cloud: the collector has to say where it goes before anything is sent.
                session != null && session.pipe == null && !session.cloudCreated && session.syncedAt == null ->
                    SessionCloudStatus.AWAITING_PIPE
                // Everything is in S3; only the session-level confirmation is outstanding.
                allVerified && session?.recordingFinal == true -> SessionCloudStatus.VERIFYING
                aggregate != null && aggregate.activeFiles > 0 ->
                    if (aggregate.awaitingVerification == aggregate.activeFiles) SessionCloudStatus.VERIFYING else SessionCloudStatus.UPLOADING
                else -> SessionCloudStatus.PENDING
            }
            return SessionCloudSummary(
                sessionId = id,
                status = status,
                totalFiles = total,
                verifiedFiles = verified,
                totalBytes = aggregate?.totalBytes ?: 0,
                transferredBytes = aggregate?.transferredBytes ?: 0,
                lastError = aggregate?.lastError ?: session?.lastError,
                pipe = session?.pipe,
            )
        }
    }
}
