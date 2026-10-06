package com.posecam.core.sync

/** Lifecycle of one immutable session file in the upload queue. */
enum class UploadState {
    /** Queued; nothing has been sent yet (or a retry reset it). */
    PENDING,

    /** A worker has picked it up: hashing and asking the backend how to upload. */
    PREPARING,

    /** Bytes are being transferred to S3 (single PUT, or a multipart upload in progress). */
    UPLOADING,

    /** All bytes reached S3 but the backend has not yet confirmed size/checksum. */
    UPLOADED,

    /** The backend inspected the S3 object and confirmed it. Terminal for a healthy file. */
    VERIFIED,

    /** A permanent error (see `lastError`); only a user retry or a changed file re-queues it. */
    FAILED,
    ;

    /** True for states a worker should still make progress on. */
    val isRunnable: Boolean get() = this == PENDING || this == PREPARING || this == UPLOADING || this == UPLOADED

    fun canTransitionTo(next: UploadState): Boolean = this == next || next in ALLOWED.getValue(this)

    private companion object {
        val ALLOWED: Map<UploadState, Set<UploadState>> = mapOf(
            PENDING to setOf(PREPARING, FAILED),
            // VERIFIED directly: the backend already holds an identical verified object.
            PREPARING to setOf(PENDING, UPLOADING, UPLOADED, VERIFIED, FAILED),
            UPLOADING to setOf(PENDING, UPLOADED, FAILED),
            // PENDING again when verification finds the cloud copy wrong and it must be re-sent.
            UPLOADED to setOf(PENDING, VERIFIED, FAILED),
            // PENDING again only when the local file changed after it was verified.
            VERIFIED to setOf(PENDING),
            FAILED to setOf(PENDING),
        )
    }
}

/** What the backend/UI cares about per kind of file. Mirrors the backend PoseCam allow-list. */
enum class UploadFileType { METADATA, TABLE, IMU, FRAME_CHUNK, EXPORT }

/** Where a queued file's bytes come from. */
enum class UploadSourceKind {
    /** A file that already exists in the session folder. */
    PLAIN,

    /** A zip of up to [FrameChunks.FRAMES_PER_CHUNK] JPEGs, built on demand and staged outside the session. */
    FRAME_CHUNK,
}

/** What the Sessions UI shows; derived from queue state, never stored. */
enum class SessionCloudStatus {
    /** Cloud sync is disabled/unconfigured, or the session has not been queued. */
    LOCAL_ONLY,

    /** Finished recording whose pipe (cloud folder) the collector has not chosen yet: nothing is uploaded until they do. */
    AWAITING_PIPE,
    WAITING_FOR_WIFI,
    PENDING,
    UPLOADING,

    /** Every byte is in S3 and verification / session completion is pending. */
    VERIFYING,
    SYNCED,
    FAILED,
}
