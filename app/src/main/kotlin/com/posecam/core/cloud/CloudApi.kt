package com.posecam.core.cloud

/**
 * The backend control plane. It only ever deals in small JSON: bulk bytes go straight to S3
 * through presigned URLs (see [com.posecam.core.sync.S3Transport]). Every method
 * throws [CloudException]; callers treat [CloudException.retryable] as "try again later".
 */
interface CloudApi {
    /** Idempotent: repeating it for the same session returns the existing record. */
    suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse

    /** Asks how to upload one file. The backend alone decides the S3 key and single-vs-multipart. */
    suspend fun requestUpload(sessionId: String, request: UploadRequest): PresignedUpload

    suspend fun startMultipartUpload(sessionId: String, request: UploadRequest): MultipartStart

    suspend fun getMultipartPartUrls(
        sessionId: String,
        relativePath: String,
        uploadId: String,
        parts: List<PartUrlRequest>,
    ): List<PresignedPart>

    suspend fun completeMultipartUpload(
        sessionId: String,
        relativePath: String,
        uploadId: String,
        parts: List<CompletedPart>,
    ): UploadCompletion

    /** The backend inspects the S3 object (size + checksum) before answering VERIFIED. */
    suspend fun verifyUpload(sessionId: String, relativePath: String): VerifiedUpload

    suspend fun completeSession(
        sessionId: String,
        recordingStatus: String,
        files: List<SessionFileRef>,
    ): CloudSessionView
}
