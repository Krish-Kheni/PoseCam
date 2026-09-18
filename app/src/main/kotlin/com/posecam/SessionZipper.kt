package com.posecam

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs a session folder into a zip whose entries are prefixed with the session name. */
object SessionZipper {
    /** JPEGs are already compressed; storing them avoids a pointless second pass. */
    private val storedExtensions = setOf("jpg", "jpeg", "mp4")

    fun zip(session: File, out: OutputStream, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val files = session.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        ZipOutputStream(out.buffered(256 * 1024)).use { zip ->
            files.forEachIndexed { n, file ->
                val name = session.name + "/" + file.relativeTo(session).path.replace(File.separatorChar, '/')
                val entry = ZipEntry(name).apply { time = file.lastModified() }
                if (file.extension.lowercase() in storedExtensions) {
                    entry.method = ZipEntry.STORED
                    entry.size = file.length()
                    entry.compressedSize = file.length()
                    entry.crc = crc32(file)
                }
                zip.putNextEntry(entry)
                FileInputStream(file).use { it.copyTo(zip, 256 * 1024) }
                zip.closeEntry()
                onProgress(n + 1, files.size)
            }
        }
    }

    fun sizeOf(session: File): Long = session.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun crc32(file: File): Long {
        val crc = java.util.zip.CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
            }
        }
        return crc.value
    }
}
