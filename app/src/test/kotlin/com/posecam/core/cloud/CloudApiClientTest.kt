package com.posecam.core.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudApiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(auth: AuthProvider = NoAuthProvider()) =
        CloudApiClient(CloudConfig(baseUrl = server.url("/").toString().trimEnd('/')), auth, "install-123")

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private val sessionJson = """{"sessionId":"s1","deviceId":"d","createdAt":"2026-01-01T00:00:00Z","recordingStatus":"recording","cloudStatus":"CREATED","totalFiles":0,"verifiedFiles":0,"totalBytes":0,"verifiedBytes":0,"completedAt":null}"""

    @Test
    fun createSessionPostsTheV1ContractAndSendsNoAuthorizationWithoutAToken() = runBlocking {
        server.enqueue(json("""{"session":$sessionJson,"created":true,"config":{"multipartThresholdBytes":104857600,"partSizeBytes":16777216,"presignedUrlTtlSeconds":900}}"""))

        val response = client().createSession(CreateSessionRequest("s1", "install-123", "2026-01-01T00:00:00Z", "recording", "0.1.0"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("install-123", request.getHeader("X-Device-Id"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("s1", body.getString("sessionId"))
        assertEquals("install-123", body.getString("deviceId"))
        assertEquals("recording", body.getString("recordingStatus"))
        assertTrue(response.created)
        assertEquals(16_777_216L, response.config!!.partSizeBytes)
    }

    @Test
    fun getSessionReadsWhetherTheRecordingReachedTheWebsite() = runBlocking {
        server.enqueue(json("""{"session":${sessionJson.dropLast(1)},"publishStatus":"done","publishedAt":"2026-10-09T10:00:00Z","publishedSets":2,"publishError":null},"files":[]}"""))

        val session = client().getSession("s1")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/sessions/s1", request.path)
        assertEquals("done", session.publishStatus)
        assertEquals(2, session.publishedSets)
        assertEquals("2026-10-09T10:00:00Z", session.publishedAt)
        assertNull(session.publishError)
    }

    @Test
    fun aBackendThatDoesNotReportPublishingGivesANullStatus() = runBlocking {
        server.enqueue(json("""{"session":$sessionJson,"files":[]}"""))

        val session = client().getSession("s1")

        assertNull(session.publishStatus)
        assertEquals(0, session.publishedSets)
    }

    @Test
    fun listPipesGetsTheV1PipesPathAndReadsTheList() = runBlocking {
        server.enqueue(json("""{"pipes":[{"id":"white","label":"White pipes","color":"#f8fafc","order":1},{"id":"pink","label":"Pink Pipes","color":null,"order":4}]}"""))

        val pipes = client().listPipes()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/pipes", request.path)
        assertEquals("install-123", request.getHeader("X-Device-Id"))
        assertEquals(listOf("white", "pink"), pipes.map { it.id })
        assertEquals("#f8fafc", pipes[0].color)
        assertNull(pipes[1].color)
        assertEquals(4, pipes[1].order)
    }

    @Test
    fun createSessionSendsThePipeWhenChosenAndOmitsItOtherwise() = runBlocking {
        repeat(2) { server.enqueue(json("""{"session":$sessionJson,"created":true}""")) }

        client().createSession(CreateSessionRequest("s1", "d", "2026-01-01T00:00:00Z", "complete", "0.3.0", pipe = "black"))
        client().createSession(CreateSessionRequest("s1", "d", "2026-01-01T00:00:00Z", "complete", "0.3.0"))

        assertEquals("black", JSONObject(server.takeRequest().body.readUtf8()).getString("pipe"))
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("pipe"))
    }

    @Test
    fun addsABearerTokenOnlyWhenTheAuthProviderReturnsOne() = runBlocking {
        server.enqueue(json("""{"session":$sessionJson,"created":false}"""))
        server.enqueue(json("""{"session":$sessionJson,"created":false}"""))
        val request = CreateSessionRequest("s1", "d", "t", "recording", null)

        client(auth = object : AuthProvider { override suspend fun getToken() = "tok-abc" }).createSession(request)
        client(auth = object : AuthProvider { override suspend fun getToken(): String? = null }).createSession(request)

        assertEquals("Bearer tok-abc", server.takeRequest().getHeader("Authorization"))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun parsesSingleAndMultipartPresignResponses() = runBlocking {
        server.enqueue(json("""{"relativePath":"manifest.json","mode":"SINGLE","url":"https://s3.example/x?sig=1","method":"PUT","headers":{"Content-Type":"application/json","x-amz-checksum-sha256":"abc="},"expiresInSeconds":900,"partSizeBytes":null}"""))
        server.enqueue(json("""{"relativePath":"segment-00001.rjmj","mode":"MULTIPART","url":null,"method":"PUT","headers":{},"expiresInSeconds":900,"partSizeBytes":16777216}"""))
        val api = client()

        val single = api.requestUpload("s1", UploadRequest("manifest.json", 10, "ab".repeat(32)))
        val multi = api.requestUpload("s1", UploadRequest("segment-00001.rjmj", 200_000_000, "cd".repeat(32)))

        assertEquals(UploadMode.SINGLE, single.mode)
        assertEquals("https://s3.example/x?sig=1", single.url)
        assertEquals("abc=", single.headers["x-amz-checksum-sha256"])
        assertEquals(UploadMode.MULTIPART, multi.mode)
        assertNull(multi.url)
        assertEquals(16_777_216L, multi.partSizeBytes)
        val sent = server.takeRequest()
        assertEquals("/v1/sessions/s1/uploads/presign", sent.path)
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("manifest.json", body.getString("relativePath"))
        assertEquals(10L, body.getLong("sizeBytes"))
        assertEquals("ab".repeat(32), body.getString("sha256"))
        // Android never sends a bucket or an S3 key.
        assertFalse(body.has("bucket") || body.has("key") || body.has("s3Key"))
    }

    @Test
    fun multipartEndpointsUseTheContractPathsAndBodies() = runBlocking {
        server.enqueue(json("""{"relativePath":"p","uploadId":"U","partSizeBytes":4,"partCount":2,"resumed":true,"uploadedParts":[{"partNumber":1,"sizeBytes":4,"etag":"\"e\"","checksumSha256":"c="}]}"""))
        server.enqueue(json("""{"parts":[{"partNumber":2,"url":"https://s3/p2","headers":{"x-amz-checksum-sha256":"d="},"expiresInSeconds":900}]}"""))
        server.enqueue(json("""{"relativePath":"p","state":"UPLOADED","sizeBytes":8}"""))
        server.enqueue(json("""{"relativePath":"p","state":"VERIFIED","sizeBytes":8,"verifiedAt":"2026-01-01T00:00:00Z"}"""))
        server.enqueue(json("""{"session":$sessionJson}"""))
        val api = client()

        val start = api.startMultipartUpload("s1", UploadRequest("p", 8, "00".repeat(32)))
        val urls = api.getMultipartPartUrls("s1", "p", "U", listOf(PartUrlRequest(2, 4, "d=")))
        val done = api.completeMultipartUpload("s1", "p", "U", listOf(CompletedPart(1, "\"e\"", "c="), CompletedPart(2, "\"f\"", "d=")))
        val verified = api.verifyUpload("s1", "p")
        api.completeSession("s1", "complete", listOf(SessionFileRef("p", 8)))

        assertEquals("U", start.uploadId)
        assertTrue(start.resumed)
        assertEquals("\"e\"", start.uploadedParts.single().etag)
        assertEquals("https://s3/p2", urls.single().url)
        assertEquals("UPLOADED", done.state)
        assertEquals("VERIFIED", verified.state)
        assertEquals("/v1/sessions/s1/uploads/multipart/start", server.takeRequest().path)
        val partsReq = server.takeRequest()
        assertEquals("/v1/sessions/s1/uploads/multipart/parts", partsReq.path)
        assertEquals(2, JSONObject(partsReq.body.readUtf8()).getJSONArray("parts").getJSONObject(0).getInt("partNumber"))
        val completeReq = server.takeRequest()
        assertEquals("/v1/sessions/s1/uploads/multipart/complete", completeReq.path)
        assertEquals(2, JSONObject(completeReq.body.readUtf8()).getJSONArray("parts").length())
        assertEquals("/v1/sessions/s1/uploads/verify", server.takeRequest().path)
        val sessionReq = server.takeRequest()
        assertEquals("/v1/sessions/s1/complete", sessionReq.path)
        assertEquals("complete", JSONObject(sessionReq.body.readUtf8()).getString("recordingStatus"))
    }

    @Test
    fun mapsErrorBodiesToTypedExceptionsWithRetryability() = runBlocking {
        server.enqueue(json("""{"error":{"code":"VERIFICATION_FAILED","message":"size","retryable":false,"details":{"reason":"SIZE_MISMATCH"}}}""", 409))
        server.enqueue(json("""{"error":{"code":"INVALID_PATH","message":"bad","retryable":false}}""", 400))
        server.enqueue(json("upstream", 503))
        server.enqueue(json("{}", 429))
        val api = client()

        val verification = assertThrows(CloudHttpException::class.java) { runBlocking { api.verifyUpload("s1", "p") } }
        assertEquals(409, verification.status)
        assertEquals("VERIFICATION_FAILED", verification.code)
        assertTrue(verification.details!!.contains("SIZE_MISMATCH"))
        assertFalse(verification.retryable)

        assertFalse(assertThrows(CloudHttpException::class.java) { runBlocking { api.verifyUpload("s1", "p") } }.retryable)
        assertTrue(assertThrows(CloudHttpException::class.java) { runBlocking { api.verifyUpload("s1", "p") } }.retryable)
        assertTrue(assertThrows(CloudHttpException::class.java) { runBlocking { api.verifyUpload("s1", "p") } }.retryable)
    }

    @Test
    fun anUnreachableServerIsARetryableNetworkException() {
        val api = client()
        server.shutdown()

        val error = assertThrows(CloudNetworkException::class.java) {
            runBlocking { api.verifyUpload("s1", "p") }
        }
        assertTrue(error.retryable)
    }

    @Test
    fun aResponseWithTheWrongShapeIsAMalformedResponseNotACrash() {
        server.enqueue(json("""{"surprise":true}"""))

        val error = assertThrows(CloudHttpException::class.java) {
            runBlocking { client().startMultipartUpload("s1", UploadRequest("p", 8, "00".repeat(32))) }
        }
        assertEquals("MALFORMED_RESPONSE", error.code)
    }
}
