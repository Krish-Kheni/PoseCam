package com.posecam

import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a recorded session into the recordings the training pipeline consumes: the same
 * rules as tools/export_anysense.py, so on-phone and on-PC exports agree.
 *
 * Pure logic (no Android APIs) so it can be unit-tested: parsing, jump detection, gap
 * interpolation, segmentation and line formatting. The caller supplies the manifest
 * values and does the encoding.
 */
class SessionExport(
    private val rows: List<Row>,
    private val holdMaxFrames: Int = HOLD_MAX_FRAMES,
) {
    class Row(
        val frameIndex: Long,
        val timestampNs: Long,
        val position: DoubleArray?,
        val quaternion: DoubleArray?,
        val imageSaved: Boolean,
    ) {
        val tracked: Boolean get() = position != null && quaternion != null
    }

    /** Rows of one continuous, jump-free run; [first]..[last] are indices into the session. */
    class Segment(val indices: List<Int>, val interpolated: List<Int>, val reusedImages: List<Int>) {
        val first: Int get() = indices.first()
        val last: Int get() = indices.last()
        val size: Int get() = indices.size
    }

    private val position = arrayOfNulls<DoubleArray>(rows.size)
    private val quaternion = arrayOfNulls<DoubleArray>(rows.size)
    private val imageSource = arrayOfNulls<Int>(rows.size)
    private val interpolatedRows = mutableListOf<Int>()
    private val reusedImageRows = mutableListOf<Int>()

    /** Rows where ARCore relocalized: the pose either side is in a different frame. */
    val jumps = mutableListOf<Int>()

    init {
        for (i in rows.indices) {
            position[i] = rows[i].position
            quaternion[i] = rows[i].quaternion
        }
        findJumps()
        fillShortGaps()
        resolveImages()
    }

    private fun findJumps() {
        for (i in 1 until rows.size) {
            val a = rows[i - 1]
            val b = rows[i]
            if (!a.tracked || !b.tracked) continue
            val dt = (b.timestampNs - a.timestampNs) / 1e9
            if (dt <= 0) continue
            var sum = 0.0
            for (k in 0..2) {
                val d = b.position!![k] - a.position!![k]
                sum += d * d
            }
            val distance = sqrt(sum)
            var dot = 0.0
            for (k in 0..3) dot += b.quaternion!![k] * a.quaternion!![k]
            val angle = 2 * acos(min(1.0, abs(dot)))
            if (distance / dt > PoseJumpDetector.MAX_SPEED_M_PER_S || angle / dt > PoseJumpDetector.MAX_RATE_RAD_PER_S) {
                jumps.add(i)
            }
        }
    }

    /**
     * Short tracking gaps get interpolated poses: deleting the rows instead would turn a gap
     * into one huge apparent motion, and the consumer cannot mask interior rows. The cap keeps
     * fabricated motion shorter than its 8-frame action stride.
     *
     * A gap whose ends are further apart than any real motion is a relocalization that happened
     * while tracking was lost. Interpolating that would invent smooth motion nothing downstream
     * could detect, so it is left as a gap and splits the take instead.
     */
    private fun fillShortGaps() {
        val tracked = rows.indices.filter { rows[it].tracked }
        for (n in 0 until tracked.size - 1) {
            val a = tracked[n]
            val b = tracked[n + 1]
            val gap = b - a - 1
            if (gap <= 0 || gap > holdMaxFrames) continue
            if (isDiscontinuity(a, b)) continue
            val qa = quaternion[a]!!
            val qb = DoubleArray(4) { quaternion[b]!![it] }
            var dot = 0.0
            for (k in 0..3) dot += qa[k] * qb[k]
            if (dot < 0) for (k in 0..3) qb[k] = -qb[k]
            for (j in a + 1 until b) {
                val w = (j - a).toDouble() / (b - a)
                position[j] = DoubleArray(3) { (1 - w) * position[a]!![it] + w * position[b]!![it] }
                val q = DoubleArray(4) { (1 - w) * qa[it] + w * qb[it] }
                val norm = sqrt(q.sumOf { it * it })
                quaternion[j] = DoubleArray(4) { q[it] / norm }
                interpolatedRows.add(j)
            }
        }
    }

    /** True when the motion between two tracked rows is faster than any real hand movement. */
    private fun isDiscontinuity(a: Int, b: Int): Boolean {
        val dt = (rows[b].timestampNs - rows[a].timestampNs) / 1e9
        if (dt <= 0) return true
        var sum = 0.0
        for (k in 0..2) {
            val d = rows[b].position!![k] - rows[a].position!![k]
            sum += d * d
        }
        var dot = 0.0
        for (k in 0..3) dot += rows[b].quaternion!![k] * rows[a].quaternion!![k]
        val angle = 2 * acos(min(1.0, abs(dot)))
        return sqrt(sum) / dt > PoseJumpDetector.MAX_SPEED_M_PER_S || angle / dt > PoseJumpDetector.MAX_RATE_RAD_PER_S
    }

    /** A dropped image reuses the previous frame's JPEG rather than breaking the run. */
    private fun resolveImages() {
        var lastSaved: Int? = null
        for (i in rows.indices) {
            if (rows[i].imageSaved) {
                lastSaved = i
                imageSource[i] = i
            } else if (lastSaved != null && i - lastSaved <= holdMaxFrames) {
                imageSource[i] = lastSaved
                reusedImageRows.add(i)
            }
        }
    }

    /** Continuous runs with a pose and an image, split at jumps and long gaps. */
    fun segments(): List<Segment> {
        val breaks = jumps.toSet()
        val out = mutableListOf<Segment>()
        var current = mutableListOf<Int>()
        fun flush() {
            if (current.isNotEmpty()) {
                val indices = current.toList()
                out.add(Segment(
                    indices,
                    interpolatedRows.filter { it in indices.first()..indices.last() },
                    reusedImageRows.filter { it in indices.first()..indices.last() },
                ))
            }
            current = mutableListOf()
        }
        for (i in rows.indices) {
            val usable = position[i] != null && quaternion[i] != null && imageSource[i] != null
            if (!usable || i in breaks) flush()
            if (usable) current.add(i)
        }
        flush()
        return out
    }

    fun secondsOf(segment: Segment): Double =
        (rows[segment.last].timestampNs - rows[segment.first].timestampNs) / 1e9

    /** The JPEG to encode for a row (its own, or the previous one when its image was dropped). */
    fun imageRow(index: Int): Row = rows[imageSource[index]!!]

    fun timestampNs(index: Int): Long = rows[index].timestampNs

    /** One AnySense pose line: quaternion xyzw first, then translation, raw. */
    fun poseLine(index: Int, epochMs: Long): String {
        val q = quaternion[index]!!
        val p = position[index]!!
        val values = doubleArrayOf(q[0], q[1], q[2], q[3], p[0], p[1], p[2])
        return buildString {
            append("\"<").append(epochMs).append(">\" ,")
            for (k in values.indices) {
                if (k > 0) append(',')
                append(formatFloat(values[k]))
            }
        }
    }

    companion object {
        /**
         * Shortest text that reads back as the same float32, always in plain decimal:
         * Java would switch to 5.4E-4 below 1e-3, and not every consumer's parser copes.
         * Matches tools/export_anysense.py exactly.
         */
        fun formatFloat(value: Double): String {
            val text = value.toFloat().toString()
            return if ('E' in text) java.math.BigDecimal(text).toPlainString() else text
        }

        /** Must stay under the consumer's 8-frame action stride (see docs/COORDINATES.md). */
        const val HOLD_MAX_FRAMES = 5
        const val MIN_SECONDS = 3.0

        fun parsePoses(reader: BufferedReader): List<Row> {
            val out = mutableListOf<Row>()
            val header = reader.readLine() ?: return out
            val columns = header.split(",")
            val ix = columns.indexOf("frame_index")
            val its = columns.indexOf("timestamp_ns")
            val itx = columns.indexOf("tx")
            val iqx = columns.indexOf("qx")
            val iimage = columns.indexOf("image")
            require(ix >= 0 && its >= 0 && itx >= 0 && iqx >= 0) { "unexpected poses.csv header: $header" }
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val f = line.split(",")
                val tracked = f[itx].isNotEmpty()
                out.add(Row(
                    frameIndex = f[ix].toLong(),
                    timestampNs = f[its].toLong(),
                    position = if (tracked) DoubleArray(3) { f[itx + it].toDouble() } else null,
                    quaternion = if (tracked) DoubleArray(4) { f[iqx + it].toDouble() } else null,
                    // posecam-1 had no image column; those sessions always saved a frame.
                    imageSaved = iimage < 0 || f[iimage] == PoseCsv.IMAGE_SAVED,
                ))
            }
            return out
        }

        /**
         * Frame timestamps (elapsedRealtimeNanos) to wall-clock epoch milliseconds, via the
         * Record tap, which the manifest stamps on both clocks.
         */
        fun epochMs(timestampNs: Long, startWallMs: Long, recordPressedElapsedNs: Long): Long =
            startWallMs + Math.round((timestampNs - recordPressedElapsedNs) / 1e6)

        /**
         * Folder name for one exported demo: UTC so the phone and the analysis machine agree,
         * plus the session's random suffix and the segment number, so re-exports are
         * recognisable and two demos can never collide.
         */
        fun stem(epochMs: Long, sessionName: String, segmentIndex: Int): String {
            val when_ = SimpleDateFormat("yyyy-MM-dd-HH_mm_ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochMs))
            return "$when_-${sessionName.substringAfterLast('-')}-s${segmentIndex + 1}"
        }

        fun parseWallTime(iso: String): Long =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(iso)!!.time
    }
}
