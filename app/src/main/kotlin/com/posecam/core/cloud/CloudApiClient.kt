package com.posecam.core.cloud

import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.posecam.core.sync.UploadThreads
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp implementation of [CloudApi]. Adds `Authorization: Bearer <token>` only when the
 * [AuthProvider] returns a token, and `X-Device-Id` (the installation id, for observability).
 */
class CloudApiClient(
    private val config: CloudConfig,
    private val authProvider: AuthProvider,
    private val installationId: String,
    private val client: OkHttpClient = defaultClient(config),
) : CloudApi {
    private val baseUrl: HttpUrl = config.normalizedBaseUrl.toHttpUrl()

    override suspend fun listPipes(): List<PipeInfo> = parsing {
        val json = get(listOf("v1", "pipes"))
        json.optJSONArray("pipes").toObjects().map {
            PipeInfo(
                id = it.requireString("id"),
                label = it.requireString("label"),
                color = it.optStringOrNull("color"),
                order = it.optInt("order", Int.MAX_VALUE),
            )
        }
    }

    override suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse = parsing {
        val body = JSONObject()
            .put("sessionId", request.sessionId)
            .put("deviceId", request.deviceId)
            .put("createdAt", request.createdAt)
            .put("recordingStatus", request.recordingStatus)
            .put("appVersion", request.appVersion ?: JSONObject.NULL)
            .apply { request.pipe?.let { put("pipe", it) } }
        val json = post(listOf("v1", "sessions"), body)
        CreateSessionResponse(
            session = json.requireObject("session").toSessionView(),
            created = json.optBoolean("created", false),
            config = json.optJSONObject("config")?.let {
                UploadConfig(
                    multipartThresholdBytes = it.optLong("multipartThresholdBytes"),
                    partSizeBytes = it.optLong("partSizeBytes"),
                    presignedUrlTtlSeconds = it.optLong("presignedUrlTtlSeconds"),
                )
            },
        )
    }

    override suspend fun requestUpload(sessionId: String, request: UploadRequest): PresignedUpload = parsing {
        val json = post(uploadPath(sessionId, "presign"), request.toJson())
        PresignedUpload(
            relativePath = json.optString("relativePath", request.relativePath),
            mode = runCatching { UploadMode.valueOf(json.getString("mode")) }
                .getOrElse { throw malformed("unknown upload mode") },
            url = json.optStringOrNull("url"),
            headers = json.optJSONObject("headers").toStringMap(),
            expiresInSeconds = json.optLong("expiresInSeconds"),
            partSizeBytes = if (json.isNull("partSizeBytes")) null else json.optLong("partSizeBytes"),
        )
    }

    override suspend fun startMultipartUpload(sessionId: String, request: UploadRequest): MultipartStart = parsing {
        val json = post(uploadPath(sessionId, "multipart/start"), request.toJson())
        val parts = json.optJSONArray("uploadedParts").toObjects().map {
            MultipartPartInfo(
                partNumber = it.getInt("partNumber"),
                sizeBytes = it.optLong("sizeBytes"),
                etag = it.optStringOrNull("etag"),
                checksumSha256 = it.optStringOrNull("checksumSha256"),
            )
        }
        MultipartStart(
            relativePath = json.optString("relativePath", request.relativePath),
            uploadId = json.requireString("uploadId"),
            partSizeBytes = json.getLong("partSizeBytes"),
            partCount = json.getInt("partCount"),
            resumed = json.optBoolean("resumed", false),
            uploadedParts = parts,
        )
    }

    override suspend fun getMultipartPartUrls(
        sessionId: String,
        relativePath: String,
        uploadId: String,
        parts: List<PartUrlRequest>,
    ): List<PresignedPart> = parsing {
        val body = JSONObject()
            .put("relativePath", relativePath)
            .put("uploadId", uploadId)
            .put(
                "parts",
                JSONArray().also { array ->
                    parts.forEach {
                        array.put(
                            JSONObject()
                                .put("partNumber", it.partNumber)
                                .put("sizeBytes", it.sizeBytes)
                                .put("checksumSha256", it.checksumSha256),
                        )
                    }
                },
            )
        val json = post(uploadPath(sessionId, "multipart/parts"), body)
        json.optJSONArray("parts").toObjects().map {
            PresignedPart(
                partNumber = it.getInt("partNumber"),
                url = it.requireString("url"),
                headers = it.optJSONObject("headers").toStringMap(),
                expiresInSeconds = it.optLong("expiresInSeconds"),
            )
        }
    }

    override suspend fun completeMultipartUpload(
        sessionId: String,
        relativePath: String,
        uploadId: String,
        parts: List<CompletedPart>,
    ): UploadCompletion = parsing {
        val body = JSONObject()
            .put("relativePath", relativePath)
            .put("uploadId", uploadId)
            .put(
                "parts",
                JSONArray().also { array ->
                    parts.forEach {
                        array.put(
                            JSONObject()
                                .put("partNumber", it.partNumber)
                                .put("etag", it.etag)
                                .put("checksumSha256", it.checksumSha256),
                        )
                    }
                },
            )
        val json = post(uploadPath(sessionId, "multipart/complete"), body)
        UploadCompletion(
            relativePath = json.optString("relativePath", relativePath),
            state = json.optString("state"),
            sizeBytes = json.optLong("sizeBytes"),
        )
    }

    override suspend fun verifyUpload(sessionId: String, relativePath: String): VerifiedUpload = parsing {
        val json = post(uploadPath(sessionId, "verify"), JSONObject().put("relativePath", relativePath))
        VerifiedUpload(
            relativePath = json.optString("relativePath", relativePath),
            state = json.optString("state"),
            sizeBytes = json.optLong("sizeBytes"),
            verifiedAt = json.optStringOrNull("verifiedAt"),
        )
    }

    override suspend fun getSession(sessionId: String): CloudSessionView = parsing {
        get(listOf("v1", "sessions", sessionId)).requireObject("session").toSessionView()
    }

    override suspend fun completeSession(
        sessionId: String,
        recordingStatus: String,
        files: List<SessionFileRef>,
    ): CloudSessionView = parsing {
        val body = JSONObject()
            .put("recordingStatus", recordingStatus)
            .put(
                "files",
                JSONArray().also { array ->
                    files.forEach {
                        array.put(JSONObject().put("relativePath", it.relativePath).put("sizeBytes", it.sizeBytes))
                    }
                },
            )
        val json = post(listOf("v1", "sessions", sessionId, "complete"), body)
        json.requireObject("session").toSessionView()
    }

    private fun uploadPath(sessionId: String, tail: String) =
        listOf("v1", "sessions", sessionId, "uploads") + tail.split('/')

    private suspend fun post(path: List<String>, body: JSONObject): JSONObject =
        execute(requestFor(path).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build())

    private suspend fun get(path: List<String>): JSONObject = execute(requestFor(path).get().build())

    private suspend fun requestFor(path: List<String>): Request.Builder {
        val url = baseUrl.newBuilder().apply { path.forEach { addPathSegment(it) } }.build()
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Device-Id", installationId)
            .apply { authProvider.getToken()?.let { header("Authorization", "Bearer $it") } }
    }

    private suspend fun execute(request: Request): JSONObject {
        val response = try {
            client.newCall(request).await()
        } catch (error: IOException) {
            throw CloudNetworkException(error)
        }
        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (error: IOException) {
                throw CloudNetworkException(error)
            }
            if (!it.isSuccessful) throw parseError(it.code, text)
            return try {
                JSONObject(text)
            } catch (error: JSONException) {
                throw malformed("response is not JSON")
            }
        }
    }

    private fun parseError(status: Int, text: String): CloudHttpException {
        val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
        return CloudHttpException(
            status = status,
            code = error?.optStringOrNull("code"),
            message = error?.optStringOrNull("message") ?: "request failed",
            details = error?.optJSONObject("details")?.toString(),
        )
    }

    /** A response with an unexpected shape is a backend problem, never a crash in the worker. */
    private inline fun <T> parsing(block: () -> T): T = try {
        block()
    } catch (error: JSONException) {
        throw malformed("unexpected response shape: ${error.message}")
    }

    private fun malformed(what: String) = CloudHttpException(502, "MALFORMED_RESPONSE", what)

    private fun UploadRequest.toJson() = JSONObject()
        .put("relativePath", relativePath)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)

    private fun JSONObject.toSessionView() = CloudSessionView(
        sessionId = getString("sessionId"),
        deviceId = optStringOrNull("deviceId"),
        createdAt = optStringOrNull("createdAt"),
        recordingStatus = optStringOrNull("recordingStatus"),
        cloudStatus = optString("cloudStatus"),
        totalFiles = optLong("totalFiles"),
        verifiedFiles = optLong("verifiedFiles"),
        totalBytes = optLong("totalBytes"),
        verifiedBytes = optLong("verifiedBytes"),
        completedAt = optStringOrNull("completedAt"),
        publishStatus = optStringOrNull("publishStatus"),
        publishedSets = optInt("publishedSets", 0),
        publishedAt = optStringOrNull("publishedAt"),
        publishError = optStringOrNull("publishError"),
    )

    private fun JSONObject.requireObject(name: String): JSONObject =
        optJSONObject(name) ?: throw malformed("missing $name")

    private fun JSONObject.requireString(name: String): String =
        optStringOrNull(name) ?: throw malformed("missing $name")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Control-plane calls are small, so timeouts are short; bulk transfers use [S3Transport]'s own client. */
        fun defaultClient(config: CloudConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.apiConnectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.apiReadTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.apiWriteTimeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Background-priority threads: control-plane calls must never compete with recording.
            .dispatcher(Dispatcher(UploadThreads.executor))
            .build()
    }
}

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { optString(it) }
}

private fun JSONArray?.toObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
