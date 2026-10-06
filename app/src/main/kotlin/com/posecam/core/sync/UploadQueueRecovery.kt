package com.posecam.core.sync

import com.posecam.core.cloud.CloudLog
import java.io.File

data class QueueRecoveryReport(
    val sessionsScanned: Int,
    val sessionsSkippedWhileRecording: Int,
    val filesQueued: Int,
    val orphansDropped: Int,
)

/**
 * Rebuilds and resumes the upload queue on app start. It makes the queue self-healing after process
 * death, reboot, force-stop or any missed enqueue, and it adopts recordings made before cloud upload
 * existed (which can be a large backlog: the first launch after an update may start a long upload).
 *
 * PoseCam never records without its capture screen in the foreground, so after a cold start nothing is
 * being written. A session whose manifest says `"complete": false` is therefore a take that was killed,
 * not one in progress, and is queued as `incomplete` (evidence, never a deliverable).
 *
 * Safety rules:
 *  * A session is queued only if no writer for it is open in this process, so a file that is still being
 *    written is never queued.
 *  * A folder whose id the backend would refuse permanently is skipped, never queued.
 *  * Everything is idempotent: the unique (sessionId, relativePath) index absorbs repeats.
 *  * Interrupted work resumes from Room state, including partially uploaded multipart files.
 */
class UploadQueueRecovery(
    /** `getExternalFilesDir(null)/captures`: one folder per recording. */
    private val capturesRoot: File,
    private val repository: UploadRepository,
    private val scheduler: UploadScheduler,
    private val staging: UploadStaging,
    private val isActivelyRecording: (String) -> Boolean = ActiveRecordingSessions::contains,
) {
    suspend fun run(): QueueRecoveryReport {
        // 1. A worker killed mid-prepare left PREPARING rows; make them runnable again.
        repository.normalizeInterrupted()

        // 2. Detect finalized sessions (including pre-existing ones) with files missing from the queue.
        val directories = capturesRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        var skipped = 0
        var queued = 0
        val present = mutableSetOf<String>()
        for (directory in directories) {
            val info = SessionManifestInfo.read(directory) ?: continue
            if (!CloudFileRules.isValidSessionId(info.sessionId)) continue
            present += info.sessionId
            if (isActivelyRecording(info.sessionId)) {
                skipped++
                continue
            }
            val files = UploadPlan.forSession(info.sessionId, directory, staging)
            val before = repository.uploadsForSession(info.sessionId).size
            repository.finalizeSession(info.sessionId, directory, info.recordingStatus, files)
            queued += repository.uploadsForSession(info.sessionId).size - before
        }

        // 3. Rows of sessions that were deleted from disk are dead weight (and could never upload).
        var dropped = 0
        for (session in repository.allSessions()) {
            if (session.sessionId !in present && !File(session.directoryPath).isDirectory && !isActivelyRecording(session.sessionId)) {
                repository.forgetSession(session.sessionId)
                dropped++
            }
        }

        // 4. Resume: retries pending session creation, interrupted uploads and multipart uploads.
        // (Never while a take is in progress: the processor and the worker also refuse to run then.)
        scheduler.schedule()

        val report = QueueRecoveryReport(directories.size, skipped, queued, dropped)
        CloudLog.i(
            "queue_recovery",
            "sessions" to report.sessionsScanned, "skipped_recording" to report.sessionsSkippedWhileRecording,
            "queued" to report.filesQueued, "orphans" to report.orphansDropped,
        )
        return report
    }
}
