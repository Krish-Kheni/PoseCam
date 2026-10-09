package com.posecam.core.sync

import com.posecam.core.cloud.CloudApi
import com.posecam.core.cloud.CloudException
import com.posecam.core.cloud.CloudHttpException
import com.posecam.core.cloud.CloudNetworkException
import com.posecam.core.cloud.CloudSessionView
import com.posecam.core.cloud.CompletedPart
import com.posecam.core.cloud.CreateSessionRequest
import com.posecam.core.cloud.CreateSessionResponse
import com.posecam.core.cloud.MultipartPartInfo
import com.posecam.core.cloud.MultipartStart
import com.posecam.core.cloud.PartUrlRequest
import com.posecam.core.cloud.PipeInfo
import com.posecam.core.cloud.PresignedPart
import com.posecam.core.cloud.PresignedUpload
import com.posecam.core.cloud.SessionFileRef
import com.posecam.core.cloud.UploadCompletion
import com.posecam.core.cloud.UploadMode
import com.posecam.core.cloud.UploadRequest
import com.posecam.core.cloud.VerifiedUpload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

/** In-memory [UploadDao] that mirrors the SQL semantics of the Room queries. */
class FakeUploadDao : UploadDao {
    private val uploads = linkedMapOf<Long, UploadEntity>()
    private val sessions = linkedMapOf<String, CloudSessionEntity>()
    private var nextId = 1L
    private val changes = MutableStateFlow(0)

    private fun changed() { changes.value++ }

    /** Makes the next insert throw, to model a database failure while finalizing. */
    var failNextInsert = false

    override suspend fun insertUploadIgnore(upload: UploadEntity): Long {
        if (failNextInsert) { failNextInsert = false; throw IllegalStateException("database is down") }
        if (uploads.values.any { it.sessionId == upload.sessionId && it.relativePath == upload.relativePath }) return -1
        val id = nextId++
        uploads[id] = upload.copy(id = id)
        changed()
        return id
    }

    override suspend fun updateUpload(upload: UploadEntity) { uploads[upload.id] = upload; changed() }
    override suspend fun getUpload(id: Long) = uploads[id]
    override suspend fun findUpload(sessionId: String, relativePath: String) =
        uploads.values.firstOrNull { it.sessionId == sessionId && it.relativePath == relativePath }
    override suspend fun uploadsForSession(sessionId: String) =
        uploads.values.filter { it.sessionId == sessionId }.sortedBy { it.relativePath }

    override suspend fun runnableUploads(): List<UploadEntity> = uploads.values
        .filter { u ->
            u.state.isRunnable && sessions[u.sessionId]?.let { it.cloudCreated && !it.permanentFailure } == true
        }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override suspend fun minRunnableCreatedAt(): Long? = uploads.values.filter { it.state.isRunnable }.minOfOrNull { it.createdAt }

    override suspend fun setRunnableCreatedAt(sessionId: String, createdAt: Long) {
        uploads.values.filter { it.sessionId == sessionId && it.state.isRunnable }
            .forEach { uploads[it.id] = it.copy(createdAt = createdAt) }
        changed()
    }

    override suspend fun failedUploads(sessionId: String?) =
        uploads.values.filter { it.state == UploadState.FAILED && (sessionId == null || it.sessionId == sessionId) }

    override suspend fun resetPreparing(now: Long): Int {
        val rows = uploads.values.filter { it.state == UploadState.PREPARING }
        rows.forEach { uploads[it.id] = it.copy(state = UploadState.PENDING, updatedAt = now) }
        changed()
        return rows.size
    }

    override suspend fun deleteUpload(id: Long) {
        uploads.remove(id)
        changed()
    }

    override suspend fun deleteUploadsForSession(sessionId: String) {
        uploads.values.removeAll { it.sessionId == sessionId }
        changed()
    }

    override fun observeAggregates(): Flow<List<SessionUploadAggregate>> = changes.map { aggregates() }

    override fun observeExportAggregates(): Flow<List<SessionExportAggregate>> = changes.map { exportAggregates() }

    fun exportAggregates(): List<SessionExportAggregate> = uploads.values.filter { !it.required }.groupBy { it.sessionId }.map { (id, rows) ->
        SessionExportAggregate(id, rows.size, rows.count { it.state == UploadState.VERIFIED }, rows.count { it.state == UploadState.FAILED })
    }

    fun aggregates(): List<SessionUploadAggregate> = uploads.values.filter { it.required }.groupBy { it.sessionId }.map { (id, rows) ->
        SessionUploadAggregate(
            sessionId = id,
            totalFiles = rows.size,
            verifiedFiles = rows.count { it.state == UploadState.VERIFIED },
            failedFiles = rows.count { it.state == UploadState.FAILED },
            activeFiles = rows.count { it.state in setOf(UploadState.PREPARING, UploadState.UPLOADING, UploadState.UPLOADED) },
            awaitingVerification = rows.count { it.state == UploadState.UPLOADED },
            totalBytes = rows.sumOf { it.sizeBytes },
            transferredBytes = rows.sumOf { if (it.state == UploadState.VERIFIED || it.state == UploadState.UPLOADED) it.sizeBytes else it.uploadedBytes },
            lastError = rows.mapNotNull { it.lastError }.maxOrNull(),
        )
    }

    /**
     * Test convenience: new sessions get this pipe unless a test sets it to null. Production sessions start with no
     * pipe (the collector chooses it); the pipe tests set this to null to exercise exactly that.
     */
    var defaultPipe: String? = "white"

    /**
     * Test convenience, like [defaultPipe]: new sessions start with this export state. Production sessions start PENDING
     * (their export is still to be made); the many tests that are about something else would otherwise have retention
     * waiting for an export that no test of theirs is making, so they get DONE and the export tests set PENDING.
     */
    var defaultExportState: ExportState = ExportState.DONE

    override suspend fun insertSessionIgnore(session: CloudSessionEntity): Long {
        if (session.sessionId in sessions) return -1
        sessions[session.sessionId] = session.copy(
            pipe = session.pipe ?: defaultPipe,
            exportState = if (session.exportState == ExportState.PENDING) defaultExportState else session.exportState,
        )
        changed()
        return 1
    }

    override suspend fun updateSession(session: CloudSessionEntity) { sessions[session.sessionId] = session; changed() }
    override suspend fun getSession(sessionId: String) = sessions[sessionId]
    override suspend fun allSessions() = sessions.values.toList()
    override fun observeSessions(): Flow<List<CloudSessionEntity>> = changes.map { sessions.values.toList() }
    override suspend fun sessionsNeedingCreation() =
        sessions.values.filter { !it.cloudCreated && !it.permanentFailure && it.pipe != null }

    override suspend fun sessionsNeedingExport() = sessions.values.filter {
        it.exportState == ExportState.PENDING && it.recordingFinal && !it.permanentFailure && it.pipe != null
    }

    override suspend fun sessionsReadyToComplete() = sessions.values.filter { s ->
        val required = uploads.values.filter { it.sessionId == s.sessionId && it.required }
        s.recordingFinal && s.cloudCreated && !s.permanentFailure && s.syncedAt == null &&
            required.isNotEmpty() && required.all { it.state == UploadState.VERIFIED }
    }

    override suspend fun deleteSession(sessionId: String) { sessions.remove(sessionId); changed() }
}

/** Scriptable [CloudApi]: records calls, can fail on demand, and models a backend that stores state. */
class FakeCloudApi : CloudApi {
    val calls = mutableListOf<String>()
    private val failures = mutableListOf<Pair<String, CloudException>>()

    var partSizeBytes = 4L
    var multipartThreshold = 10L
    var uploadedPartsOnServer: Map<Int, MultipartPartInfo> = emptyMap()
    var verifyBehavior: (String) -> Unit = {}
    val createdSessions = mutableSetOf<String>()
    val completedSessions = mutableListOf<List<SessionFileRef>>()
    val completedStatuses = mutableListOf<String>()
    val createdStatuses = mutableMapOf<String, String>()
    val createdAt = mutableMapOf<String, String>()
    val createdPipes = mutableMapOf<String, String?>()
    val verified = mutableListOf<String>()
    var multipartCompletedParts: List<CompletedPart>? = null
    val partUrlRequests = mutableListOf<Int>()
    var nextUploadId = "upload-1"
    var alreadyVerified = setOf<String>()

    private val pathFailures = mutableListOf<Triple<String, String, CloudException>>()

    fun failOnce(method: String, error: CloudException) { failures += method to error }

    /** Fails the next [method] call made for exactly [path] (a relativePath or session id), and no other. */
    fun failOnceFor(method: String, path: String, error: CloudException) { pathFailures += Triple(method, path, error) }
    fun failTimes(method: String, error: CloudException, times: Int) { repeat(times) { failures += method to error } }

    private fun record(method: String, detail: String = "") {
        calls += if (detail.isEmpty()) method else "$method:$detail"
        val pathIndex = pathFailures.indexOfFirst { it.first == method && it.second == detail }
        if (pathIndex >= 0) throw pathFailures.removeAt(pathIndex).third
        val index = failures.indexOfFirst { it.first == method }
        if (index >= 0) throw failures.removeAt(index).second
    }

    fun callsTo(method: String) = calls.count { it == method || it.startsWith("$method:") }

    /** What GET /v1/pipes returns; tests change it to model the backend gaining or losing a pipe. */
    var pipesOnServer: List<PipeInfo> = listOf(PipeInfo("white", "White pipes", "#f8fafc", 1))

    override suspend fun listPipes(): List<PipeInfo> {
        record("listPipes")
        return pipesOnServer
    }

    override suspend fun createSession(request: CreateSessionRequest): CreateSessionResponse {
        record("createSession", request.sessionId)
        createdStatuses[request.sessionId] = request.recordingStatus
        createdPipes[request.sessionId] = request.pipe
        createdAt[request.sessionId] = request.createdAt
        val created = createdSessions.add(request.sessionId)
        return CreateSessionResponse(view(request.sessionId), created, null)
    }

    override suspend fun requestUpload(sessionId: String, request: UploadRequest): PresignedUpload {
        record("requestUpload", request.relativePath)
        return when {
            request.relativePath in alreadyVerified ->
                PresignedUpload(request.relativePath, UploadMode.ALREADY_VERIFIED, null, emptyMap(), 0, null)
            request.sizeBytes >= multipartThreshold ->
                PresignedUpload(request.relativePath, UploadMode.MULTIPART, null, emptyMap(), 900, partSizeBytes)
            else ->
                PresignedUpload(request.relativePath, UploadMode.SINGLE, "https://s3.example/put/${request.relativePath}", mapOf("x-amz-checksum-sha256" to request.sha256), 900, null)
        }
    }

    override suspend fun startMultipartUpload(sessionId: String, request: UploadRequest): MultipartStart {
        record("startMultipart", request.relativePath)
        val count = ((request.sizeBytes + partSizeBytes - 1) / partSizeBytes).toInt()
        return MultipartStart(request.relativePath, nextUploadId, partSizeBytes, count, uploadedPartsOnServer.isNotEmpty(), uploadedPartsOnServer.values.toList())
    }

    override suspend fun getMultipartPartUrls(sessionId: String, relativePath: String, uploadId: String, parts: List<PartUrlRequest>): List<PresignedPart> {
        record("partUrls", parts.joinToString(",") { it.partNumber.toString() })
        partUrlRequests += parts.map { it.partNumber }
        return parts.map { PresignedPart(it.partNumber, "https://s3.example/part/${it.partNumber}", mapOf("x-amz-checksum-sha256" to it.checksumSha256), 900) }
    }

    override suspend fun completeMultipartUpload(sessionId: String, relativePath: String, uploadId: String, parts: List<CompletedPart>): UploadCompletion {
        record("completeMultipart", relativePath)
        multipartCompletedParts = parts
        return UploadCompletion(relativePath, "UPLOADED", 0)
    }

    override suspend fun verifyUpload(sessionId: String, relativePath: String): VerifiedUpload {
        record("verify", relativePath)
        verifyBehavior(relativePath)
        verified += relativePath
        return VerifiedUpload(relativePath, "VERIFIED", 0, null)
    }

    /** What GET /v1/sessions/{id} reports as the publish verdict, per session; a session not listed is "pending". */
    val publishOnServer = mutableMapOf<String, CloudSessionView>()

    override suspend fun getSession(sessionId: String): CloudSessionView {
        record("getSession", sessionId)
        return publishOnServer[sessionId] ?: view(sessionId).copy(publishStatus = "pending")
    }

    override suspend fun completeSession(sessionId: String, recordingStatus: String, files: List<SessionFileRef>): CloudSessionView {
        record("completeSession", sessionId)
        completedSessions += files
        completedStatuses += recordingStatus
        return view(sessionId)
    }

    private fun view(id: String) = CloudSessionView(id, "device", null, null, "CREATED", 0, 0, 0, 0, null)
}

/** Records the bytes ranges "sent" instead of touching a network. */
class FakeS3Transport : S3Transport {
    data class Put(val url: String, val headers: Map<String, String>, val offset: Long, val length: Long, val content: ByteArray)

    val puts = mutableListOf<Put>()
    private val failures = mutableListOf<CloudException>()
    var etagFor: (Int) -> String? = { "\"etag-$it\"" }
    private var partCounter = 0

    fun failNext(error: CloudException) { failures += error }

    override suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, onProgress: (Long) -> Unit): S3PutResult {
        if (failures.isNotEmpty()) throw failures.removeAt(0)
        val bytes = file.readBytes().copyOfRange(offset.toInt(), (offset + length).toInt())
        puts += Put(url, headers, offset, length, bytes)
        val partNumber = Regex("part/(\\d+)").find(url)?.groupValues?.get(1)?.toInt() ?: ++partCounter
        return S3PutResult(etagFor(partNumber))
    }
}

class FakeScheduler : UploadScheduler {
    var scheduled = 0
    var syncNowCalls = 0
    val syncNowSessions = mutableListOf<String?>()
    var rescheduled = 0
    override suspend fun schedule() { scheduled++ }
    override suspend fun syncNow(sessionId: String?) { syncNowCalls++; syncNowSessions += sessionId }
    override suspend fun reschedule() { rescheduled++ }
}

val NETWORK_DOWN = CloudNetworkException(IOException("Unable to resolve host"))
fun http(status: Int, code: String? = null, details: String? = null) = CloudHttpException(status, code, "boom", details)
