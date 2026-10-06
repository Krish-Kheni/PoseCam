package com.posecam.core.sync

import com.posecam.core.cloud.CloudLog
import java.io.File
import java.util.concurrent.TimeUnit

data class CleanupReport(val deletedSessions: List<String>, val freedBytes: Long)

/** Why a session may not (yet) be removed locally. */
enum class RetentionBlock {
    NOT_SYNCED,
    STILL_RECORDING,
    RECORDING_NOT_FINAL,
    FILES_NOT_VERIFIED,
    LOCAL_FILE_NOT_COVERED,
    FRAMES_NOT_COVERED,
}

/**
 * Decides when a recording's local copy may be removed. Deliberately conservative:
 *
 *  * Only sessions the backend has confirmed SYNCED, whose recording is finalized, and whose
 *    every required file is VERIFIED -- and whose local files still match what was verified --
 *    are ever eligible. LOCAL_ONLY, PENDING, UPLOADING and FAILED sessions are never touched.
 *  * Even then they are kept for [retentionDays] (default 7), unless the phone is short on
 *    storage, in which case the oldest synced sessions go first until the pressure is gone.
 *  * It never runs on the recording path and cannot weaken the existing low-storage guard: it
 *    only ever frees space by removing data that already exists, verified, in the cloud.
 */
class LocalRetentionManager(
    private val repository: UploadRepository,
    private val enabled: () -> Boolean,
    private val retentionDays: () -> Int,
    private val storagePressure: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deleteDirectory: (File) -> Boolean = { it.deleteRecursively() },
    private val isActivelyRecording: (String) -> Boolean = ActiveRecordingSessions::contains,
) {
    suspend fun cleanup(): CleanupReport {
        if (!enabled()) return CleanupReport(emptyList(), 0)
        val retentionMs = TimeUnit.DAYS.toMillis(retentionDays().toLong())
        val deleted = mutableListOf<String>()
        var freed = 0L

        val candidates = repository.allSessions()
            .filter { it.syncedAt != null }
            .sortedBy { it.syncedAt }
        for (session in candidates) {
            val directory = File(session.directoryPath)
            if (!directory.isDirectory) {
                repository.forgetSession(session.sessionId)
                continue
            }
            if (blockedReason(session) != null) continue
            val pastRetention = clock() - requireNotNull(session.syncedAt) >= retentionMs
            if (!pastRetention && !storagePressure()) continue

            val bytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (deleteDirectory(directory)) {
                repository.forgetSession(session.sessionId)
                deleted += session.sessionId
                freed += bytes
                CloudLog.i("cleanup", "session" to session.sessionId, "bytes" to bytes, "reason" to if (pastRetention) "retention" else "storage_pressure")
            }
        }
        return CleanupReport(deleted, freed)
    }

    /** Null when the session is safe to delete; otherwise the first reason it is not. */
    suspend fun blockedReason(session: CloudSessionEntity): RetentionBlock? {
        if (session.syncedAt == null) return RetentionBlock.NOT_SYNCED
        if (isActivelyRecording(session.sessionId)) return RetentionBlock.STILL_RECORDING
        val directory = File(session.directoryPath)
        // "Final" is what finalizeSession recorded: PoseCam writes complete:false for a killed take, which was
        // still queued (as evidence) and, once synced, is as safe to remove as any other.
        if (!session.recordingFinal || SessionManifestInfo.read(directory) == null) {
            return RetentionBlock.RECORDING_NOT_FINAL
        }
        val rows = repository.uploadsForSession(session.sessionId)
        val required = rows.filter { it.required }
        if (required.isEmpty() || required.any { it.state != UploadState.VERIFIED }) return RetentionBlock.FILES_NOT_VERIFIED

        // Every required file on disk must be one we verified at exactly its current size, so a
        // file created or changed after the sync (and so never uploaded) is not deleted with it.
        val verified = rows.filter { it.state == UploadState.VERIFIED }.associateBy { it.relativePath }
        val uncovered = CloudFileRules.listPlainUploadable(directory).any { file ->
            CloudFileRules.isRequired(file.name) && verified[file.name]?.sizeBytes != file.length()
        }
        if (uncovered) return RetentionBlock.LOCAL_FILE_NOT_COVERED

        // The JPEGs are not plain files: they live inside verified chunk zips. Every JPEG on disk must be in one,
        // so a frame that exists locally but is in no verified chunk blocks deletion.
        val covered = rows.filter { it.kind == UploadSourceKind.FRAME_CHUNK && it.state == UploadState.VERIFIED }
            .sumOf { it.itemCount }
        return if (FrameChunks.countJpegs(File(directory, "frames")) != covered) RetentionBlock.FRAMES_NOT_COVERED else null
    }
}
