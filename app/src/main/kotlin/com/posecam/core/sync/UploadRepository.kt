package com.posecam.core.sync

import androidx.room.withTransaction
import com.posecam.core.cloud.CloudLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

/** Runs a block atomically. Production uses Room's `withTransaction`; unit tests just run it. */
interface Transactor {
    suspend fun <T> run(block: suspend () -> T): T

    companion object {
        fun forDatabase(database: UploadDatabase): Transactor = object : Transactor {
            override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
        }

        val None: Transactor = object : Transactor {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
    }
}

enum class EnqueueResult { INSERTED, ALREADY_QUEUED, REQUEUED_CHANGED_FILE }

/** The single owner of upload-queue state transitions. Every operation is idempotent. */
class UploadRepository(
    private val dao: UploadDao,
    private val transactor: Transactor,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called after a session's rows were dropped, so its staged frame chunks can be deleted too. */
    private val onSessionForgotten: (String) -> Unit = {},
) {
    // ---- enqueue ---------------------------------------------------------------------------

    suspend fun registerSession(sessionId: String, directory: File) {
        dao.insertSessionIgnore(newSession(sessionId, directory))
    }

    /** Queues one closed file of [sessionId]. Calling it again for the same file is a no-op. */
    suspend fun enqueue(
        sessionId: String,
        directory: File,
        file: QueuedFile,
        /** Queue position (rows run oldest first); null means now, i.e. behind everything queued already. */
        createdAt: Long? = null,
    ): EnqueueResult = transactor.run {
        // A file the backend would reject permanently must never enter the queue.
        require(CloudFileRules.isUploadable(file.relativePath)) { "Not an uploadable session file: ${file.relativePath}" }
        val now = clock()
        dao.insertSessionIgnore(newSession(sessionId, directory))
        val row = UploadEntity(
            sessionId = sessionId,
            relativePath = file.relativePath,
            localPath = file.localPath,
            fileType = requireNotNull(CloudFileRules.classify(file.relativePath)),
            kind = file.kind,
            itemCount = file.itemCount,
            required = CloudFileRules.isRequired(file.relativePath),
            sizeBytes = file.sizeBytes,
            sha256 = file.sha256,
            createdAt = createdAt ?: now,
            updatedAt = now,
        )
        if (dao.insertUploadIgnore(row) != NOT_INSERTED) {
            CloudLog.i("file_queued", "session" to sessionId, "path" to file.relativePath, "bytes" to file.sizeBytes)
            return@run EnqueueResult.INSERTED
        }
        val existing = requireNotNull(dao.findUpload(sessionId, file.relativePath))
        // A chunk's queued size is an estimate that becomes the zip's real size once it is built, so
        // sizes can never be compared for it; the number of JPEGs it holds says whether its content changed.
        val unchanged = if (existing.kind == UploadSourceKind.FRAME_CHUNK) {
            existing.itemCount == file.itemCount
        } else {
            existing.sizeBytes == file.sizeBytes
        }
        if (unchanged) return@run EnqueueResult.ALREADY_QUEUED
        // The same file now has different content: the copy we queued (or even verified) is stale.
        // Start it over rather than mixing two versions of its bytes.
        rebaseline(existing, file.sizeBytes, itemCount = file.itemCount, newSha256 = file.sha256)
        EnqueueResult.REQUEUED_CHANGED_FILE
    }

    /**
     * The recording is finalized (or was recovered): queue every file that exists and mark the
     * session final in one transaction, so "recordingFinal" can never be observed before the
     * complete file set is in the queue.
     */
    suspend fun finalizeSession(
        sessionId: String,
        directory: File,
        recordingStatus: String,
        files: List<QueuedFile>,
    ) {
        transactor.run {
            dao.insertSessionIgnore(newSession(sessionId, directory))
            files.forEach { enqueue(sessionId, directory, it) }
            val session = requireNotNull(dao.getSession(sessionId))
            if (!session.recordingFinal || session.recordingStatus != recordingStatus) {
                dao.updateSession(session.copy(recordingFinal = true, recordingStatus = recordingStatus, updatedAt = clock()))
            }
        }
    }

    /**
     * The user asked for this session to upload now: move its pending files to the front of the queue. The
     * file already in flight finishes first; the next one picked is this session's.
     */
    suspend fun prioritizeSession(sessionId: String) {
        transactor.run {
            val front = (dao.minRunnableCreatedAt() ?: clock()) - 1
            dao.setRunnableCreatedAt(sessionId, front)
        }
    }

    /**
     * Files [sessionId] under [pipe]. Only possible until the session exists in the cloud: after that its files already
     * live in a folder, and moving them would mean different copies in two pipes. Returns whether the choice was recorded.
     */
    suspend fun setPipe(sessionId: String, pipe: Pipe): Boolean = transactor.run {
        val session = dao.getSession(sessionId) ?: return@run false
        if (session.cloudCreated) return@run session.pipe == pipe.wire
        dao.updateSession(session.copy(pipe = pipe.wire, updatedAt = clock()))
        CloudLog.i("pipe_chosen", "session" to sessionId, "pipe" to pipe.wire)
        true
    }

    // ---- pipeline export -------------------------------------------------------------------

    suspend fun sessionsNeedingExport(): List<CloudSessionEntity> = dao.sessionsNeedingExport()

    /**
     * The export of [sessionId] was written to staging: queue its files and remember it is done, in one transaction, so a
     * crash can never leave "done" without rows (which would lose the export) or rows without "done" (which would redo it).
     * The files go to the FRONT of the queue: they are about 50 MB against gigabytes of raw data, and they are what
     * LabelNow uses, so a labeler's recording becomes labelable long before the raw upload ends.
     */
    suspend fun completeExport(sessionId: String, files: List<QueuedFile>) {
        transactor.run {
            val session = dao.getSession(sessionId) ?: return@run
            val front = (dao.minRunnableCreatedAt() ?: clock()) - files.size - 1
            files.forEachIndexed { index, file ->
                enqueue(sessionId, File(session.directoryPath), file, createdAt = front + index)
            }
            dao.updateSession(session.copy(exportState = ExportState.DONE, exportNote = null, updatedAt = clock()))
            CloudLog.i("export_queued", "session" to sessionId, "files" to files.size)
        }
    }

    /** The export is not coming ([state] is one of the lasting or failed outcomes); [note] says why, in plain words. */
    suspend fun markExportMissing(sessionId: String, state: ExportState, note: String) {
        require(state != ExportState.PENDING && state != ExportState.DONE)
        mutateSession(sessionId) { it.copy(exportState = state, exportNote = note.take(MAX_ERROR_CHARS)) }
        CloudLog.w("export_missing", "session" to sessionId, "state" to state.name, "note" to note)
    }

    /**
     * Makes the export again: a crashed one (user retry), or one whose staged files vanished before they were uploaded.
     * Rows of an earlier export are dropped; files already verified in the cloud are simply sent again if the new bytes differ.
     */
    suspend fun resetExport(sessionId: String) {
        transactor.run {
            val exportRows = dao.uploadsForSession(sessionId).filter { it.kind == UploadSourceKind.EXPORT }
            exportRows.forEach { dao.deleteUpload(it.id) }
            val session = dao.getSession(sessionId) ?: return@run
            dao.updateSession(session.copy(exportState = ExportState.PENDING, exportNote = null, updatedAt = clock()))
        }
    }

    /** Startup: a worker killed mid-prepare left PREPARING rows; they are plainly runnable again. */
    suspend fun normalizeInterrupted(): Int = dao.resetPreparing(clock())

    /** Drops the queue and session rows of a session whose directory is gone. */
    suspend fun forgetSession(sessionId: String) {
        transactor.run {
            dao.deleteUploadsForSession(sessionId)
            dao.deleteSession(sessionId)
        }
        runCatching { onSessionForgotten(sessionId) }
    }

    // ---- queue access ----------------------------------------------------------------------

    suspend fun runnable(): List<UploadEntity> = dao.runnableUploads()

    suspend fun get(id: Long): UploadEntity? = dao.getUpload(id)

    suspend fun uploadsForSession(sessionId: String): List<UploadEntity> = dao.uploadsForSession(sessionId)

    suspend fun session(sessionId: String): CloudSessionEntity? = dao.getSession(sessionId)

    suspend fun allSessions(): List<CloudSessionEntity> = dao.allSessions()

    // ---- state transitions -----------------------------------------------------------------

    suspend fun markPreparing(id: Long) = transition(id, UploadState.PREPARING)

    suspend fun saveSha256(id: Long, sha256: String) = mutate(id) { it.copy(sha256 = sha256) }

    suspend fun markUploading(id: Long) = transition(id, UploadState.UPLOADING)

    /** Persists multipart progress; called per part, so a resume skips the finished parts. */
    suspend fun saveMultipart(id: Long, state: MultipartState) = mutate(id) {
        it.copy(multipartUploadId = state.uploadId, multipartState = state.encode(), uploadedBytes = state.uploadedBytes)
    }

    /** In-flight bytes of a single (non-multipart) PUT, so progress moves during a large upload. */
    suspend fun saveProgress(id: Long, uploadedBytes: Long) = mutate(id) {
        // Never regress, and never claim more than the file: this is display progress only.
        it.copy(uploadedBytes = maxOf(it.uploadedBytes, uploadedBytes.coerceAtMost(it.sizeBytes)))
    }

    suspend fun markUploaded(id: Long) = transition(id, UploadState.UPLOADED) {
        it.copy(uploadedBytes = it.sizeBytes, uploadedAt = clock(), lastError = null)
    }

    suspend fun markVerified(id: Long) = transition(id, UploadState.VERIFIED) {
        it.copy(
            uploadedBytes = it.sizeBytes,
            verifiedAt = clock(),
            uploadedAt = it.uploadedAt ?: clock(),
            lastError = null,
            multipartState = null,
        )
    }

    /** A temporary failure: remember why, keep state (and multipart progress), stay retryable. */
    suspend fun recordRetry(id: Long, error: String) = mutate(id) {
        it.copy(retryCount = it.retryCount + 1, lastError = error.take(MAX_ERROR_CHARS))
    }

    suspend fun markFailed(id: Long, error: String) = transition(id, UploadState.FAILED) {
        it.copy(lastError = error.take(MAX_ERROR_CHARS))
    }

    /** Throws away multipart progress and sends the file again from byte zero. */
    suspend fun resetToPending(id: Long, reason: String, countVerifyFailure: Boolean = false) =
        transition(id, UploadState.PENDING) {
            it.copy(
                multipartUploadId = null,
                multipartState = null,
                uploadedBytes = 0,
                lastError = reason.take(MAX_ERROR_CHARS),
                verifyFailures = it.verifyFailures + if (countVerifyFailure) 1 else 0,
            )
        }

    /**
     * A frame chunk was just built (or found staged): record its real size. Unlike [rebaseline] this does NOT
     * reset the row's state, its hash, or the session's synced mark: the content did not change, only our
     * estimate of its size became exact.
     */
    suspend fun saveMaterialized(id: Long, sizeBytes: Long) = mutate(id) { it.copy(sizeBytes = sizeBytes) }

    /** The local file changed after it was queued; adopt its real size and begin again. */
    suspend fun rebaseline(id: Long, newSizeBytes: Long) {
        val row = dao.getUpload(id) ?: return
        rebaseline(row, newSizeBytes)
    }

    private suspend fun rebaseline(row: UploadEntity, newSizeBytes: Long, itemCount: Int = row.itemCount, newSha256: String? = null) {
        CloudLog.w(
            "file_changed",
            "session" to row.sessionId, "path" to row.relativePath, "was" to row.sizeBytes, "now" to newSizeBytes,
        )
        // A staged chunk is stale too: drop it so it is rebuilt from the JPEGs that exist now.
        if (row.kind == UploadSourceKind.FRAME_CHUNK) runCatching { File(row.localPath).delete() }
        transactor.run {
            dao.updateUpload(
                row.copy(
                    sizeBytes = newSizeBytes,
                    itemCount = itemCount,
                    sha256 = newSha256,
                    state = UploadState.PENDING,
                    multipartUploadId = null,
                    multipartState = null,
                    uploadedBytes = 0,
                    verifiedAt = null,
                    uploadedAt = null,
                    lastError = null,
                    updatedAt = clock(),
                ),
            )
            // New content means an earlier "synced" verdict no longer holds.
            dao.getSession(row.sessionId)?.takeIf { it.syncedAt != null }
                ?.let { dao.updateSession(it.copy(syncedAt = null, updatedAt = clock())) }
        }
    }

    /** User action: put permanently failed files (of one session, or all) back in the queue. */
    suspend fun retryFailed(sessionId: String? = null): Int = transactor.run {
        val failed = dao.failedUploads(sessionId)
        failed.forEach { resetToPending(it.id, "Retry requested") }
        // A session the backend refused outright gets another chance as well.
        dao.allSessions()
            .filter { it.permanentFailure && (sessionId == null || it.sessionId == sessionId) }
            .forEach { dao.updateSession(it.copy(permanentFailure = false, lastError = null, updatedAt = clock())) }
        // So does an export that crashed (storage, encoder): only an explicit retry runs it again.
        dao.allSessions()
            .filter { it.exportState == ExportState.FAILED && (sessionId == null || it.sessionId == sessionId) }
            .forEach { dao.updateSession(it.copy(exportState = ExportState.PENDING, exportNote = null, updatedAt = clock())) }
        failed.size
    }

    // ---- sessions --------------------------------------------------------------------------

    suspend fun sessionsNeedingCreation(): List<CloudSessionEntity> = dao.sessionsNeedingCreation()

    suspend fun markSessionCreated(sessionId: String) = mutateSession(sessionId) {
        it.copy(cloudCreated = true, lastError = null)
    }

    /** The backend lost track of the session (404): create it again before more uploads. */
    suspend fun markSessionNotCreated(sessionId: String) = mutateSession(sessionId) { it.copy(cloudCreated = false) }

    suspend fun markSessionFailed(sessionId: String, error: String) = mutateSession(sessionId) {
        it.copy(permanentFailure = true, lastError = error.take(MAX_ERROR_CHARS))
    }

    suspend fun noteSessionError(sessionId: String, error: String) = mutateSession(sessionId) {
        it.copy(lastError = error.take(MAX_ERROR_CHARS))
    }

    suspend fun sessionsReadyToComplete(): List<CloudSessionEntity> = dao.sessionsReadyToComplete()

    suspend fun markSessionSynced(sessionId: String) = mutateSession(sessionId) {
        // Synced again means the backend may publish again, so an earlier website verdict no longer holds.
        it.copy(syncedAt = clock(), lastError = null, publishState = PublishState.PENDING, publishedSets = 0, publishNote = null)
    }

    /** What the backend last said about this recording reaching the website (see [PublishTracker]). */
    suspend fun recordPublish(sessionId: String, state: PublishState, sets: Int, note: String?) = mutateSession(sessionId) {
        it.copy(publishState = state, publishedSets = sets, publishNote = note?.take(MAX_ERROR_CHARS))
    }

    // ---- observation -----------------------------------------------------------------------

    /** Live per-session cloud progress for the UI. */
    fun observeSummaries(): Flow<Map<String, SessionCloudSummary>> =
        combine(dao.observeAggregates(), dao.observeSessions(), dao.observeExportAggregates()) { aggregates, sessions, exports ->
            val bySession = sessions.associateBy { it.sessionId }
            val byAggregate = aggregates.associateBy { it.sessionId }
            val byExport = exports.associateBy { it.sessionId }
            (bySession.keys + byAggregate.keys).associateWith { id ->
                SessionCloudSummary.from(byAggregate[id], bySession[id], byExport[id])
            }
        }.distinctUntilChanged()

    // ---- internals -------------------------------------------------------------------------

    private suspend fun transition(id: Long, next: UploadState, edit: (UploadEntity) -> UploadEntity = { it }) {
        transactor.run {
            val row = dao.getUpload(id) ?: return@run
            check(row.state.canTransitionTo(next)) {
                "Illegal upload transition ${row.state} -> $next for ${row.relativePath}"
            }
            dao.updateUpload(edit(row).copy(state = next, updatedAt = clock()))
        }
    }

    private suspend fun mutate(id: Long, edit: (UploadEntity) -> UploadEntity) {
        transactor.run {
            val row = dao.getUpload(id) ?: return@run
            dao.updateUpload(edit(row).copy(updatedAt = clock()))
        }
    }

    private suspend fun mutateSession(sessionId: String, edit: (CloudSessionEntity) -> CloudSessionEntity) {
        transactor.run {
            val row = dao.getSession(sessionId) ?: return@run
            dao.updateSession(edit(row).copy(updatedAt = clock()))
        }
    }

    private fun newSession(sessionId: String, directory: File): CloudSessionEntity {
        val now = clock()
        return CloudSessionEntity(sessionId = sessionId, directoryPath = directory.absolutePath, createdAt = now, updatedAt = now)
    }

    private companion object {
        const val NOT_INSERTED = -1L
        const val MAX_ERROR_CHARS = 500
    }
}
