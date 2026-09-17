package com.posecam

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import java.io.File

/** GL thread: copies the frame's CPU image into a pooled buffer and closes the image at once. */
class ImageGrabber(private val pool: BufferPool) {

    fun grab(frame: Frame): FrameImage {
        val buffer = pool.tryAcquire() ?: return FrameImage.Dropped(FrameImage.QUEUE_FULL)
        try {
            // Always closed: the image pool is tiny and a leak stalls the session.
            frame.acquireCameraImage().use { image ->
                check(image.format == ImageFormat.YUV_420_888) { "Unexpected image format ${image.format}" }
                val planes = image.planes
                buffer.set(
                    image.width, image.height, image.timestamp,
                    planes[0].buffer, planes[1].buffer, planes[2].buffer,
                    planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride,
                )
            }
            return FrameImage.Captured(buffer)
        } catch (e: Exception) {
            pool.release(buffer)
            return FrameImage.Dropped(
                when (e) {
                    is NotYetAvailableException -> FrameImage.NOT_YET_AVAILABLE
                    is DeadlineExceededException -> FrameImage.DEADLINE_EXCEEDED
                    is ResourceExhaustedException -> FrameImage.RESOURCES_EXHAUSTED
                    else -> throw e
                }
            )
        }
    }
}

/** Writer thread: YUV → NV21 → JPEG via the platform encoder. */
class JpegEncoder(private val quality: Int) : FrameEncoder {
    private var nv21 = ByteArray(0)

    override fun encode(image: YuvBuffer, output: File) {
        if (nv21.size < image.nv21Size()) nv21 = ByteArray(image.nv21Size())
        image.toNv21(nv21)
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        output.outputStream().buffered(256 * 1024).use { out ->
            check(yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)) { "JPEG compression failed" }
        }
    }
}
