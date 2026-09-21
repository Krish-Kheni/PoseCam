package com.posecam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes recorded JPEGs into the H.264 MP4 the pipeline reads, on the phone.
 *
 * Every input frame produces exactly one output frame, in order: the consumer aligns pose
 * line N with video frame N. Rotation is applied to the pixels, not written as metadata,
 * because OpenCV and decord ignore the MP4 rotation hint.
 */
object Mp4Writer {
    private const val TAG = "PoseCam"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    const val DEFAULT_BIT_RATE = 4_000_000
    private const val TIMEOUT_US = 10_000L

    class EncodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @param files one JPEG per output frame, in order
     * @param rotateDegrees clockwise rotation applied to the image (0/90/180/270)
     * @return the number of frames written
     */
    fun encode(
        files: List<File>,
        output: File,
        fps: Int = 30,
        rotateDegrees: Int = 0,
        bitRate: Int = DEFAULT_BIT_RATE,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        require(files.isNotEmpty()) { "no frames to encode" }
        val first = BitmapFactory.decodeFile(files[0].path)
            ?: throw EncodeException("Could not decode ${files[0].name}")
        val sourceWidth = first.width
        val sourceHeight = first.height
        val swap = rotateDegrees == 90 || rotateDegrees == 270
        val width = if (swap) sourceHeight else sourceWidth
        val height = if (swap) sourceWidth else sourceHeight
        // H.264 encoders need even dimensions; recorded sizes always are, but fail loudly if not.
        require(width % 2 == 0 && height % 2 == 0) { "odd output size ${width}x$height" }

        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // One keyframe per second keeps seeking cheap for the training dataloader.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MIME)
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        var written = 0

        // Reused across frames: decoding 5000 bitmaps otherwise thrashes the heap.
        val decodeOptions = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var source = first.copy(Bitmap.Config.ARGB_8888, true).also { first.recycle() }
        val rotated = if (rotateDegrees == 0) null else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = rotated?.let { Canvas(it) }
        val matrix = Matrix().apply {
            postRotate(rotateDegrees.toFloat())
            when (rotateDegrees) {
                90 -> postTranslate(sourceHeight.toFloat(), 0f)
                180 -> postTranslate(sourceWidth.toFloat(), sourceHeight.toFloat())
                270 -> postTranslate(0f, sourceWidth.toFloat())
            }
        }
        val pixels = IntArray(width * height)
        val info = MediaCodec.BufferInfo()

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxing) { "encoder format changed twice" }
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                check(muxing) { "encoder produced data before the format was known" }
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                                written++
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            for ((n, file) in files.withIndex()) {
                decodeOptions.inBitmap = source
                val decoded = try {
                    BitmapFactory.decodeFile(file.path, decodeOptions)
                } catch (e: IllegalArgumentException) {   // inBitmap not reusable for this frame
                    decodeOptions.inBitmap = null
                    BitmapFactory.decodeFile(file.path, decodeOptions)
                } ?: throw EncodeException("Could not decode ${file.name}")
                source = decoded
                if (canvas != null) {
                    canvas.drawBitmap(decoded, matrix, null)
                    rotated!!.getPixels(pixels, 0, width, 0, 0, width, height)
                } else {
                    decoded.getPixels(pixels, 0, width, 0, 0, width, height)
                }

                var inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                while (inputIndex < 0) {
                    drain(false)
                    inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                }
                val size = fillInput(codec, inputIndex, pixels, width, height)
                val presentationUs = n.toLong() * 1_000_000L / fps
                codec.queueInputBuffer(inputIndex, 0, size, presentationUs, 0)
                drain(false)
                if (n % 30 == 0) onProgress(n, files.size)
            }

            val endIndex = run {
                var index = codec.dequeueInputBuffer(TIMEOUT_US)
                while (index < 0) {
                    drain(false)
                    index = codec.dequeueInputBuffer(TIMEOUT_US)
                }
                index
            }
            codec.queueInputBuffer(endIndex, 0, 0, files.size.toLong() * 1_000_000L / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            onProgress(files.size, files.size)
        } catch (e: Exception) {
            throw if (e is EncodeException) e else EncodeException("Encoding failed: $e", e)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxing) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            rotated?.recycle()
            source.recycle()
        }
        if (written != files.size) {
            Log.w(TAG, "encoder wrote $written frames for ${files.size} inputs")
        }
        return written
    }

    /**
     * Writes ARGB pixels into the codec's input as YUV 4:2:0, BT.601 limited range (as ffmpeg
     * does for full-range JPEG input). Returns the number of bytes queued.
     */
    private fun fillInput(codec: MediaCodec, index: Int, pixels: IntArray, width: Int, height: Int): Int {
        val image = codec.getInputImage(index)
        if (image != null) {
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]
            writeYuv(pixels, width, height, y.buffer, y.rowStride, y.pixelStride,
                u.buffer, u.rowStride, u.pixelStride, v.buffer, v.rowStride, v.pixelStride)
            return width * height * 3 / 2
        }
        // Devices whose encoder does not expose an Image: assume the flexible format is I420.
        val buffer = codec.getInputBuffer(index) ?: throw EncodeException("no input buffer")
        buffer.clear()
        val ySize = width * height
        val chroma = ySize / 4
        val yBuf = buffer.duplicate().apply { position(0); limit(ySize) }.slice()
        val uBuf = buffer.duplicate().apply { position(ySize); limit(ySize + chroma) }.slice()
        val vBuf = buffer.duplicate().apply { position(ySize + chroma); limit(ySize + 2 * chroma) }.slice()
        writeYuv(pixels, width, height, yBuf, width, 1, uBuf, width / 2, 1, vBuf, width / 2, 1)
        return ySize * 3 / 2
    }

    private fun writeYuv(
        pixels: IntArray, width: Int, height: Int,
        yBuf: ByteBuffer, yRowStride: Int, yPixelStride: Int,
        uBuf: ByteBuffer, uRowStride: Int, uPixelStride: Int,
        vBuf: ByteBuffer, vRowStride: Int, vPixelStride: Int,
    ) {
        for (row in 0 until height) {
            var yIndex = row * yRowStride
            var p = row * width
            for (col in 0 until width) {
                val argb = pixels[p++]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                yBuf.put(yIndex, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                yIndex += yPixelStride
            }
        }
        // Chroma from the mean of each 2x2 block, like a proper downsample.
        for (row in 0 until height / 2) {
            var uIndex = row * uRowStride
            var vIndex = row * vRowStride
            for (col in 0 until width / 2) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val argb = pixels[(2 * row + dy) * width + (2 * col + dx)]
                    r += (argb shr 16) and 0xFF
                    g += (argb shr 8) and 0xFF
                    b += argb and 0xFF
                }
                r /= 4; g /= 4; b /= 4
                uBuf.put(uIndex, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                vBuf.put(vIndex, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                uIndex += uPixelStride
                vIndex += vPixelStride
            }
        }
    }
}
