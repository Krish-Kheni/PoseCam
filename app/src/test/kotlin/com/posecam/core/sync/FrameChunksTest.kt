package com.posecam.core.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class FrameChunksTest {
    private val f = SyncFixture()
    private fun session() = f.sessionDir("capture-20260917T090000-a3f9c1")
    private fun frames(dir: File) = File(dir, "frames")

    // ---- planning ---------------------------------------------------------------------------

    @Test fun chunkCountsForTheBoundaryFrameCounts() {
        val expected = mapOf(0 to listOf(), 1 to listOf(1), 999 to listOf(999), 1000 to listOf(1000), 1001 to listOf(1000, 1), 2500 to listOf(1000, 1000, 500))
        for ((count, sizes) in expected) {
            val dir = session().also { f.frames(it, count) }
            val plan = FrameChunks.plan(frames(dir))
            assertEquals("frames=$count", sizes, plan.map { it.itemCount })
            assertEquals(List(sizes.size) { FrameChunks.relativePath(it) }, plan.map { it.relativePath })
            dir.deleteRecursively()
        }
    }

    @Test fun noFramesFolderOrAnEmptyOneMeansNoChunks() {
        assertTrue(FrameChunks.plan(File(session(), "frames")).isEmpty())
        val dir = session()
        frames(dir).mkdirs()
        assertTrue(FrameChunks.plan(frames(dir)).isEmpty())
    }

    @Test fun chunkNumberIsFrameIndexDividedByAThousandSoARangeIsContiguous() {
        val dir = session().also { f.frames(it, 1200) }
        val plan = FrameChunks.plan(frames(dir))
        val chunk1 = File(dir, "out.zip").also { FrameChunks.build(frames(dir), 1, it) }
        ZipFile(chunk1).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(200, names.size)
            assertTrue(names.first().startsWith("001000_"))
            assertTrue(names.last().startsWith("001199_"))
        }
        assertEquals(200, plan[1].itemCount)
    }

    @Test fun onlyRealJpegsCount_tmpFilesAndStrangersAreIgnored() {
        val dir = session().also { f.frames(it, 3) }
        File(frames(dir), "000003_99.jpg.tmp").writeText("half written")
        File(frames(dir), "notes.txt").writeText("x")
        File(frames(dir), "5_1.jpg").writeText("too few digits")
        File(frames(dir), "000004_x.jpg").writeText("no timestamp")
        File(frames(dir), "000005_7.jpg").mkdirs() // a directory with a JPEG-like name

        assertEquals(3, FrameChunks.plan(frames(dir)).single().itemCount)
        assertEquals(3, FrameChunks.countJpegs(frames(dir)))
    }

    @Test fun anAbsentFrameIsSimplyAbsent_NothingIsFixedUpByTheUploader() {
        // A JPEG that failed to write is missing although poses.csv says "saved": the chunk holds what exists.
        val dir = session().also { f.frames(it, 10) }
        frames(dir).listFiles()!!.first { it.name.startsWith("000004_") }.delete()

        assertEquals(9, FrameChunks.plan(frames(dir)).single().itemCount)
    }

    @Test fun relativePathAndIndexRoundTrip() {
        assertEquals("frames-00007.zip", FrameChunks.relativePath(7))
        assertEquals("frames-100000.zip", FrameChunks.relativePath(100_000))
        assertEquals(7, FrameChunks.indexOf("frames-00007.zip"))
        assertEquals(100_000, FrameChunks.indexOf("frames-100000.zip"))
        assertNull(FrameChunks.indexOf("frames-7.zip"))
        assertNull(FrameChunks.indexOf("poses.csv"))
    }

    // ---- building ---------------------------------------------------------------------------

    @Test fun theZipIsReadableByAStandardReaderAndEveryEntryIsByteForByteTheJpeg() {
        val dir = session().also { f.frames(it, 120) }
        val out = File(dir, "chunk.zip")

        assertEquals(120, FrameChunks.build(frames(dir), 0, out))

        ZipFile(out).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(120, entries.size)
            assertEquals("entries are sorted by name", entries.map { it.name }.sorted(), entries.map { it.name })
            for (entry in entries) {
                assertEquals("stored, not deflated", ZipEntry.STORED, entry.method)
                val original = File(frames(dir), entry.name).readBytes()
                assertArrayEquals(entry.name, original, zip.getInputStream(entry).readBytes())
                assertEquals(original.size.toLong(), entry.size)
                assertEquals(CRC32().apply { update(original) }.value, entry.crc)
            }
        }
    }

    @Test fun theSameFramesAlwaysGiveByteIdenticalZips() {
        val dir = session().also { f.frames(it, 300) }
        val a = File(dir, "a.zip").also { FrameChunks.build(frames(dir), 0, it) }
        val b = File(dir, "b.zip").also { FrameChunks.build(frames(dir), 0, it) }
        assertArrayEquals(a.readBytes(), b.readBytes())
    }

    @Test fun fileModificationTimesDoNotChangeTheBytes() {
        val dir = session().also { f.frames(it, 50) }
        val a = File(dir, "a.zip").also { FrameChunks.build(frames(dir), 0, it) }
        frames(dir).listFiles()!!.forEachIndexed { i, file -> file.setLastModified(1_000_000_000L + i * 86_400_000L) }
        val b = File(dir, "b.zip").also { FrameChunks.build(frames(dir), 0, it) }
        assertArrayEquals(a.readBytes(), b.readBytes())
    }

    @Test fun theTimeZoneDoesNotChangeTheBytes() {
        // The backend remembers the SHA-256 declared at presign: a rebuild on a phone that moved zone must match it.
        val dir = session().also { f.frames(it, 50) }
        val original = TimeZone.getDefault()
        try {
            val digests = listOf("UTC", "Asia/Kolkata", "America/Los_Angeles", "Pacific/Auckland").map { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                File(dir, "tz.zip").also { FrameChunks.build(frames(dir), 0, it) }.readBytes().toList()
            }
            assertEquals(1, digests.toSet().size)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun differentFramesGiveDifferentBytes() {
        val dir = session().also { f.frames(it, 5) }
        val a = File(dir, "a.zip").also { FrameChunks.build(frames(dir), 0, it) }.readBytes()
        File(frames(dir), frames(dir).list()!!.sorted().first()).writeBytes(byteArrayOf(9, 9, 9))
        val b = File(dir, "b.zip").also { FrameChunks.build(frames(dir), 0, it) }.readBytes()
        assertFalse(a.contentEquals(b))
    }

    @Test fun nothingIsLeftBehindOnSuccessAndNothingExistsOnFailure() {
        val dir = session().also { f.frames(it, 5) }
        val out = File(dir, "stage/chunk.zip")

        FrameChunks.build(frames(dir), 0, out)
        assertTrue(out.isFile)
        assertFalse(File(out.path + ".tmp").exists())

        val missing = File(dir, "stage/none.zip")
        assertThrows(FileNotFoundException::class.java) { FrameChunks.build(frames(dir), 9, missing) }
        assertFalse(missing.exists())
        assertFalse(File(missing.path + ".tmp").exists())
    }

    @Test fun aFrameThatVanishesAfterPlanningIsSimplyNotPackedAndNoTempFileRemains() {
        val dir = session().also { f.frames(it, 5) }
        val out = File(dir, "chunk.zip")
        // A frame that disappears between planning and packing.
        val victim = frames(dir).listFiles()!!.sortedBy { it.name }[2]
        val original = FrameChunks.plan(frames(dir)).single()
        assertEquals(5, original.itemCount)
        victim.delete()

        assertEquals(4, FrameChunks.build(frames(dir), 0, out)) // built from what exists now
        assertFalse(File(out.path + ".tmp").exists())
    }

    @Test fun aFullThousandFrameChunkHasAThousandEntries() {
        val dir = session().also { f.frames(it, 1_000) }
        val out = File(dir, "c.zip")
        assertEquals(1_000, FrameChunks.build(frames(dir), 0, out))
        ZipFile(out).use { assertEquals(1_000, it.size()) }
    }
}
