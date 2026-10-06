package com.posecam.core.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Random

class FileHasherTest {
    @Test fun base64MatchesTheJdkForEveryLengthPaddingCase() {
        val random = Random(7)
        for (length in 0..70) {
            val bytes = ByteArray(length).also { random.nextBytes(it) }
            assertEquals("length $length", Base64.getEncoder().encodeToString(bytes), FileHasher.base64(bytes))
        }
    }

    @Test fun base64OfASha256DigestIs44CharactersWithOnePaddingSign() {
        val encoded = FileHasher.base64(MessageDigest.getInstance("SHA-256").digest("x".toByteArray()))
        assertEquals(44, encoded.length)
        assertEquals('=', encoded.last())
    }

    @Test fun hexAndRangeDigestsMatchTheJdk() = runBlocking {
        val file = File.createTempFile("hash", ".bin").apply { deleteOnExit(); writeBytes(ByteArray(300_000) { (it * 31).toByte() }) }
        val hasher = FileHasher()

        val whole = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        assertEquals(whole.joinToString("") { "%02x".format(it) }, hasher.sha256Hex(file))

        val part = MessageDigest.getInstance("SHA-256").digest(file.readBytes().copyOfRange(1_000, 200_000))
        assertEquals(Base64.getEncoder().encodeToString(part), hasher.sha256Base64(file, 1_000, 199_000))
    }
}
