package com.posecam.core.sync

import com.posecam.PipelineExporter
import com.posecam.core.cloud.CloudLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Writes a recording's pipeline export (one folder per clean segment) and returns those folders. Blocking. */
fun interface SessionExporter {
    /** @throws PipelineExporter.ExportException when the recording cannot be exported, for a reason that will not change. */
    fun export(session: File, outputRoot: File, rotateDegrees: Int, onProgress: (done: Int, total: Int) -> Unit): List<File>
}

/** The real thing: [PipelineExporter], the same code (and the same rotation) as "Export for pipeline" in Recordings. */
class PipelineSessionExporter(private val appVersion: String) : SessionExporter {
    override fun export(session: File, outputRoot: File, rotateDegrees: Int, onProgress: (done: Int, total: Int) -> Unit): List<File> =
        PipelineExporter.export(session, outputRoot, rotateDegrees, appVersion) { _, done, total -> onProgress(done, total) }.folders
}

/**
 * Makes the pipeline export of every filed recording that does not have one yet, and queues its files for upload.
 * [UploadProcessor] runs it before it sends anything, on the upload worker's background-priority threads.
 *
 *  * **Never while recording.** The check is made between frames, so a take that starts mid-export stops it within a
 *    frame: its half-written output is deleted and the recording stays PENDING for the next run.
 *  * **An export that cannot exist must not fail the session.** An off-protocol or otherwise unexportable recording is
 *    recorded as such (and shown in Recordings: it will never appear in LabelNow) while its raw upload carries on.
 *  * **The rotation is a setting, not a question.** Until the collector has chosen one, nothing is exported (a
 *    wrongly rotated video would be uploaded and labeled without anyone noticing); the recordings simply stay PENDING.
 */
class PipelineExportStage(
    private val repository: UploadRepository,
    private val staging: UploadStaging,
    private val exporter: SessionExporter,
    /** Clockwise degrees so the gripper jaws point up; null while the collector has not set it. */
    private val rotation: () -> Int?,
    private val isRecording: () -> Boolean = { ActiveRecordingSessions.snapshot().isNotEmpty() },
) {
    /** Whether a run could export anything now; keeps the worker from starting for nothing. */
    suspend fun hasWork(): Boolean = rotation() != null && repository.sessionsNeedingExport().isNotEmpty()

    /** Exports every pending recording ([onlySessionId]: just that one). Stops quietly when a take starts. */
    suspend fun runPending(onlySessionId: String? = null) {
        val degrees = rotation() ?: return
        for (session in repository.sessionsNeedingExport().filter { onlySessionId == null || it.sessionId == onlySessionId }) {
            if (isRecording()) return
            if (!exportOne(session, degrees)) return
        }
    }

    /** Returns false when the run must stop (a take started, or the worker was cancelled). */
    private suspend fun exportOne(session: CloudSessionEntity, degrees: Int): Boolean {
        val outputRoot = staging.exportRoot(session.sessionId)
        val job = currentCoroutineContext()[Job]
        // The encoder wraps whatever its progress callback throws, so "stop now" is a flag, not an exception type.
        var stopped = false
        try {
            val folders = exporter.export(File(session.directoryPath), outputRoot, degrees) { _, _ ->
                if (isRecording() || job?.isActive == false) {
                    stopped = true
                    throw IllegalStateException("Export stopped")
                }
            }
            check(folders.isNotEmpty()) { "The exporter produced no recording" }
            repository.completeExport(session.sessionId, UploadPlan.forExport(outputRoot))
            return true
        } catch (cancel: CancellationException) {
            outputRoot.deleteRecursively()
            throw cancel
        } catch (refused: PipelineExporter.ExportException) {
            outputRoot.deleteRecursively()
            val state = if (refused.reason == PipelineExporter.Reason.OFF_PROTOCOL) ExportState.OFF_PROTOCOL else ExportState.NOT_EXPORTABLE
            repository.markExportMissing(session.sessionId, state, refused.message.orEmpty())
        } catch (error: Exception) {
            outputRoot.deleteRecursively()
            if (stopped) {
                // A cancelled worker must stop; a take that started merely ends this pass. Either way it stays PENDING.
                job?.ensureActive()
                return false
            }
            // Storage full, encoder trouble, a bug: nothing is known to be wrong with the recording, so a retry is allowed.
            CloudLog.e("export_failed", error, "session" to session.sessionId)
            repository.markExportMissing(session.sessionId, ExportState.FAILED, error.message ?: error.javaClass.simpleName)
        }
        return true
    }
}
