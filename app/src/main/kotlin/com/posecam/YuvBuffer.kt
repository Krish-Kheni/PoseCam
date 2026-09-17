package com.posecam

import java.nio.ByteBuffer

/**
 * A reusable copy of a YUV_420_888 camera image. Copying on the GL thread lets the
 * ARCore image be closed immediately; conversion and encoding happen on the writer thread.
 */
class YuvBuffer {
    var width = 0
        private set
    var height = 0
        private set
    var timestampNs = 0L
        private set

    var y = ByteArray(0)
        private set
    var u = ByteArray(0)
        private set
    var v = ByteArray(0)
        private set
    var yRowStride = 0
        private set
    var uvRowStride = 0
        private set
    var uvPixelStride = 0
        private set

    fun set(
        width: Int, height: Int, timestampNs: Long,
        yPlane: ByteBuffer, uPlane: ByteBuffer, vPlane: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
    ) {
        this.width = width
        this.height = height
        this.timestampNs = timestampNs
        this.yRowStride = yRowStride
        this.uvRowStride = uvRowStride
        this.uvPixelStride = uvPixelStride
        y = copy(yPlane, y)
        u = copy(uPlane, u)
        v = copy(vPlane, v)
    }

    /** Writes the image as NV21 (Y plane, then interleaved V/U at half resolution) into [out]. */
    fun toNv21(out: ByteArray = ByteArray(nv21Size())): ByteArray {
        require(out.size >= nv21Size()) { "NV21 buffer too small" }
        for (row in 0 until height) {
            System.arraycopy(y, row * yRowStride, out, row * width, width)
        }
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var dst = width * height
        for (row in 0 until chromaHeight) {
            var src = row * uvRowStride
            for (col in 0 until chromaWidth) {
                out[dst++] = v[src]
                out[dst++] = u[src]
                src += uvPixelStride
            }
        }
        return out
    }

    fun nv21Size(): Int = width * height + 2 * (width / 2) * (height / 2)

    private companion object {
        fun copy(src: ByteBuffer, dst: ByteArray): ByteArray {
            val buffer = src.duplicate().apply { rewind() }
            val n = buffer.remaining()
            val out = if (dst.size >= n) dst else ByteArray(n)
            buffer.get(out, 0, n)
            return out
        }
    }
}
