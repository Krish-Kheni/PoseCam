package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Checks the on-device export logic against real recordings, and writes the pose lines it
 * would produce to /tmp so they can be diffed against tools/export_anysense.py (the
 * validated reference implementation). Skipped when the repository's data/ is absent.
 */
class SessionExportCrossCheckTest {
    private val sessions = File("../data").listFiles { f: File -> f.isDirectory && f.name.startsWith("capture-") }
        ?.sortedBy { it.name } ?: emptyList()

    @Test
    fun matchesTheReferenceExporterOnRealSessions() {
        assumeTrue("no recordings in data/", sessions.isNotEmpty())
        val out = File("/tmp/posecam-crosscheck").apply { deleteRecursively(); mkdirs() }
        var checked = 0
        for (session in sessions) {
            val posesFile = File(session, "poses.csv")
            val manifestFile = File(session, "manifest.json")
            if (!posesFile.exists() || !manifestFile.exists()) continue
            if (!File(session, "frames").isDirectory) continue   // posecam-1: poses only, nothing to export
            val manifest = manifestFile.readText()
            if ("\"complete\": true" !in manifest) continue
            val startWall = Regex("\"start_wall_time_utc\": \"([^\"]+)\"").find(manifest)?.groupValues?.get(1) ?: continue
            val pressed = Regex("\"record_pressed_elapsed_realtime_ns\": (\\d+)").find(manifest)?.groupValues?.get(1)?.toLong()
                ?: (Regex("\"first_timestamp_ns\": (\\d+)").find(manifest)!!.groupValues[1].toLong() + 100_000_000L)
            val startWallMs = SessionExport.parseWallTime(startWall)

            val export = SessionExport(posesFile.bufferedReader().use { SessionExport.parsePoses(it) })
            val segments = export.segments().filter { export.secondsOf(it) >= SessionExport.MIN_SECONDS }
            val report = StringBuilder()
            for ((n, segment) in segments.withIndex()) {
                val stem = SessionExport.stem(
                    SessionExport.epochMs(export.timestampNs(segment.first), startWallMs, pressed), session.name, n)
                report.append("SEGMENT $stem rows=${segment.first}-${segment.last} frames=${segment.size} " +
                    "interpolated=${segment.interpolated.size} reused=${segment.reusedImages.size}\n")
                val lines = File(out, "${session.name}__$stem.txt").bufferedWriter()
                lines.use { w ->
                    for (index in segment.indices) {
                        w.write(export.poseLine(index, SessionExport.epochMs(export.timestampNs(index), startWallMs, pressed)))
                        w.newLine()
                    }
                }
                // every exported row must have a pose and a real frame file behind it
                assertTrue("segment rows are contiguous", segment.indices.zipWithNext().all { (a, b) -> b == a + 1 })
                val frame = File(File(session, "frames"), FrameWriter.fileName(
                    export.imageRow(segment.first).frameIndex, export.imageRow(segment.first).timestampNs))
                assertTrue("frame ${frame.name} exists", frame.exists())
            }
            File(out, "${session.name}__segments.txt").writeText(report.toString())
            checked++
        }
        assumeTrue("no complete sessions with poses.csv", checked > 0)
        println("cross-check wrote ${out.listFiles()?.size} files for $checked session(s) to $out")
        assertEquals(checked, File(out, ".").listFiles { f: File -> f.name.endsWith("__segments.txt") }!!.size)
    }
}
