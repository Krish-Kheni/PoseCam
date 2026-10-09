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

    /**
     * A file of the pipeline export (`export/<stem>/...`). It was written once, into [UploadStaging], by
     * [PipelineExportStage]; the session folder holds no copy of it, so it is deleted from staging once verified.
     */
    EXPORT,
}

/**
 * Where a recording stands on its pipeline export (`RGB_<stem>.mp4` + `AR_Pose_<stem>.txt`, the files LabelNow turns into
 * a labelable video). The export is derived from the raw recording and never holds up SYNCED, but it is the part of the
 * upload that LabelNow actually uses, so every outcome other than [DONE] is shown to the collector.
 */
enum class ExportState {
    /** Not exported yet (or interrupted by a take, or waiting for the rotation setting). Retried on every run. */
    PENDING,

    /** Exported and its files queued: from here on they are ordinary queue rows. */
    DONE,

    /** The recording is off protocol (wrong size, fps, focus): it can never appear in LabelNow. Not retried. */
    OFF_PROTOCOL,

    /** The recording cannot be exported for another lasting reason (killed take, no clean stretch, frames missing). Not retried. */
    NOT_EXPORTABLE,

    /** The export crashed for a reason that may pass (storage, encoder). Only a user retry runs it again. */
    FAILED,
    ;

    /** True when this recording will not (or not yet) have an export in LabelNow. */
    val isMissing: Boolean get() = this != DONE
}

/**
 * Where a synced recording stands on its way to the website, as last reported by the backend. SYNCED only means the
 * files arrived; the backend then turns them into gallery cards, which takes a while and can fail.
 */
enum class PublishState {
    /** Not on the website yet (or not asked yet). Checked again until the backend answers. */
    PENDING,

    /** The backend published it. With zero sets it was published without a video (nothing to label). */
    DONE,

    /** The backend gave up publishing it; [CloudSessionEntity.publishNote] says why. */
    FAILED,

    /** The backend does not report publishing (an older one): behave as before and never wait for it. */
    UNSUPPORTED,
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
