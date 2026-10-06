package com.posecam.core.sync

import com.posecam.core.cloud.CloudConfig
import com.posecam.core.cloud.CloudHttpException
import com.posecam.core.cloud.CloudNetworkException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

class OkHttpS3TransportTest {
    private lateinit var server: MockWebServer
    private val transport = OkHttpS3Transport(CloudConfig(baseUrl = "http://unused/"))
    private val file: File = createTempFile().toFile().also { it.writeText("0123456789") }

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun url() = server.url("/bucket/key?X-Amz-Signature=secret").toString()

    @Test
    fun putsExactlyTheRequestedByteRangeWithTheSignedHeadersAndNoAuthorization() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"abc\""))
        val progress = mutableListOf<Long>()

        val result = transport.put(url(), mapOf("Content-Type" to "application/octet-stream", "x-amz-checksum-sha256" to "zz="), file, offset = 2, length = 5) { progress += it }

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("23456", request.body.readUtf8())
        assertEquals("zz=", request.getHeader("x-amz-checksum-sha256"))
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
        assertEquals("5", request.getHeader("Content-Length"))
        // An Authorization header would invalidate a presigned URL.
        assertNull(request.getHeader("Authorization"))
        assertEquals("\"abc\"", result.etag)
        assertEquals(5L, progress.last())
    }

    @Test
    fun mapsS3ErrorCodesAndTheirRetryability() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>AccessDenied</Code></Error>"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchUpload</Code></Error>"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("<Error><Code>BadDigest</Code></Error>"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("<Error><Code>EntityTooSmall</Code></Error>"))

        val denied = assertThrows(CloudHttpException::class.java) { runBlocking { transport.put(url(), emptyMap(), file, 0, 10) } }
        assertEquals("AccessDenied", denied.code)
        assertTrue(denied.retryable) // an expired URL is fixed by asking for a new one

        val gone = assertThrows(CloudHttpException::class.java) { runBlocking { transport.put(url(), emptyMap(), file, 0, 10) } }
        assertEquals(CloudHttpException.S3_NO_SUCH_UPLOAD, gone.code)

        assertTrue(assertThrows(CloudHttpException::class.java) { runBlocking { transport.put(url(), emptyMap(), file, 0, 10) } }.retryable)
        assertFalse(assertThrows(CloudHttpException::class.java) { runBlocking { transport.put(url(), emptyMap(), file, 0, 10) } }.retryable)
    }

    @Test
    fun noResponseIsANetworkException() {
        val target = url()
        server.shutdown()

        assertThrows(CloudNetworkException::class.java) { runBlocking { transport.put(target, emptyMap(), file, 0, 10) } }
    }

    @Test
    fun theSecretUrlNeverAppearsInAnExceptionMessage() {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { transport.put(url(), emptyMap(), file, 0, 10) } }

        assertFalse(error.message!!.contains("X-Amz-Signature"))
    }
}

class UploadThrottleTest {
    private val server = MockWebServer()
    private val file: File = createTempFile().toFile().also { it.writeBytes(ByteArray(300_000) { 1 }) }

    @Test
    fun theThrottleSpreadsBytesOutOverTime() = runBlocking {
        server.start()
        server.enqueue(MockResponse().setResponseCode(200))
        val transport = OkHttpS3Transport(CloudConfig(baseUrl = "http://unused/"), throttle = UploadThrottle { 400_000L })

        val started = System.nanoTime()
        transport.put(server.url("/x").toString(), emptyMap(), file, 0, 300_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // 300,000 bytes at 400,000 B/s is 750 ms; allow slack for scheduling.
        assertTrue("took only ${elapsedMs}ms", elapsedMs >= 600)
        server.shutdown()
    }

    @Test
    fun noThrottleMeansFullSpeed() {
        // PoseCam never uploads while recording, so there is no recording-time cap: the default is unlimited.
        assertNull(UploadThrottle.None.maxBytesPerSecond())
    }
}
