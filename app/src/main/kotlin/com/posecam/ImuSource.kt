package com.posecam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Streams accelerometer and uncalibrated gyroscope samples into an [ImuRecorder] on a
 * dedicated thread. Registered for the whole time the screen is active, so the IMU is
 * already warmed up when recording starts; the recorder ignores samples while idle.
 */
class ImuSource(context: Context, private val recorder: ImuRecorder) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null

    /** SystemClock.elapsedRealtimeNanos() minus the latest event timestamp: a clock sanity check. */
    @Volatile var lastSampleAgeNs: Long? = null
        private set

    fun resume() {
        if (thread != null) return
        val t = HandlerThread("PoseCam-IMU").apply { start() }
        thread = t
        val handler = Handler(t.looper)
        for (sensor in listOfNotNull(accelerometer, gyroscope)) {
            if (!sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)) {
                Log.w(TAG, "Could not register ${sensor.name}")
            }
        }
    }

    fun pause() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (name, count) = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> ImuRecorder.ACCELEROMETER to 3
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> ImuRecorder.GYROSCOPE_UNCALIBRATED to 6
            Sensor.TYPE_GYROSCOPE -> ImuRecorder.GYROSCOPE to 3
            else -> return
        }
        lastSampleAgeNs = SystemClock.elapsedRealtimeNanos() - event.timestamp
        recorder.onSample(event.timestamp, name, event.values, count)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** Sensor details for device.json. */
    fun describe(): Map<String, Any?> = linkedMapOf(
        ImuRecorder.ACCELEROMETER to accelerometer?.let(::describe),
        (if (gyroscope?.type == Sensor.TYPE_GYROSCOPE) ImuRecorder.GYROSCOPE else ImuRecorder.GYROSCOPE_UNCALIBRATED)
            to gyroscope?.let(::describe),
    )

    private fun describe(sensor: Sensor): Map<String, Any?> = linkedMapOf(
        "name" to sensor.name,
        "vendor" to sensor.vendor,
        "version" to sensor.version,
        "units" to if (sensor.type == Sensor.TYPE_ACCELEROMETER) "m/s^2" else "rad/s",
        "resolution" to sensor.resolution,
        "maximum_range" to sensor.maximumRange,
        "min_delay_us" to sensor.minDelay,
        "max_delay_us" to sensor.maxDelay,
    )

    private companion object {
        const val TAG = "PoseCam"
    }
}
