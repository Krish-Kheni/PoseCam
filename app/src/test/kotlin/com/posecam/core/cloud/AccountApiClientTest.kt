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

class AccountApiClientTest {
    private lateinit var server: MockWebServer
    private var unauthorized = 0

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(token: String? = null) = AccountApiClient(
        CloudConfig(baseUrl = server.url("/").toString().trimEnd('/')),
        object : AuthProvider {
            override suspend fun getToken() = token
            override fun onUnauthorized() { unauthorized++ }
        },
        "install-1",
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private val authBody = """{"token":"jwt.token.sig","expiresAt":"2027-01-07T10:15:30.250Z","user":{"id":"a@b.co","email":"a@b.co","name":"Asha"}}"""

    @Test
    fun registerPostsCredentialsAndReturnsTheSession() = runBlocking {
        server.enqueue(json(authBody, 201))

        val session = client().register("a@b.co", "hunter2hunter2", "Asha")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/register", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("a@b.co", body.getString("email"))
        assertEquals("hunter2hunter2", body.getString("password"))
        assertEquals("Asha", body.getString("name"))
        assertEquals("jwt.token.sig", session.token)
        assertEquals("Asha", session.name)
        assertEquals(1_799_316_930_000L, session.expiresAtMs) // 2027-01-07T10:15:30Z; the fraction is dropped
    }

    @Test
    fun aBlankNameIsNotSent() = runBlocking {
        server.enqueue(json(authBody, 201))

        client().register("a@b.co", "hunter2hunter2", "  ")

        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("name"))
    }

    @Test
    fun loginSurfacesTheServersReasonForAWrongPassword() {
        server.enqueue(json("""{"error":{"code":"INVALID_CREDENTIALS","message":"Wrong email or password"}}""", 401))

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { client().login("a@b.co", "nope") } }

        assertEquals("INVALID_CREDENTIALS", error.code)
        assertEquals("Wrong email or password", AuthValidation.messageFor(error))
    }

    @Test
    fun aTakenEmailIsExplainedToTheCollector() {
        server.enqueue(json("""{"error":{"code":"EMAIL_TAKEN","message":"An account with this email already exists"}}""", 409))

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { client().register("a@b.co", "hunter2hunter2", null) } }

        assertEquals("An account with this email already exists", AuthValidation.messageFor(error))
    }

    @Test
    fun statsAreReadWithTheTokenAndThePhonesTimeZone() = runBlocking {
        server.enqueue(
            json(
                """{"email":"a@b.co","totalRecordings":12,"uploadedToday":3,"firstUploadAt":"2026-09-01T05:00:00.000Z",
                    "days":[{"date":"2026-10-09","recordings":3,"pipes":{"white":2,"pink":1},"live":2,"publishing":1,"failed":0},
                            {"date":"2026-10-08","recordings":9,"pipes":{"white":9},"live":9,"publishing":0,"failed":0}]}""",
            ),
        )

        val stats = client(token = "T").myStats(330)

        val request = server.takeRequest()
        assertEquals("/v1/me/stats?tzOffsetMinutes=330", request.path)
        assertEquals("Bearer T", request.getHeader("Authorization"))
        assertEquals(12, stats.totalRecordings)
        assertEquals(3, stats.uploadedToday)
        assertEquals(
            listOf(
                DayCount("2026-10-09", 3, mapOf("white" to 2, "pink" to 1), live = 2, publishing = 1),
                DayCount("2026-10-08", 9, mapOf("white" to 9), live = 9),
            ),
            stats.days,
        )
    }

    @Test
    fun oneDaysRecordingsAreAskedForByDateAndTimeZone() = runBlocking {
        server.enqueue(
            json(
                """{"date":"2026-10-09","total":2,"recordings":[
                    {"sessionId":"capture-20261009T154304-60c403","pipe":"white","pipeLabel":"White pipes","uploadedAt":"2026-10-09T10:00:00.000Z","date":"2026-10-09","status":"live"},
                    {"sessionId":"capture-20261009T101500-aaaaaa","pipe":"pink","pipeLabel":"Pink Pipes","uploadedAt":"2026-10-09T09:00:00.000Z","date":"2026-10-09","status":"publish_failed"}]}""",
            ),
        )

        val list = client(token = "T").uploadsOn("2026-10-09", 330)

        val request = server.takeRequest()
        assertEquals("/v1/me/uploads?date=2026-10-09&tzOffsetMinutes=330", request.path)
        assertEquals("Bearer T", request.getHeader("Authorization"))
        assertEquals(listOf("white", "pink"), list.map { it.pipe })
        assertEquals("publish_failed", list[1].status)
    }

    @Test
    fun aRefusedTokenIsForgotten() {
        server.enqueue(json("""{"error":{"code":"UNAUTHENTICATED","message":"Sign in again"}}""", 401))

        assertThrows(CloudHttpException::class.java) { runBlocking { client(token = "old").myStats(0) } }

        assertEquals(1, unauthorized)
    }

    @Test
    fun theUploadApiForgetsARefusedTokenToo() {
        server.enqueue(json("""{"error":{"code":"UNAUTHENTICATED","message":"Sign in again"}}""", 401))
        var forgotten = 0
        val api = CloudApiClient(
            CloudConfig(baseUrl = server.url("/").toString().trimEnd('/')),
            object : AuthProvider {
                override suspend fun getToken() = "old"
                override fun onUnauthorized() { forgotten++ }
            },
            "install-1",
        )

        assertThrows(CloudHttpException::class.java) { runBlocking { api.getSession("s1") } }

        assertEquals(1, forgotten)
    }

    @Test
    fun noConnectionReadsAsOne() {
        server.shutdown()

        val error = assertThrows(CloudNetworkException::class.java) { runBlocking { client().login("a@b.co", "x") } }

        assertTrue(AuthValidation.messageFor(error).contains("internet"))
    }
}
