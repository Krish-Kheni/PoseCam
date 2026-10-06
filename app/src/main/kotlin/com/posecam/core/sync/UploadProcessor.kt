package com.posecam.core.sync

import com.posecam.core.cloud.CloudApi
import com.posecam.core.cloud.CloudException
import com.posecam.core.cloud.CloudHttpException
import com.posecam.core.cloud.CloudLog
import com.posecam.core.cloud.CloudNetworkException
import com.posecam.core.cloud.CreateSessionRequest
import com.posecam.core.cloud.SessionFileRef
import com.posecam.core.cloud.UploadMode
import com.posecam.core.cloud.UploadRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Outcome of one pass over the queue, with what changed (for user messaging). */
data class QueueRunReport(val result: QueueRunResult, val sessionsSynced: Int)

enum class QueueRunResult {
    /** Nothing is left that can make progress right now. */
    DONE,

    /** Something hit a temporary failure; WorkManager should retry with exponential backoff. */
    RETRY,
}

/**
 * Drains the persistent upload queue: creates cloud sessions, uploads files one at a time
 * (single PUT or resumable multipart), has the backend verify each, and confirms finished
 * sessions. All durable progress lives in Room via [UploadRepository], so this class can be
 * stopped at any instant -- worker cancelled, process killed -- and a later run continues.
 *
 * It knows nothing about WorkManager, recording or authentication.
 */
class UploadProcessor(
    private val repository: UploadRepository,
    private val api: CloudApi,
    private val transport: S3Transport,
    private val hasher: FileHasher,
    private val installationId: () -> String,
    private val appVersion: String,
    /** Builds the file for rows whose source is not a plain file (frame chunks). */
    private val materializer: UploadMaterializer = UploadMaterializer.Plain,
    /** True while a take is being recorded: nothing is hashed, built or sent then (see [UploadCoordinator]). */
    private val isRecording: () -> Boolean = { ActiveRecordingSessions.snapshot().isNotEmpty() },
    /** How often the in-flight byte count of a single PUT is persisted for the progress display. */
    private val progressIntervalMs: Long = 1_000,
) {
    private val multipart = MultipartUploader(api, transport, repository, hasher)

    private sealed interface FileOutcome {
        data object Done : FileOutcome
        data object Failed : FileOutcome
        data class Retry(val networkDown: Boolean) : FileOutcome
    }

    /** Whether a run could do anything: lets the worker skip starting a foreground service for nothing. */
    suspend fun hasWork(): Boolean =
        repository.runnable().isNotEmpty() || repository.sessionsNeedingCreation().isNotEmpty() ||
            repository.sessionsReadyToComplete().isNotEmpty()

    suspend fun runQueue(onlySessionId: String? = null): QueueRunResult = runQueueReport(onlySessionId).result

    /**
     * Uploads what is queued, one file at a time. With [onlySessionId] (the user tapped upload on one
     * session) every other session is left alone. Runs are serialized, so a one-session run and a
     * whole-queue run can never upload the same file at once.
     */
    suspend fun runQueueReport(onlySessionId: String? = null): QueueRunReport = runLock.withLock {
        syncedThisRun = 0
        val result = drainQueue(onlySessionId)
        QueueRunReport(result, syncedThisRun)
    }

    private val runLock = Mutex()
    private var syncedThisRun = 0

    private suspend fun drainQueue(onlySessionId: String?): QueueRunResult {
        // Recording wins: PoseCam's documented failure under load is dropped images, so no upload work at all
        // (hashing, zipping, TLS, radio) runs while a take is in progress. The next run starts after Stop.
        if (isRecording()) return QueueRunResult.DONE
        var needsRetry = false

        when (createPendingSessions(onlySessionId)) {
            QueueRunResult.RETRY -> needsRetry = true
            QueueRunResult.DONE -> Unit
        }

        // One failing file must not starve the rest, so it is skipped for the remainder of this run
        // and retried by the next (backed-off) run. A dead network aborts the run outright.
        val skipped = mutableSetOf<Long>()
        while (true) {
            if (isRecording()) return QueueRunResult.DONE
            val next = repository.runnable().firstOrNull { it.id !in skipped && (onlySessionId == null || it.sessionId == onlySessionId) } ?: break
            when (val outcome = processFile(next)) {
                FileOutcome.Done, FileOutcome.Failed -> Unit
                is FileOutcome.Retry -> {
                    skipped += next.id
                    needsRetry = true
                    if (outcome.networkDown) return QueueRunResult.RETRY
                }
            }
        }

        if (completeFinishedSessions(onlySessionId) == QueueRunResult.RETRY) needsRetry = true
        return if (needsRetry) QueueRunResult.RETRY else QueueRunResult.DONE
    }

    // ---- session creation ------------------------------------------------------------------

    private suspend fun createPendingSessions(onlySessionId: String?): QueueRunResult {
        var result = QueueRunResult.DONE
        for (session in repository.sessionsNeedingCreation().filter { onlySessionId == null || it.sessionId == onlySessionId }) {
            val manifest = SessionManifestInfo.read(File(session.directoryPath))
            val request = CreateSessionRequest(
                sessionId = session.sessionId,
                deviceId = installationId(),
                createdAt = manifest?.startWallTimeUtc ?: isoUtc(session.createdAt),
                recordingStatus = session.recordingStatus ?: manifest?.recordingStatus ?: "recording",
                appVersion = appVersion,
                pipe = session.pipe,
            )
            try {
                val response = api.createSession(request)
                repository.markSessionCreated(session.sessionId)
                CloudLog.i("session_created", "session" to session.sessionId, "new" to response.created)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: CloudException) {
                if (error.retryable) {
                    repository.noteSessionError(session.sessionId, error.message.orEmpty())
                    CloudLog.w("session_create_retry", "session" to session.sessionId, "error" to error.message)
                    if (error is CloudNetworkException) return QueueRunResult.RETRY
                    result = QueueRunResult.RETRY
                } else {
                    repository.markSessionFailed(session.sessionId, error.message.orEmpty())
                    CloudLog.e("session_create_failed", error, "session" to session.sessionId)
                }
            }
        }
        return result
    }

    // ---- one file --------------------------------------------------------------------------

    private suspend fun processFile(initial: UploadEntity): FileOutcome {
        var row = initial
        var file = File(row.localPath)
        val isChunk = row.kind == UploadSourceKind.FRAME_CHUNK
        try {
            // A chunk that only awaits verification no longer needs its bytes (it may have been released already).
            if (!(isChunk && row.state == UploadState.UPLOADED)) {
                if (isChunk) {
                    // Build (or find) the zip first: its real size replaces the estimate queued at finalize.
                    file = materializer.ensure(row)
                    row = repository.get(row.id) ?: return FileOutcome.Done
                }
                if (!file.isFile) {
                    repository.markFailed(row.id, "Local file is missing")
                    CloudLog.e("upload_failed", null, "session" to row.sessionId, "path" to row.relativePath, "reason" to "file_missing")
                    return FileOutcome.Failed
                }
                if (file.length() != row.sizeBytes) {
                    repository.rebaseline(row.id, file.length())
                    row = repository.get(row.id) ?: return FileOutcome.Done
                }
            }
            if (row.state == UploadState.PENDING) repository.markPreparing(row.id)

            if (row.state != UploadState.UPLOADED) {
                val sha256 = row.sha256 ?: hasher.sha256Hex(file).also { repository.saveSha256(row.id, it) }
                // Hashing a large file takes a while; if it changed meanwhile the digest is worthless.
                if (file.length() != row.sizeBytes) throw FileChangedException("${row.relativePath} changed while hashing")

                val plan = api.requestUpload(row.sessionId, UploadRequest(row.relativePath, row.sizeBytes, sha256))
                when (plan.mode) {
                    UploadMode.ALREADY_VERIFIED -> {
                        repository.markVerified(row.id)
                        materializer.release(row)
                        CloudLog.i("verification_completed", "session" to row.sessionId, "path" to row.relativePath, "already" to true)
                        return FileOutcome.Done
                    }
                    UploadMode.SINGLE -> {
                        val url = plan.url ?: throw CloudHttpException(502, "MALFORMED_RESPONSE", "no upload URL")
                        repository.markUploading(row.id)
                        CloudLog.i("upload_started", "session" to row.sessionId, "path" to row.relativePath, "mode" to "single", "bytes" to row.sizeBytes)
                        putWithProgress(row, url, plan.headers, file)
                    }
                    UploadMode.MULTIPART -> {
                        val partSize = plan.partSizeBytes
                            ?: throw CloudHttpException(502, "MALFORMED_RESPONSE", "no part size")
                        repository.markUploading(row.id)
                        CloudLog.i("upload_started", "session" to row.sessionId, "path" to row.relativePath, "mode" to "multipart", "bytes" to row.sizeBytes)
                        try {
                            multipart.upload(row, file, sha256, partSize)
                        } catch (conflict: CloudHttpException) {
                            // We lost track of a multipart upload the backend already finished: nothing to send.
                            if (!conflict.isAlreadyUploaded()) throw conflict
                            CloudLog.i("upload_already_complete", "session" to row.sessionId, "path" to row.relativePath)
                        }
                    }
                }
                repository.markUploaded(row.id)
                CloudLog.i("upload_completed", "session" to row.sessionId, "path" to row.relativePath)
            }

            api.verifyUpload(row.sessionId, row.relativePath)
            repository.markVerified(row.id)
            materializer.release(row)
            CloudLog.i("verification_completed", "session" to row.sessionId, "path" to row.relativePath)
            return FileOutcome.Done
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (changed: FileChangedException) {
            repository.rebaseline(row.id, file.length())
            return FileOutcome.Retry(networkDown = false)
        } catch (missing: FileNotFoundException) {
            repository.markFailed(row.id, "Local file is missing")
            return FileOutcome.Failed
        } catch (error: CloudException) {
            return handleCloudError(row, error)
        } catch (error: IOException) {
            // Local read problem (not the network): temporary until proven otherwise.
            return retry(row, "Local I/O error: ${error.message}", networkDown = false)
        }
    }

    /** A single PUT reports bytes as it goes; persist them about once a second so progress is visible. */
    private suspend fun putWithProgress(row: UploadEntity, url: String, headers: Map<String, String>, file: File) {
        val sent = AtomicLong(0)
        coroutineScope {
            val ticker = launch {
                while (true) {
                    delay(progressIntervalMs)
                    repository.saveProgress(row.id, sent.get())
                }
            }
            try {
                transport.put(url, headers, file, 0, row.sizeBytes, onProgress = { sent.set(it) })
            } finally {
                ticker.cancel()
            }
        }
    }

    private suspend fun handleCloudError(row: UploadEntity, error: CloudException): FileOutcome {
        val http = error as? CloudHttpException
        return when {
            error is CloudNetworkException -> retry(row, "Network: ${error.message}", networkDown = true)

            http?.code == CloudHttpException.SESSION_NOT_FOUND -> {
                repository.markSessionNotCreated(row.sessionId)
                retry(row, "Cloud session missing; recreating", networkDown = false)
            }

            http?.code == CloudHttpException.UPLOAD_NOT_FOUND || http?.code == CloudHttpException.S3_NO_SUCH_UPLOAD ||
                http?.code == CloudHttpException.FILE_NOT_FOUND || http?.code == CloudHttpException.UPLOAD_CONFLICT -> {
                // The multipart upload expired, was aborted, or disagrees with the backend's record
                // (or the backend forgot the file): only restarting the file can fix that.
                repository.resetToPending(row.id, "Cloud upload state out of sync (${http.code}); restarting")
                CloudLog.w("upload_restart", "session" to row.sessionId, "path" to row.relativePath)
                FileOutcome.Retry(networkDown = false)
            }

            http?.status == 409 && http.code == CloudHttpException.VERIFICATION_FAILED -> {
                if (row.verifyFailures + 1 >= MAX_VERIFY_FAILURES) {
                    repository.markFailed(row.id, "Cloud copy failed verification repeatedly: ${http.details ?: http.message}")
                    CloudLog.e("upload_failed", error, "session" to row.sessionId, "path" to row.relativePath)
                    FileOutcome.Failed
                } else {
                    repository.resetToPending(row.id, "Verification failed; re-uploading", countVerifyFailure = true)
                    CloudLog.w("verification_failed", "session" to row.sessionId, "path" to row.relativePath)
                    FileOutcome.Retry(networkDown = false)
                }
            }

            error.retryable -> retry(row, error.message.orEmpty(), networkDown = false)

            else -> {
                repository.markFailed(row.id, error.message.orEmpty())
                CloudLog.e("upload_failed", error, "session" to row.sessionId, "path" to row.relativePath)
                FileOutcome.Failed
            }
        }
    }

    private suspend fun retry(row: UploadEntity, message: String, networkDown: Boolean): FileOutcome {
        repository.recordRetry(row.id, message)
        CloudLog.w(
            "upload_retry", "session" to row.sessionId, "path" to row.relativePath,
            "attempt" to row.retryCount + 1, "error" to message,
        )
        return FileOutcome.Retry(networkDown)
    }

    // ---- session completion ----------------------------------------------------------------

    private suspend fun completeFinishedSessions(onlySessionId: String?): QueueRunResult {
        var result = QueueRunResult.DONE
        for (session in repository.sessionsReadyToComplete().filter { onlySessionId == null || it.sessionId == onlySessionId }) {
            val files = repository.uploadsForSession(session.sessionId)
                .filter { it.state == UploadState.VERIFIED }
                .map { SessionFileRef(it.relativePath, it.sizeBytes) }
            try {
                api.completeSession(session.sessionId, session.recordingStatus ?: "complete", files)
                repository.markSessionSynced(session.sessionId)
                syncedThisRun++
                CloudLog.i("session_synced", "session" to session.sessionId, "files" to files.size)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: CloudException) {
                val http = error as? CloudHttpException
                when {
                    http?.code == CloudHttpException.SESSION_INCOMPLETE -> {
                        // The backend disagrees about which files it holds: send those again.
                        requeueFilesReportedBy(session.sessionId, http.details)
                        result = QueueRunResult.RETRY
                    }
                    http?.code == CloudHttpException.SESSION_NOT_FOUND -> {
                        repository.markSessionNotCreated(session.sessionId)
                        result = QueueRunResult.RETRY
                    }
                    error.retryable -> {
                        repository.noteSessionError(session.sessionId, error.message.orEmpty())
                        CloudLog.w("session_complete_retry", "session" to session.sessionId, "error" to error.message)
                        if (error is CloudNetworkException) return QueueRunResult.RETRY
                        result = QueueRunResult.RETRY
                    }
                    else -> {
                        repository.markSessionFailed(session.sessionId, error.message.orEmpty())
                        CloudLog.e("session_complete_failed", error, "session" to session.sessionId)
                    }
                }
            }
        }
        return result
    }

    private suspend fun requeueFilesReportedBy(sessionId: String, details: String?) {
        val json = runCatching { JSONObject(details.orEmpty()) }.getOrNull()
        val paths = buildSet {
            for (key in listOf("missing", "unverified")) {
                val array = json?.optJSONArray(key) ?: continue
                for (i in 0 until array.length()) add(array.optString(i))
            }
        }
        val rows = repository.uploadsForSession(sessionId)
        for (row in rows) {
            if (row.relativePath in paths && row.state == UploadState.VERIFIED) {
                repository.resetToPending(row.id, "Backend reports this file is not verified; re-uploading")
            }
        }
    }

    /** 409 UPLOAD_CONFLICT {reason: ALREADY_UPLOADED | ALREADY_VERIFIED}: the bytes are already in S3. */
    private fun CloudHttpException.isAlreadyUploaded(): Boolean =
        status == 409 && code == CloudHttpException.UPLOAD_CONFLICT &&
            details?.let { "ALREADY_UPLOADED" in it || "ALREADY_VERIFIED" in it } == true

    private fun isoUtc(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))

    private companion object {
        const val MAX_VERIFY_FAILURES = 3
    }
}
