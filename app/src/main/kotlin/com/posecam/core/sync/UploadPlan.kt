package com.posecam.core.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** One row to put in the upload queue; the queue owns state, this only describes the source. */
data class QueuedFile(
    val relativePath: String,
    /** The file itself for [UploadSourceKind.PLAIN]; where the chunk will be built for [UploadSourceKind.FRAME_CHUNK]. */
    val localPath: String,
    val kind: UploadSourceKind,
    /** For chunks this is an estimate until the zip is built. */
    val sizeBytes: Long,
    val itemCount: Int = 0,
    /** SHA-256 hex when already known; null means the worker hashes it (always the case today). */
    val sha256: String? = null,
)

/**
 * Where frame chunks are built. It lives under `getExternalFilesDir(null)/upload-staging/`, NOT in
 * `cacheDir`: the OS may purge the cache at any time, which would turn a half-finished upload into
 * "local file missing". Chunks are deleted as soon as their row is verified.
 */
class UploadStaging(private val root: File) {
    fun chunkFile(sessionId: String, relativePath: String): File = File(File(root, sessionId), relativePath)

    /** Where [PipelineExportStage] writes a recording's pipeline export: one `<stem>/` folder per segment. */
    fun exportRoot(sessionId: String): File = File(File(root, sessionId), "export")

    fun purgeSession(sessionId: String) {
        runCatching { File(root, sessionId).deleteRecursively() }
    }
}

object UploadPlan {
    /** Everything uploadable in a finished session: its plain files plus one row per frame chunk. */
    fun forSession(sessionId: String, directory: File, staging: UploadStaging): List<QueuedFile> {
        val plain = CloudFileRules.listPlainUploadable(directory).map {
            QueuedFile(it.name, it.absolutePath, UploadSourceKind.PLAIN, it.length())
        }
        val chunks = FrameChunks.plan(File(directory, "frames")).map {
            QueuedFile(
                relativePath = it.relativePath,
                localPath = staging.chunkFile(sessionId, it.relativePath).absolutePath,
                kind = UploadSourceKind.FRAME_CHUNK,
                sizeBytes = it.estimatedBytes,
                itemCount = it.itemCount,
            )
        }
        return plain + chunks
    }

    /**
     * One row per file per exported segment: `export/<stem>/RGB_<stem>.mp4`, `export/<stem>/AR_Pose_<stem>.txt` and
     * `export/<stem>/posecam_export.json`. [exportRoot] is the folder [com.posecam.PipelineExporter] filled (one folder per
     * clean segment, named by its stem). A folder missing any of the three files, or named so the backend would refuse
     * its paths, is an error here and never a half-queued export: the server requires the stem inside each filename to
     * equal its folder's.
     */
    fun forExport(exportRoot: File): List<QueuedFile> {
        val segments = exportRoot.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        return segments.flatMap { folder ->
            val stem = folder.name
            listOf("RGB_$stem.mp4", "AR_Pose_$stem.txt", EXPORT_PROVENANCE).map { name ->
                val file = File(folder, name)
                val relativePath = "$EXPORT_DIR/$stem/$name"
                check(file.isFile) { "The export of $stem has no $name" }
                check(CloudFileRules.classify(relativePath) == UploadFileType.EXPORT) {
                    "The export folder name '$stem' is not one the backend accepts"
                }
                QueuedFile(relativePath, file.absolutePath, UploadSourceKind.EXPORT, file.length())
            }
        }
    }

    const val EXPORT_DIR = "export"
    const val EXPORT_PROVENANCE = "posecam_export.json"
}

/** Turns a queue row into the file whose bytes are hashed and sent. */
interface UploadMaterializer {
    /** Returns the file to upload, building it first if the row's source is not a plain file. */
    suspend fun ensure(row: UploadEntity): File

    /** The row is verified: staged bytes are no longer needed (a frame chunk, or a pipeline-export file). Never throws. */
    fun release(row: UploadEntity)

    /** A file that is already what it is. */
    object Plain : UploadMaterializer {
        override suspend fun ensure(row: UploadEntity): File = File(row.localPath)

        override fun release(row: UploadEntity) = Unit
    }
}

/**
 * Builds a frame chunk on demand, one at a time (at most about 35 MB staged), and reuses a staged
 * file that is already there. Safe to run again after a crash: a chunk is built deterministically
 * ([DeterministicZip]) and renamed into place only when complete.
 */
class FrameChunkMaterializer(
    private val repository: UploadRepository,
    private val dispatcher: CoroutineDispatcher,
) : UploadMaterializer {
    override suspend fun ensure(row: UploadEntity): File {
        if (row.kind != UploadSourceKind.FRAME_CHUNK) return File(row.localPath)
        val staged = File(row.localPath)
        if (!staged.isFile) {
            val index = FrameChunks.indexOf(row.relativePath) ?: throw FileNotFoundException("not a frame chunk: ${row.relativePath}")
            val session = repository.session(row.sessionId) ?: throw FileNotFoundException("unknown session ${row.sessionId}")
            withContext(dispatcher) { FrameChunks.build(File(session.directoryPath, "frames"), index, staged) }
        }
        // The queued size was an estimate (the sum of the JPEG sizes); from here on it is the zip's real size.
        if (staged.length() != row.sizeBytes) repository.saveMaterialized(row.id, staged.length())
        return staged
    }

    override fun release(row: UploadEntity) {
        when (row.kind) {
            UploadSourceKind.PLAIN -> Unit
            UploadSourceKind.FRAME_CHUNK -> runCatching {
                val chunk = File(row.localPath)
                chunk.delete()
                // The session's staging folder goes with its last chunk (delete() refuses a non-empty folder).
                chunk.parentFile?.delete()
            }
            // `<session>/export/<stem>/<file>`: each folder goes with its last file, up to the session's staging folder.
            UploadSourceKind.EXPORT -> runCatching {
                var folder: File? = File(row.localPath).also { it.delete() }.parentFile
                repeat(EXPORT_FOLDER_DEPTH) {
                    folder?.delete()
                    folder = folder?.parentFile
                }
            }
        }
    }

    private companion object {
        /** `<stem>`, `export`, `<session>`: the folders above an export file that are removed once empty. */
        const val EXPORT_FOLDER_DEPTH = 3
    }
}
