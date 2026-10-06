package com.posecam.core.cloud

import java.io.IOException

/** Wire models for the v1 backend API. Field names mirror the JSON exactly (camelCase). */

data class UploadConfig(
    val multipartThresholdBytes: Long,
    val partSizeBytes: Long,
    val presignedUrlTtlSeconds: Long,
)

data class CloudSessionView(
    val sessionId: String,
    val deviceId: String?,
    val createdAt: String?,
    val recordingStatus: String?,
    val cloudStatus: String,
    val totalFiles: Long,
    val verifiedFiles: Long,
    val totalBytes: Long,
    val verifiedBytes: Long,
    val completedAt: String?,
)

data class CreateSessionRequest(
    val sessionId: String,
    val deviceId: String,
    val createdAt: String,
    val recordingStatus: String,
    val appVersion: String?,
    /** [com.posecam.core.sync.Pipe.wire]: which cloud folder the backend files this session under. */
    val pipe: String? = null,
)

data class CreateSessionResponse(
    val session: CloudSessionView,
    val created: Boolean,
    val config: UploadConfig?,
)

/** What [CloudApi.requestUpload] asks for: the backend picks S3 key, mode and limits itself. */
data class UploadRequest(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

enum class UploadMode { SINGLE, MULTIPART, ALREADY_VERIFIED }

data class PresignedUpload(
    val relativePath: String,
    val mode: UploadMode,
    /** Presigned URL. Treat as a secret: never log it. Null unless [mode] is SINGLE. */
    val url: String?,
    val headers: Map<String, String>,
    val expiresInSeconds: Long,
    val partSizeBytes: Long?,
)

data class MultipartPartInfo(
    val partNumber: Int,
    val sizeBytes: Long,
    val etag: String?,
    /** Base64 SHA-256 exactly as S3 reports it. */
    val checksumSha256: String?,
)

data class MultipartStart(
    val relativePath: String,
    val uploadId: String,
    val partSizeBytes: Long,
    val partCount: Int,
    val resumed: Boolean,
    val uploadedParts: List<MultipartPartInfo>,
)

data class PartUrlRequest(
    val partNumber: Int,
    val sizeBytes: Long,
    val checksumSha256: String,
)

data class PresignedPart(
    val partNumber: Int,
    val url: String,
    val headers: Map<String, String>,
    val expiresInSeconds: Long,
)

data class CompletedPart(
    val partNumber: Int,
    val etag: String,
    val checksumSha256: String,
)

data class UploadCompletion(val relativePath: String, val state: String, val sizeBytes: Long)

data class VerifiedUpload(val relativePath: String, val state: String, val sizeBytes: Long, val verifiedAt: String?)

data class SessionFileRef(val relativePath: String, val sizeBytes: Long)

/** Base type of every failure surfaced by the cloud layer. */
sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** True when trying again later (same request) can plausibly succeed. */
    abstract val retryable: Boolean
}

/** The request never produced an HTTP response: offline, DNS, TLS, timeout, connection reset. */
class CloudNetworkException(cause: IOException) :
    CloudException(cause.message ?: cause.javaClass.simpleName, cause) {
    override val retryable: Boolean get() = true
}

/** The backend (or S3) answered with an error status. */
class CloudHttpException(
    val status: Int,
    val code: String?,
    message: String,
    val details: String? = null,
) : CloudException("HTTP $status${code?.let { " $it" }.orEmpty()}: $message") {
    /**
     * Rate limiting, server errors and auth problems are all things a later attempt can fix
     * (auth providers refresh tokens; throttling and outages end). Other 4xx are the
     * client's own mistake and will fail identically every time.
     */
    override val retryable: Boolean
        get() = status == 408 || status == 425 || status == 429 || status == 401 || status == 403 ||
            status >= 500 || code in TRANSIENT_CODES

    companion object {
        /** S3 answers some transient conditions with a 400: a body corrupted in flight, a stalled upload. */
        private val TRANSIENT_CODES = setOf("BadDigest", "RequestTimeout", "SlowDown", "InternalError")

        const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
        const val UPLOAD_NOT_FOUND = "UPLOAD_NOT_FOUND"
        const val FILE_NOT_FOUND = "FILE_NOT_FOUND"
        const val UPLOAD_CONFLICT = "UPLOAD_CONFLICT"
        const val VERIFICATION_FAILED = "VERIFICATION_FAILED"
        const val SESSION_INCOMPLETE = "SESSION_INCOMPLETE"
        const val S3_NO_SUCH_UPLOAD = "NoSuchUpload"
    }
}
