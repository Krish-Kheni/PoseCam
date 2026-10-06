package com.posecam.core.sync

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32

/**
 * A PoseCam take is thousands of small JPEGs (about 4,500 for 2.5 minutes). Queueing one row and two
 * API calls per JPEG would mean ~9,000 calls per take against the backend's throttle, so frames travel
 * in zip chunks of [FRAMES_PER_CHUNK] instead: `frames-00000.zip` holds frame indices 0..999, and so on.
 *
 * Chunk contents are exactly the `frames/NNNNNN_<ts>.jpg` files that exist when the session is
 * finalized. A frame whose JPEG failed to write is simply absent (its `poses.csv` row still says
 * `saved`; `manifest.json` lists it under `images.write_failures`). This is NOT fixed up here.
 */
object FrameChunks {
    const val FRAMES_PER_CHUNK = 1000

    /** `<frameIndex>_<timestampNs>.jpg`; ASCII digits only, so `.tmp` files and anything else are ignored. */
    private val JPEG = Regex("""^([0-9]{6,})_([0-9]+)\.jpg$""")

    data class ChunkPlan(
        val index: Int,
        val relativePath: String,
        /** JPEGs in the chunk. */
        val itemCount: Int,
        /** Sum of the JPEG sizes: an estimate (the zip is a little larger) that only feeds progress until it is built. */
        val estimatedBytes: Long,
    )

    fun relativePath(chunkIndex: Int): String = String.format(Locale.ROOT, "frames-%05d.zip", chunkIndex)

    /** The chunk number of `frames-00012.zip`, or null for any other path. */
    fun indexOf(relativePath: String): Int? =
        Regex("""^frames-([0-9]{5,6})\.zip$""").matchEntire(relativePath)?.groupValues?.get(1)?.toIntOrNull()

    private fun chunkOf(frameIndex: Long): Int = (frameIndex / FRAMES_PER_CHUNK).toInt()

    /** Every JPEG of [framesDir] with its frame index, sorted by file name (= by frame index). */
    private fun jpegs(framesDir: File): List<Pair<Long, File>> =
        framesDir.listFiles().orEmpty()
            .mapNotNull { file ->
                val match = JPEG.matchEntire(file.name) ?: return@mapNotNull null
                val index = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                if (file.isFile) index to file else null
            }
            .sortedBy { it.second.name }

    /** One plan per non-empty chunk, in chunk order. Empty when there is no `frames/` folder or no JPEG. */
    fun plan(framesDir: File): List<ChunkPlan> =
        jpegs(framesDir)
            .groupBy { chunkOf(it.first) }
            .toSortedMap()
            .map { (index, frames) ->
                ChunkPlan(index, relativePath(index), frames.size, frames.sumOf { it.second.length() })
            }

    /** Number of JPEGs currently in [framesDir]; retention compares it with what verified chunks cover. */
    fun countJpegs(framesDir: File): Int = jpegs(framesDir).size

    /**
     * Builds chunk [chunkIndex] at [output]: to `<output>.tmp` first, then renamed, so a file that exists
     * is always complete. Deterministic: the same JPEGs always give the same bytes (see [DeterministicZip]).
     * Returns the number of JPEGs packed. Throws [FileNotFoundException] if the chunk has no JPEG left.
     */
    fun build(framesDir: File, chunkIndex: Int, output: File): Int {
        val files = jpegs(framesDir).filter { chunkOf(it.first) == chunkIndex }.map { it.second }
        if (files.isEmpty()) throw FileNotFoundException("no frames for chunk $chunkIndex in $framesDir")
        output.parentFile?.mkdirs()
        val temp = File(output.path + ".tmp")
        try {
            DeterministicZip.write(files, temp)
            if (!temp.renameTo(output)) throw IOException("Could not rename $temp to $output")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return files.size
    }
}

/**
 * A minimal, byte-for-byte reproducible zip writer: entries sorted by name, STORED (JPEGs are already
 * compressed), fixed 1980-01-01 DOS timestamp, no extra fields, no comment. The backend records the
 * SHA-256 declared when a file is first presigned, so rebuilding a chunk after a crash must give
 * the identical bytes. `java.util.zip.ZipOutputStream` cannot promise that: its DOS time depends on the
 * JVM's time zone and file modification times.
 */
object DeterministicZip {
    private const val LOCAL_HEADER = 0x04034b50
    private const val CENTRAL_HEADER = 0x02014b50
    private const val END_OF_CENTRAL_DIRECTORY = 0x06054b50
    private const val VERSION = 10 // 1.0: STORED, no extensions
    private const val DOS_TIME = 0 // 00:00:00
    private const val DOS_DATE = 0x0021 // 1980-01-01
    private const val MAX_ENTRIES = 0xFFFF
    private const val MAX_BYTES = 0xFFFFFFFFL

    private class Entry(val name: ByteArray, val file: File, val size: Long, val crc: Long, val offset: Long)

    fun write(files: List<File>, output: File) {
        val sorted = files.sortedBy { it.name }
        require(sorted.size <= MAX_ENTRIES) { "too many entries for a plain zip: ${sorted.size}" }
        require(sorted.map { it.name }.toSet().size == sorted.size) { "duplicate entry names" }

        var offset = 0L
        val entries = ArrayList<Entry>(sorted.size)
        BufferedOutputStream(FileOutputStream(output), 256 * 1024).use { out ->
            for (file in sorted) {
                val name = file.name.toByteArray(Charsets.US_ASCII)
                val (size, crc) = measure(file)
                require(size <= MAX_BYTES && offset + size < MAX_BYTES) { "zip would exceed 4 GiB" }
                val entry = Entry(name, file, size, crc, offset)
                offset += writeLocalHeader(out, entry)
                offset += copy(file, out, size)
                entries += entry
            }
            val directoryStart = offset
            var directorySize = 0L
            for (entry in entries) directorySize += writeCentralHeader(out, entry)
            writeEnd(out, entries.size, directorySize, directoryStart)
        }
    }

    /** First pass: STORED entries need their size and CRC in the local header, before the bytes. */
    private fun measure(file: File): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        val buffer = ByteArray(128 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
            }
        }
        return size to crc.value
    }

    private fun copy(file: File, out: OutputStream, expected: Long): Long {
        var copied = 0L
        val buffer = ByteArray(128 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                copied += n
            }
        }
        // The JPEGs are closed and immutable; a change between the two passes would corrupt the entry.
        if (copied != expected) throw IOException("${file.name} changed size while being packed")
        return copied
    }

    private fun writeLocalHeader(out: OutputStream, e: Entry): Long {
        val b = buffer(30 + e.name.size)
        b.i(LOCAL_HEADER).s(VERSION).s(0).s(0) // version needed, flags, method (0 = STORED)
            .s(DOS_TIME).s(DOS_DATE)
            .i(e.crc).i(e.size).i(e.size) // crc, compressed size, uncompressed size
            .s(e.name.size).s(0) // name length, extra length
        b.put(e.name)
        out.write(b.array())
        return b.capacity().toLong()
    }

    private fun writeCentralHeader(out: OutputStream, e: Entry): Long {
        val b = buffer(46 + e.name.size)
        b.i(CENTRAL_HEADER).s(VERSION).s(VERSION).s(0).s(0) // made by, needed, flags, method
            .s(DOS_TIME).s(DOS_DATE)
            .i(e.crc).i(e.size).i(e.size)
            .s(e.name.size).s(0).s(0).s(0).s(0) // name, extra, comment, disk number, internal attributes
            .i(0).i(e.offset) // external attributes, local header offset
        b.put(e.name)
        out.write(b.array())
        return b.capacity().toLong()
    }

    private fun writeEnd(out: OutputStream, count: Int, directorySize: Long, directoryStart: Long) {
        val b = buffer(22)
        b.i(END_OF_CENTRAL_DIRECTORY).s(0).s(0) // this disk, disk with the directory
            .s(count).s(count) // entries on this disk, entries in total
            .i(directorySize).i(directoryStart).s(0) // directory size and offset, comment length
        out.write(b.array())
    }

    private fun ByteBuffer.s(value: Int): ByteBuffer = putShort(value.toShort())

    private fun ByteBuffer.i(value: Int): ByteBuffer = putInt(value)

    private fun ByteBuffer.i(value: Long): ByteBuffer = putInt(value.toInt())

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
