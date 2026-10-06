package com.posecam.core.sync

import com.posecam.core.cloud.CloudConfig
import com.posecam.core.cloud.CloudHttpException
import com.posecam.core.cloud.CloudNetworkException
import com.posecam.core.cloud.await
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

data class S3PutResult(val etag: String?)

/**
 * Sends bytes straight to S3 over a presigned URL. Throws [com.posecam.core.cloud.CloudException]:
 * [CloudNetworkException] when no response arrived, [CloudHttpException] for an S3 error status.
 */
interface S3Transport {
    suspend fun put(
        url: String,
        headers: Map<String, String>,
        file: File,
        offset: Long,
        length: Long,
        onProgress: (bytesSent: Long) -> Unit = {},
    ): S3PutResult
}

/**
 * OkHttp [S3Transport]. Uses its own client with NO auth interceptor: a presigned URL carries
 * its own signature, and an `Authorization` header would invalidate it. The URL is never logged.
 */
class OkHttpS3Transport(
    config: CloudConfig,
    private val client: OkHttpClient = defaultClient(config),
    private val throttle: UploadThrottle = UploadThrottle.None,
) : S3Transport {

    override suspend fun put(
        url: String,
        headers: Map<String, String>,
        file: File,
        offset: Long,
        length: Long,
        onProgress: (Long) -> Unit,
    ): S3PutResult {
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        val request = Request.Builder()
            .url(url)
            .apply {
                headers.forEach { (name, value) ->
                    // OkHttp derives these from the body; sending them twice would corrupt the request.
                    if (!name.equals("Content-Type", true) && !name.equals("Content-Length", true) && !name.equals("Host", true)) {
                        header(name, value)
                    }
                }
            }
            .put(FileRangeBody(file, offset, length, contentType?.let { it.toMediaTypeOrNull() }, onProgress, throttle))
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (error: IOException) {
            throw CloudNetworkException(error)
        }
        response.use {
            if (!it.isSuccessful) {
                val body = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                val s3Code = S3_ERROR_CODE.find(body)?.groupValues?.get(1)
                throw CloudHttpException(it.code, s3Code, "S3 rejected the upload")
            }
            return S3PutResult(etag = it.header("ETag"))
        }
    }

    private class FileRangeBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val mediaType: okhttp3.MediaType?,
        private val onProgress: (Long) -> Unit,
        private val throttle: UploadThrottle,
    ) : RequestBody() {
        override fun contentType() = mediaType

        override fun contentLength() = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(BUFFER_BYTES)
                var remaining = length
                var sent = 0L
                val startNs = System.nanoTime()
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("${file.name} shrank while uploading")
                    sink.write(buffer, 0, read)
                    remaining -= read
                    sent += read
                    onProgress(sent)
                    pace(sent, startNs)
                }
            }
        }

        /** While a recording is running, spread the bytes out so uploading never starves the camera pipeline. */
        private fun pace(sent: Long, startNs: Long) {
            val limit = throttle.maxBytesPerSecond() ?: return
            val dueMs = sent * 1000 / limit
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            if (dueMs > elapsedMs) Thread.sleep(dueMs - elapsedMs)
        }

        // A one-shot body: the file may legitimately be re-read on a retry, but never half-way.
        override fun isOneShot() = false
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private val S3_ERROR_CODE = Regex("<Code>([^<]+)</Code>")

        fun defaultClient(config: CloudConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.apiConnectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.transferTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.transferTimeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dispatcher(Dispatcher(UploadThreads.executor))
            .build()
    }
}
