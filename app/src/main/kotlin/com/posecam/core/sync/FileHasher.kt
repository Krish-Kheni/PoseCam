package com.posecam.core.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** SHA-256 over files or file ranges. Never runs on the main/recording thread. */
class FileHasher(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun sha256Hex(file: File): String = withContext(dispatcher) {
        toHex(digest(file, 0, file.length()))
    }

    /** Base64 SHA-256 of `[offset, offset + length)`: the format S3's `x-amz-checksum-sha256` uses. */
    suspend fun sha256Base64(file: File, offset: Long, length: Long): String = withContext(dispatcher) {
        base64(digest(file, offset, length))
    }

    private fun digest(file: File, offset: Long, length: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(BUFFER_BYTES)
            var remaining = length
            while (remaining > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw java.io.EOFException("${file.name} is shorter than expected")
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest()
    }

    companion object {
        private const val BUFFER_BYTES = 128 * 1024

        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /**
         * Standard base64 with padding and no line breaks (RFC 4648), as S3 expects in `x-amz-checksum-sha256`.
         * Hand-written because `java.util.Base64` needs API 26 (minSdk is 24) and `android.util.Base64` is
         * stubbed in plain JVM tests.
         */
        fun base64(bytes: ByteArray): String = buildString((bytes.size + 2) / 3 * 4) {
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                append(ALPHABET[b0 shr 2])
                append(ALPHABET[(b0 and 0x03 shl 4) or (b1 shr 4)])
                append(if (i + 1 < bytes.size) ALPHABET[(b1 and 0x0F shl 2) or (b2 shr 6)] else '=')
                append(if (i + 2 < bytes.size) ALPHABET[b2 and 0x3F] else '=')
                i += 3
            }
        }
    }
}
