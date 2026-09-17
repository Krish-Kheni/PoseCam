package com.posecam

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Turns a camera image into a file. Runs on the writer thread only. */
fun interface FrameEncoder {
    fun encode(image: YuvBuffer, output: File)
}

/**
 * Encodes captured frames on a single background thread, never the GL thread.
 * The queue is bounded by the [BufferPool]: at most `pool.capacity` frames are ever pending.
 */
class FrameWriter(
    private val framesDir: File,
    private val encoder: FrameEncoder,
    private val pool: BufferPool,
) {
    data class Stats(
        val written: Long,
        val failedFrameIndices: List<Long>,
        val timestampMismatches: Long,
        val firstError: String?,
    )

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "PoseCam-FrameWriter") }
    private val written = AtomicLong()
    private val mismatches = AtomicLong()
    private val failed = mutableListOf<Long>()
    @Volatile private var firstError: String? = null

    init {
        check(framesDir.isDirectory || framesDir.mkdirs()) { "Could not create $framesDir" }
    }

    /** Takes ownership of [buffer]; it goes back to the pool once written (or failed). */
    fun submit(frameIndex: Long, timestampNs: Long, buffer: YuvBuffer) {
        executor.execute {
            try {
                // Filenames use the frame timestamp; the image should carry the same one.
                if (buffer.timestampNs != timestampNs) mismatches.incrementAndGet()
                val name = fileName(frameIndex, timestampNs)
                val tmp = File(framesDir, "$name.tmp")
                encoder.encode(buffer, tmp)
                check(tmp.renameTo(File(framesDir, name))) { "Could not rename $tmp" }
                written.incrementAndGet()
            } catch (e: Exception) {
                synchronized(failed) { failed.add(frameIndex) }
                if (firstError == null) firstError = "frame $frameIndex: $e"
            } finally {
                pool.release(buffer)
            }
        }
    }

    /** Waits for every pending frame, then shuts the thread down. */
    fun finish(): Stats {
        executor.shutdown()
        check(executor.awaitTermination(60, TimeUnit.SECONDS)) { "Frame writer did not finish" }
        return Stats(written.get(), synchronized(failed) { failed.toList() }, mismatches.get(), firstError)
    }

    companion object {
        fun fileName(frameIndex: Long, timestampNs: Long): String = "%06d_%d.jpg".format(frameIndex, timestampNs)
    }
}
