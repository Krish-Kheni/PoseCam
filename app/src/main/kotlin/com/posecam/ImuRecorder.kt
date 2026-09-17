package com.posecam

import java.io.BufferedWriter
import java.io.File

/**
 * Writes imu.csv. Samples arrive on the sensor thread; start/stop come from the UI thread.
 * Timestamps are `SensorEvent.timestamp`, logged raw (no reordering or deduplication).
 *
 * While idle it keeps the last [preRollNs] of samples. Camera frames reach the app ~100 ms
 * after exposure, so the first recorded frame predates the Record tap; the pre-roll makes
 * the IMU cover it.
 */
class ImuRecorder(private val preRollNs: Long = 1_000_000_000L) {
    private class Sample(val timestampNs: Long, val sensor: String, val values: FloatArray)

    data class SensorStats(val count: Long, val firstTimestampNs: Long, val lastTimestampNs: Long) {
        val rateHz: Double
            get() = if (count < 2) 0.0 else (count - 1) * 1e9 / (lastTimestampNs - firstTimestampNs)
    }

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private val stats = linkedMapOf<String, SensorStats>()
    private var rowsSinceFlush = 0
    private val preRoll = ArrayDeque<Sample>()

    fun start(sessionDir: File) = synchronized(lock) {
        check(writer == null) { "Already recording" }
        stats.clear()
        rowsSinceFlush = 0
        val out = File(sessionDir, "imu.csv").bufferedWriter(bufferSize = 64 * 1024).apply {
            write(HEADER)
            newLine()
        }
        writer = out
        for (s in preRoll) write(out, s.timestampNs, s.sensor, s.values, s.values.size)
        preRoll.clear()
    }

    /**
     * [values] holds x, y, z and, for uncalibrated sensors, the bias estimate x, y, z.
     * Only the first [valueCount] entries are used (SensorEvent.values can be longer).
     */
    fun onSample(timestampNs: Long, sensor: String, values: FloatArray, valueCount: Int) {
        synchronized(lock) {
            val out = writer
            if (out == null) {
                keepForPreRoll(timestampNs, sensor, values, valueCount)
                return
            }
            write(out, timestampNs, sensor, values, valueCount)
        }
    }

    private fun keepForPreRoll(timestampNs: Long, sensor: String, values: FloatArray, valueCount: Int) {
        preRoll.addLast(Sample(timestampNs, sensor, values.copyOf(valueCount)))
        while (preRoll.first().timestampNs < timestampNs - preRollNs) preRoll.removeFirst()
    }

    private fun write(out: BufferedWriter, timestampNs: Long, sensor: String, values: FloatArray, valueCount: Int) {
        out.write(row(timestampNs, sensor, values, valueCount))
        out.newLine()
        stats[sensor] = stats[sensor]?.let { it.copy(count = it.count + 1, lastTimestampNs = timestampNs) }
            ?: SensorStats(1, timestampNs, timestampNs)
        if (++rowsSinceFlush >= FLUSH_EVERY_ROWS) {
            out.flush()
            rowsSinceFlush = 0
        }
    }

    /** Returns per-sensor stats for manifest.json, or null if not recording. */
    fun stop(): Map<String, Any?>? = synchronized(lock) {
        val out = writer ?: return null
        out.close()
        writer = null
        stats.mapValuesTo(linkedMapOf()) { (_, s) ->
            linkedMapOf(
                "samples" to s.count,
                "first_timestamp_ns" to s.firstTimestampNs,
                "last_timestamp_ns" to s.lastTimestampNs,
                "mean_rate_hz" to Math.round(s.rateHz * 10) / 10.0,
            )
        }
    }

    companion object {
        const val HEADER = "timestamp_ns,sensor,x,y,z,bias_x,bias_y,bias_z"
        const val ACCELEROMETER = "accel"
        const val GYROSCOPE_UNCALIBRATED = "gyro_uncal"
        const val GYROSCOPE = "gyro"
        private const val FLUSH_EVERY_ROWS = 500

        fun row(timestampNs: Long, sensor: String, values: FloatArray, valueCount: Int): String {
            require(valueCount == 3 || valueCount == 6) { "Expected 3 or 6 values, got $valueCount" }
            return buildString {
                append(timestampNs).append(',').append(sensor)
                for (i in 0 until 3) append(',').append(values[i])
                if (valueCount == 6) for (i in 3 until 6) append(',').append(values[i]) else append(",,,")
            }
        }
    }
}
