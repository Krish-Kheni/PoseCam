package com.posecam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class YuvBufferTest {
    /** 4x2 image laid out like a real semi-planar camera buffer: row padding, pixel stride 2. */
    @Test
    fun convertsStridedPlanesToNv21() {
        val width = 4
        val height = 2
        val yRowStride = 6 // 2 bytes of padding per row
        val y = byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8)          // last row has no padding
        val u = byteArrayOf(10, 99, 11)                            // U0 . U1 (pixel stride 2)
        val v = byteArrayOf(20, 99, 21)                            // V0 . V1

        val buffer = YuvBuffer()
        buffer.set(width, height, 123, ByteBuffer.wrap(y), ByteBuffer.wrap(u), ByteBuffer.wrap(v), yRowStride, 4, 2)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11), buffer.toNv21())
    }

    @Test
    fun reusesArraysAcrossFrames() {
        val buffer = YuvBuffer()
        val plane = ByteBuffer.wrap(ByteArray(16) { it.toByte() })
        buffer.set(4, 4, 1, plane, ByteBuffer.wrap(ByteArray(4)), ByteBuffer.wrap(ByteArray(4)), 4, 2, 1)
        val first = buffer.y
        buffer.set(4, 4, 2, plane, ByteBuffer.wrap(ByteArray(4)), ByteBuffer.wrap(ByteArray(4)), 4, 2, 1)
        assertTrue(first === buffer.y)
    }
}
