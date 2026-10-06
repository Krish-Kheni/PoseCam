package com.posecam.core.sync

import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

/** Wires the real repository/processor to in-memory fakes and a temp-dir "app storage". */
class SyncFixture {
    var now = 1_000_000L
    var recording = false
    val root: File = createTempDirectory(prefix = "posecam-sync-").toFile()
    val capturesRoot = File(root, "captures").also { it.mkdirs() }
    val staging = UploadStaging(File(root, "upload-staging"))
    val dao = FakeUploadDao()
    val forgotten = mutableListOf<String>()
    val repo = UploadRepository(dao, Transactor.None, { now }, { forgotten += it })
    val api = FakeCloudApi()
    val s3 = FakeS3Transport()
    val materializer = FrameChunkMaterializer(repo, kotlinx.coroutines.Dispatchers.Unconfined)
    val processor = UploadProcessor(
        repo, api, s3, FileHasher(), { "install-1" }, "test", materializer, isRecording = { recording },
    )

    /** A PoseCam-style session folder: `captures/<id>/manifest.json` with `"complete"`. */
    fun sessionDir(sessionId: String = SESSION, status: String = "complete"): File {
        val complete = status == "complete"
        val dir = File(capturesRoot, sessionId).also { it.mkdirs() }
        File(dir, "manifest.json").writeText(
            """{
  "format_version": "posecam-5",
  "session_id": "$sessionId",
  "start_wall_time_utc": "2026-09-17T09:00:00.000Z",
  "complete": $complete,
  "frame_count": 3
}""",
        )
        return dir
    }

    /** Writes [content] at [relative] inside [dir] and describes it as a plain queue entry. */
    fun file(dir: File, relative: String, content: String): QueuedFile {
        val file = File(dir, relative).also { it.parentFile.mkdirs(); it.writeText(content) }
        return QueuedFile(relative, file.absolutePath, UploadSourceKind.PLAIN, file.length())
    }

    /** `frames/000000_<ts>.jpg ...`: [count] fake JPEGs, each with distinct bytes. */
    fun frames(dir: File, count: Int, startIndex: Int = 0) {
        val frames = File(dir, "frames").also { it.mkdirs() }
        for (i in startIndex until startIndex + count) {
            File(frames, "%06d_%d.jpg".format(i, 1_000_000L + i * 33_000_000L)).writeBytes(ByteArray(40 + i % 7) { (i + it).toByte() })
        }
    }

    fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Queues [files] as a finalized session and marks its cloud session as created. */
    suspend fun queueFinalized(dir: File, vararg files: QueuedFile, created: Boolean = true, status: String = "complete") {
        repo.finalizeSession(dir.name, dir, status, files.toList())
        if (created) repo.markSessionCreated(dir.name)
    }

    suspend fun row(sessionId: String, relative: String) = requireNotNull(dao.findUpload(sessionId, relative))

    companion object {
        const val SESSION = "capture-20260917T090000-a3f9c1"
    }
}

/** Test convenience: the session is the folder the file lives in (what [SyncFixture.file] creates). */
suspend fun UploadRepository.enqueue(file: QueuedFile): EnqueueResult {
    val path = file.localPath.replace(File.separatorChar, '/')
    val directory = File(path.removeSuffix(file.relativePath).trimEnd('/'))
    return enqueue(directory.name, directory, file)
}
