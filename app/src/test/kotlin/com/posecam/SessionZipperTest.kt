package com.posecam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class SessionZipperTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun zipContainsEveryFileUnderTheSessionName() {
        val session = tmp.newFolder("capture-x")
        File(session, "poses.csv").writeText("a,b\n1,2\n")
        File(session, "frames").mkdir()
        val jpeg = ByteArray(1000) { (it % 251).toByte() }
        File(session, "frames/000000_1.jpg").writeBytes(jpeg)

        val out = ByteArrayOutputStream()
        val progress = mutableListOf<Int>()
        SessionZipper.zip(session, out) { n, _ -> progress.add(n) }

        val entries = mutableMapOf<String, Pair<ZipEntry, ByteArray>>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = e to zip.readBytes()
            }
        }
        assertEquals(setOf("capture-x/frames/000000_1.jpg", "capture-x/poses.csv"), entries.keys)
        assertArrayEquals(jpeg, entries["capture-x/frames/000000_1.jpg"]!!.second)
        assertEquals(ZipEntry.STORED, entries["capture-x/frames/000000_1.jpg"]!!.first.method)
        assertEquals("a,b\n1,2\n", String(entries["capture-x/poses.csv"]!!.second))
        assertEquals(listOf(1, 2), progress)
        assertEquals(1000L + 8L, SessionZipper.sizeOf(session))
    }
}
