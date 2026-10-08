package com.posecam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import kotlin.math.abs

class SessionExportTest {
    private val frameNs = 33_333_333L

    /** Builds a poses.csv where every row is tracked unless listed in [untracked]. */
    private fun csv(
        n: Int,
        untracked: Set<Int> = emptySet(),
        dropped: Set<Int> = emptySet(),
        jumpAt: Int? = null,
    ): BufferedReader {
        val out = StringBuilder(PoseCsv.HEADER).append('\n')
        for (i in 0 until n) {
            val ts = i * frameNs
            val image = if (i in dropped) PoseCsv.droppedImage("queue_full") else PoseCsv.IMAGE_SAVED
            if (i in untracked) {
                out.append(PoseCsv.untrackedRow(i.toLong(), ts, "PAUSED:INSUFFICIENT_FEATURES", image)).append('\n')
            } else {
                // 1 cm per frame along x, plus a 1 m teleport at jumpAt
                val x = i * 0.01f + if (jumpAt != null && i >= jumpAt) 1.0f else 0f
                out.append(PoseCsv.trackedRow(i.toLong(), ts, floatArrayOf(x, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), image))
                    .append('\n')
            }
        }
        return BufferedReader(StringReader(out.toString()))
    }

    @Test
    fun parsesTrackedAndUntrackedRows() {
        val rows = SessionExport.parsePoses(csv(5, untracked = setOf(2)))
        assertEquals(5, rows.size)
        assertEquals(0.03, rows[3].position!![0], 1e-6)
        assertEquals(1.0, rows[3].quaternion!![3], 1e-9)
        assertTrue(rows[2].position == null && !rows[2].tracked)
        assertTrue(rows.all { it.imageSaved })
    }

    @Test
    fun poseLineIsQuaternionFirstThenTranslation() {
        val export = SessionExport(SessionExport.parsePoses(csv(2)))
        assertEquals("\"<1789000000123>\" ,0.0,0.0,0.0,1.0,0.01,0.0,0.0", export.poseLine(1, 1789000000123L))
    }

    @Test
    fun smallValuesAreWrittenAsPlainDecimals() {
        // Java's Float.toString would give 5.480349E-4 here; the reference exporter writes decimals
        assertEquals("0.0005480349", SessionExport.formatFloat(0.0005480349))
        assertEquals("-0.00051909685", SessionExport.formatFloat(-0.00051909685))
        assertEquals("0.0000007599592", SessionExport.formatFloat(7.599592e-7))
        assertEquals("0.0", SessionExport.formatFloat(0.0))
        assertEquals("-5.426973", SessionExport.formatFloat(-5.42697286605835))
    }

    @Test
    fun aTeleportSplitsTheRecording() {
        val export = SessionExport(SessionExport.parsePoses(csv(60, jumpAt = 30)))
        assertEquals(listOf(30), export.jumps)
        val segments = export.segments()
        assertEquals(2, segments.size)
        assertEquals(0 to 29, segments[0].first to segments[0].last)
        assertEquals(30 to 59, segments[1].first to segments[1].last)
    }

    @Test
    fun shortGapsAreInterpolatedAndLongOnesSplit() {
        // 3-frame gap (interpolated) and a 9-frame gap (splits), with the cap at 5
        val short = (10..12).toSet()
        val long = (30..38).toSet()
        val export = SessionExport(SessionExport.parsePoses(csv(60, untracked = short + long)))
        val segments = export.segments()
        assertEquals(2, segments.size)
        assertEquals(0 to 29, segments[0].first to segments[0].last)
        assertEquals(short.toList(), segments[0].interpolated)
        assertEquals(39 to 59, segments[1].first to segments[1].last)
        assertTrue(segments[1].interpolated.isEmpty())
        // interpolated rows sit on the straight line between the real poses either side
        val line = export.poseLine(11, 0).substringAfterLast(',', "").let { export.poseLine(11, 0).split(",") }
        assertEquals(0.11f, line[5].toFloat(), 1e-6f)   // tx after "<ts>" ,qx,qy,qz,qw
    }

    @Test
    fun aGapHidingARelocalizationIsNotInterpolated() {
        // 3 untracked rows, and the pose on the far side is 1 m away: a relocalization, not motion
        val out = StringBuilder(PoseCsv.HEADER).append('\n')
        for (i in 0 until 20) {
            val ts = i * frameNs
            if (i in 8..10) {
                out.append(PoseCsv.untrackedRow(i.toLong(), ts, "PAUSED:INSUFFICIENT_FEATURES", PoseCsv.IMAGE_SAVED))
            } else {
                val x = i * 0.01f + if (i > 10) 1.0f else 0f
                out.append(PoseCsv.trackedRow(i.toLong(), ts, floatArrayOf(x, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f), PoseCsv.IMAGE_SAVED))
            }
            out.append('\n')
        }
        val export = SessionExport(SessionExport.parsePoses(BufferedReader(StringReader(out.toString()))))
        val segments = export.segments()
        assertEquals(2, segments.size)   // split, not bridged with invented motion
        assertEquals(0 to 7, segments[0].first to segments[0].last)
        assertEquals(11 to 19, segments[1].first to segments[1].last)
        assertTrue(segments.all { it.interpolated.isEmpty() })
    }

    @Test
    fun aDroppedImageReusesThePreviousFrame() {
        val export = SessionExport(SessionExport.parsePoses(csv(10, dropped = setOf(4))))
        assertEquals(1, export.segments().size)
        assertEquals(10, export.segments()[0].size)
        assertEquals(listOf(4), export.segments()[0].reusedImages)
        assertEquals(3L, export.imageRow(4).frameIndex)   // frame 4 shows frame 3's image
        assertEquals(4L * frameNs, export.timestampNs(4)) // but keeps its own pose and timestamp
    }

    @Test
    fun secondsAndSegmentSizesAreMeasuredFromTimestamps() {
        val export = SessionExport(SessionExport.parsePoses(csv(91)))
        val segment = export.segments().single()
        assertEquals(91, segment.size)
        assertEquals(3.0, export.secondsOf(segment), 0.01)
    }

    @Test
    fun epochMsAndStemComeFromTheRecordTap() {
        // Record pressed at wall time T, on the frame clock at P; a frame 100 ms before the tap
        val startWallMs = 1789742919207L
        val pressedNs = 2_000_000_000L
        assertEquals(startWallMs - 100, SessionExport.epochMs(1_900_000_000L, startWallMs, pressedNs))
        assertEquals(startWallMs, SessionExport.epochMs(pressedNs, startWallMs, pressedNs))
        // UTC, so the phone and the analysis machine name the same demo identically
        assertEquals("2026-09-18-14_48_39-06f6db-s2",
            SessionExport.stem(startWallMs, "capture-20260918T201839-06f6db", 1))
        // 2026-09-18T10:48:39.796Z in epoch ms, independent of this machine's time zone
        assertEquals(1789728519796L, SessionExport.parseWallTime("2026-09-18T10:48:39.796Z"))
    }

    @Test
    fun interpolatedQuaternionsStayUnitNormAndTakeTheShortPath() {
        val n = 10
        val out = StringBuilder(PoseCsv.HEADER).append('\n')
        for (i in 0 until n) {
            val ts = i * frameNs
            // 90 degrees about Y at the end, written with a flipped sign to force the short path
            val q = when {
                i == 0 -> floatArrayOf(0f, 0f, 0f, 1f)
                i == n - 1 -> floatArrayOf(0f, -0.7071068f, 0f, -0.7071068f)
                else -> null
            }
            if (q == null) {
                out.append(PoseCsv.untrackedRow(i.toLong(), ts, "PAUSED:NONE", PoseCsv.IMAGE_SAVED)).append('\n')
            } else {
                out.append(PoseCsv.trackedRow(i.toLong(), ts, floatArrayOf(0f, 0f, 0f), q, PoseCsv.IMAGE_SAVED)).append('\n')
            }
        }
        val export = SessionExport(SessionExport.parsePoses(BufferedReader(StringReader(out.toString()))), holdMaxFrames = 8)
        val fields = export.poseLine(5, 0).split(",")
        val quat = (1..4).map { fields[it].toDouble() }
        val norm = Math.sqrt(quat.sumOf { it * it })
        assertEquals(1.0, norm, 1e-6)
        // row 5 of the 0..9 gap is 5/9 of the way from identity to 90 degrees, not 5/9 of 270
        val angle = Math.toDegrees(2 * Math.acos(abs(quat[3])))
        assertEquals(50.0, angle, 1.0)
    }

    // ---- whole recording as one video ----------------------------------------------------------

    private fun rowsOf(n: Int, untracked: Set<Int> = emptySet(), dropped: Set<Int> = emptySet(), jumpAt: Int? = null) =
        SessionExport.parsePoses(csv(n, untracked, dropped, jumpAt))

    @Test
    fun aPoseJumpSplitsTheDefaultExportButNotTheWholeRecording() {
        val export = SessionExport(rowsOf(100, jumpAt = 40))

        assertEquals(2, export.segments().size)
        val whole = export.wholeRecording().single()
        assertEquals((0 until 100).toList(), whole.indices)
        assertEquals(listOf(40), export.jumps) // still reported, so it can be written to the provenance
        assertTrue(export.omittedWithin(whole).isEmpty())
    }

    @Test
    fun aLongRunOfDroppedImagesIsHeldInTheWholeRecordingOnly() {
        val dropped = (10..19).toSet() // 10 in a row: over the reference exporter's 5-frame hold
        val rows = rowsOf(60, dropped = dropped)

        assertTrue("default rule splits at it", SessionExport(rows).segments().size > 1)

        val export = SessionExport(rows, imageHoldMaxFrames = Int.MAX_VALUE)
        val whole = export.wholeRecording().single()
        assertEquals(60, whole.size)
        assertEquals(dropped.toList(), whole.reusedImages)
        // Every held row shows the last JPEG that was actually saved (row 9).
        assertTrue(dropped.all { export.imageRow(it).frameIndex == 9L })
    }

    @Test
    fun wideningTheImageHoldDoesNotWidenPoseInterpolation() {
        val rows = rowsOf(100, untracked = (30..50).toSet()) // 21 rows with no pose, far over the 5-row cap
        val export = SessionExport(rows, imageHoldMaxFrames = Int.MAX_VALUE)

        val whole = export.wholeRecording().single()

        assertEquals(79, whole.size) // the rows with no pose cannot be written...
        assertEquals((30..50).toList(), export.omittedWithin(whole)) // ...and are reported, never invented
        assertTrue(whole.interpolated.isEmpty())
    }

    @Test
    fun aRecordingWithNothingUsableHasNoWholeRecording() {
        assertTrue(SessionExport(rowsOf(5, untracked = (0 until 5).toSet())).wholeRecording().isEmpty())
    }

    @Test
    fun theDefaultRulesAreUnchanged() {
        // The reference exporter's behaviour is what an export does unless whole-recording mode is asked for.
        val export = SessionExport(rowsOf(100, jumpAt = 40, dropped = (60..70).toSet()))
        assertEquals(SessionExport.HOLD_MAX_FRAMES, 5)
        assertTrue(export.segments().size >= 3)
    }
}
