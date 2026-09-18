package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class FrameWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun poolBoundsPendingFrames() {
        val pool = BufferPool(2)
        val a = pool.tryAcquire()!!
        pool.tryAcquire()!!
        assertNull(pool.tryAcquire())
        pool.release(a)
        assertEquals(1, pool.available)
    }

    @Test
    fun writesFilesNamedByIndexAndTimestampAndReturnsBuffers() {
        val pool = BufferPool(4)
        val dir = File(tmp.root, "frames")
        val writer = FrameWriter(dir, { image, out -> out.writeText("ts=${image.timestampNs}") }, pool)

        writer.submit(0, 100, filled(pool, 100))
        writer.submit(1, 133_000_000, filled(pool, 141_000_000)) // 8 ms apart (Tecno jitter): same frame
        writer.submit(2, 166_000_000, filled(pool, 199_000_000)) // a frame interval apart: wrong frame
        val stats = writer.finish()

        assertEquals(3, stats.written)
        assertEquals(1, stats.timestampMismatches)
        assertEquals(listOf(0L, 33_000_000L), stats.imageMinusFrameNs!!.toList())
        assertEquals(listOf("000000_100.jpg", "000001_133000000.jpg", "000002_166000000.jpg"), dir.list()!!.sorted())
        assertEquals(4, pool.available)
    }

    @Test
    fun recordsEncoderFailuresAndStillReturnsBuffer() {
        val pool = BufferPool(1)
        val dir = File(tmp.root, "frames")
        val writer = FrameWriter(dir, { _, _ -> error("disk full") }, pool)
        writer.submit(5, 100, filled(pool, 100))
        val stats = writer.finish()
        assertEquals(listOf(5L), stats.failedFrameIndices)
        assertEquals(0, dir.list()!!.size)
        assertEquals(1, pool.available)
    }

    private fun filled(pool: BufferPool, timestampNs: Long) = pool.tryAcquire()!!.apply {
        set(2, 2, timestampNs, ByteBuffer.wrap(ByteArray(4)), ByteBuffer.wrap(ByteArray(1)), ByteBuffer.wrap(ByteArray(1)), 2, 1, 1)
    }
}
