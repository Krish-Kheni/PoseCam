package com.posecam.core.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per immutable session file. The unique (sessionId, relativePath) index is what makes
 * every enqueue/recovery call idempotent: the same file can never be queued twice.
 *
 * Recording state ("complete", "interrupted", ...) deliberately does not appear here; cloud
 * state is independent of it.
 */
@Entity(
    tableName = "uploads",
    indices = [
        Index(value = ["sessionId", "relativePath"], unique = true),
        Index(value = ["state"]),
        Index(value = ["sessionId"]),
    ],
)
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    /** Path relative to the session directory, `/`-separated. Sent to the backend. */
    val relativePath: String,
    val localPath: String,
    /** Reserved. The backend alone constructs S3 keys and does not disclose them to the app. */
    val s3Key: String? = null,
    val fileType: UploadFileType,
    /** [UploadSourceKind.FRAME_CHUNK] rows have no file until the worker builds the zip at [localPath]. */
    val kind: UploadSourceKind = UploadSourceKind.PLAIN,
    /** JPEGs a frame chunk holds. Retention uses it to prove every local frame is inside a verified chunk. */
    val itemCount: Int = 0,
    /** False for derived files (pipeline exports) that must not hold up SYNCED. */
    val required: Boolean = true,
    val sizeBytes: Long,
    /** SHA-256 hex. Null until known (computed by the worker on a background thread). */
    val sha256: String? = null,
    val state: UploadState = UploadState.PENDING,
    val multipartUploadId: String? = null,
    /** JSON-encoded [MultipartState]: the parts already uploaded, so a resume skips them. */
    val multipartState: String? = null,
    /** Bytes confirmed transferred so far; drives the progress display. */
    val uploadedBytes: Long = 0,
    /** Temporary failures seen so far. Informational only: it never caps retrying. */
    val retryCount: Int = 0,
    /** Times the backend found the S3 copy wrong; a small cap here turns corruption into FAILED. */
    val verifyFailures: Int = 0,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val uploadedAt: Long? = null,
    val verifiedAt: Long? = null,
)

/** Cloud-side bookkeeping for one recording session, kept apart from its recording status. */
@Entity(tableName = "cloud_sessions")
data class CloudSessionEntity(
    @PrimaryKey val sessionId: String,
    val directoryPath: String,
    /** Final manifest status once the recording is finalized/recovered; null while recording. */
    val recordingStatus: String? = null,
    /** True once finalization finished and every file that exists was queued. */
    val recordingFinal: Boolean = false,
    /** True once `POST /v1/sessions` succeeded (or the session already existed). */
    val cloudCreated: Boolean = false,
    /** The backend refused the session permanently; its files will not be uploaded. */
    val permanentFailure: Boolean = false,
    /** Set when the backend confirmed the session SYNCED. Null again if a file is re-queued. */
    val syncedAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * [Pipe.wire] the collector chose for this recording. Null until they choose: such a session is never created in
     * the cloud and none of its files are uploaded, because the pipe decides the cloud folder they go to.
     */
    val pipe: String? = null,
    /** Pipeline export progress (see [ExportState]). Added in database version 2; existing rows start at PENDING. */
    @ColumnInfo(defaultValue = "PENDING")
    val exportState: ExportState = ExportState.PENDING,
    /** Why the export is missing, in the collector's words: the exporter's message. Null unless [exportState] says so. */
    val exportNote: String? = null,
)

/**
 * Per-session roll-up over the pipeline-export files (the ones that are not required for SYNCED). Kept apart from
 * [SessionUploadAggregate] so an export that fails to upload can be shown without ever un-syncing the session.
 */
data class SessionExportAggregate(
    val sessionId: String,
    val files: Int,
    val verifiedFiles: Int,
    val failedFiles: Int,
)

/** Per-session roll-up over the *required* files, used for status and progress. */
data class SessionUploadAggregate(
    val sessionId: String,
    val totalFiles: Int,
    val verifiedFiles: Int,
    val failedFiles: Int,
    val activeFiles: Int,
    val awaitingVerification: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
    val lastError: String?,
)
