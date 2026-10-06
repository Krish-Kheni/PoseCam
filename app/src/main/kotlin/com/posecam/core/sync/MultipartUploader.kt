package com.posecam.core.sync

import com.posecam.core.cloud.CloudApi
import com.posecam.core.cloud.CloudLog
import com.posecam.core.cloud.CloudHttpException
import com.posecam.core.cloud.CompletedPart
import com.posecam.core.cloud.MultipartPartInfo
import com.posecam.core.cloud.PartUrlRequest
import com.posecam.core.cloud.UploadRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** The local file's length no longer matches what was queued; the upload must start over. */
class FileChangedException(message: String) : Exception(message)

/**
 * Resumable multipart upload of one large file. Progress (finished parts + their S3 checksums
 * and ETags) is persisted to Room after every part, so after a crash, a lost network or a
 * killed worker only the part that was in flight is repeated -- never the whole file.
 *
 * Integrity: each part is sent with its own SHA-256, which S3 validates server-side. The
 * multipart ETag is only a handle used to assemble the object and is never treated as a checksum.
 */
class MultipartUploader(
    private val api: CloudApi,
    private val transport: S3Transport,
    private val repository: UploadRepository,
    private val hasher: FileHasher,
) {
    suspend fun upload(upload: UploadEntity, file: File, sha256: String, partSizeBytes: Long) {
        val saved = MultipartState.decode(upload.multipartState)
            ?.takeIf { it.uploadId == upload.multipartUploadId && it.partSizeBytes == partSizeBytes }
        // Parts S3 already holds (reported by the backend) when our own record of them is gone.
        var serverParts = emptyMap<Int, MultipartPartInfo>()

        var state: MultipartState
        if (saved == null) {
            val start = api.startMultipartUpload(upload.sessionId, UploadRequest(upload.relativePath, upload.sizeBytes, sha256))
            state = MultipartState(start.uploadId, start.partSizeBytes, start.partCount)
            serverParts = start.uploadedParts.associateBy { it.partNumber }
            repository.saveMultipart(upload.id, state)
            CloudLog.i(
                "multipart_started", "session" to upload.sessionId, "path" to upload.relativePath,
                "parts" to start.partCount, "resumed" to start.resumed,
            )
        } else {
            state = saved
            CloudLog.i(
                "multipart_resumed", "session" to upload.sessionId, "path" to upload.relativePath,
                "done" to saved.parts.size, "parts" to saved.partCount,
            )
        }

        for (partNumber in 1..state.partCount) {
            currentCoroutineContext().ensureActive()
            if (state.hasPart(partNumber)) continue
            // The queued file is immutable; if it changed, the parts already uploaded are meaningless.
            if (file.length() != upload.sizeBytes) {
                throw FileChangedException("${upload.relativePath} changed size during upload")
            }
            val offset = (partNumber - 1) * state.partSizeBytes
            val length = minOf(state.partSizeBytes, upload.sizeBytes - offset)
            val checksum = hasher.sha256Base64(file, offset, length)

            val onServer = serverParts[partNumber]
            val etag = if (onServer?.etag != null && onServer.checksumSha256 == checksum && onServer.sizeBytes == length) {
                onServer.etag
            } else {
                uploadPart(upload, file, state, partNumber, offset, length, checksum)
            }
            state = state.withPart(MultipartState.Part(partNumber, etag, checksum, length))
            repository.saveMultipart(upload.id, state)
            CloudLog.i(
                "part_uploaded", "session" to upload.sessionId, "path" to upload.relativePath,
                "part" to "$partNumber/${state.partCount}",
            )
        }

        api.completeMultipartUpload(
            upload.sessionId,
            upload.relativePath,
            state.uploadId,
            state.parts.map { CompletedPart(it.partNumber, it.etag, it.checksumSha256) },
        )
    }

    private suspend fun uploadPart(
        upload: UploadEntity,
        file: File,
        state: MultipartState,
        partNumber: Int,
        offset: Long,
        length: Long,
        checksum: String,
    ): String {
        // Presigned URLs are short-lived, so one is requested right before each part is sent.
        val presigned = api.getMultipartPartUrls(
            upload.sessionId,
            upload.relativePath,
            state.uploadId,
            listOf(PartUrlRequest(partNumber, length, checksum)),
        ).firstOrNull { it.partNumber == partNumber }
            ?: throw CloudHttpException(502, "MALFORMED_RESPONSE", "no URL returned for part $partNumber")
        val result = transport.put(presigned.url, presigned.headers, file, offset, length)
        return result.etag
            ?: throw CloudHttpException(502, "MISSING_ETAG", "S3 did not return an ETag for part $partNumber")
    }
}
