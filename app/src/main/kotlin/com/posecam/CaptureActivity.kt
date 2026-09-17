package com.posecam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CaptureActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusView: TextView
    private lateinit var recordButton: Button
    private lateinit var resolutionButton: Button
    private lateinit var focusButton: Button

    @Volatile private var session: Session? = null
    private var sessionMetadata: Map<String, Any?> = emptyMap()
    private var deviceInfo: Map<String, Any?> = emptyMap()
    private var userRequestedInstall = true
    private var permissionRequestPending = false
    private var fatalError: String? = null

    private lateinit var recorder: PoseRecorder
    private val backgroundRenderer = BackgroundRenderer()
    private val trackingGate = TrackingGate()
    private lateinit var imageGrabber: ImageGrabber
    private val imuRecorder = ImuRecorder()
    private lateinit var imuSource: ImuSource

    /** SystemClock.elapsedRealtimeNanos() minus the first recorded frame's timestamp. */
    @Volatile private var firstFrameAgeNs: Long? = null
    private var availableSizes: List<Pair<Int, Int>> = emptyList()
    private var currentSize: Pair<Int, Int>? = null
    /** Set when the camera config changes; the GL thread re-arms the tracking gate. */
    @Volatile private var resetTrackingGate = false

    // GL thread state.
    private var textureBoundTo: Session? = null
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportChanged = false
    private var lastLoggedState = ""
    private var lastUiUpdateTimestampNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        // A screen timeout pauses the ARCore session and ends the take.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surface)
        statusView = findViewById(R.id.status)
        recordButton = findViewById(R.id.record)
        resolutionButton = findViewById(R.id.resolution)
        focusButton = findViewById(R.id.focus)

        val root = getExternalFilesDir(null)
        if (root == null) {
            fatal("External storage is unavailable.")
            return
        }
        recorder = PoseRecorder(
            File(root, "captures"),
            JpegEncoder(JPEG_QUALITY),
            imageMetadata = mapOf(
                "format" to "jpeg",
                "jpeg_quality" to JPEG_QUALITY,
                "orientation" to "sensor native, not rotated for display",
            ),
        )
        imageGrabber = ImageGrabber(recorder.pool)
        imuSource = ImuSource(this, imuRecorder)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)

        recordButton.setOnClickListener { toggleRecording() }
        resolutionButton.setOnClickListener { cycleResolution() }
        focusButton.setOnClickListener { toggleFocusMode() }

        if (ArCoreApk.getInstance().checkAvailability(this) == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            fatal("This device does not support ARCore.")
        }
    }

    override fun onResume() {
        super.onResume()
        if (fatalError != null) return

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // onResume runs again while the dialog is up; a second request would cancel the first.
            if (!permissionRequestPending) {
                permissionRequestPending = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
            return
        }

        if (session == null && !createSession()) return

        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            showStatus("Camera not available. Close other camera apps and reopen PoseCam.")
            session = null
            return
        }
        surfaceView.onResume()
        imuSource.resume()
    }

    override fun onPause() {
        super.onPause()
        if (!::recorder.isInitialized) return
        // Stop the GL thread first so no frame arrives mid-stop.
        surfaceView.onPause()
        session?.pause()
        stopRecording()
        imuSource.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    /** Returns false if the session could not be created yet (install pending or error shown). */
    private fun createSession(): Boolean {
        try {
            when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    userRequestedInstall = false
                    return false
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val newSession = Session(this)
            logSupportedCameraConfigs(newSession)
            availableSizes = CameraConfigs.availableSizes(newSession)
            val (width, height) = savedSize()
            applyCameraConfig(newSession, CameraConfigs.select(newSession, width, height))

            newSession.configure(Config(newSession).apply {
                focusMode = savedFocusMode()
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                // Nothing below is needed for camera pose; disabling saves CPU and heat.
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                depthMode = Config.DepthMode.DISABLED
                instantPlacementMode = Config.InstantPlacementMode.DISABLED
                cloudAnchorMode = Config.CloudAnchorMode.DISABLED
            })

            session = newSession
            return true
        } catch (e: UnavailableException) {
            val message = when (e) {
                is UnavailableArcoreNotInstalledException,
                is UnavailableUserDeclinedInstallationException -> "Please install Google Play Services for AR."
                is UnavailableApkTooOldException -> "Please update Google Play Services for AR."
                is UnavailableSdkTooOldException -> "Please update PoseCam."
                is UnavailableDeviceNotCompatibleException -> "This device does not support ARCore."
                else -> "ARCore is unavailable: $e"
            }
            Log.e(TAG, "Session creation failed", e)
            fatal(message)
            return false
        }
    }

    /**
     * FIXED keeps intrinsics constant but focuses at roughly 1 m, blurring close scenes.
     * AUTO sharpens close range; refocusing can shift intrinsics, which intrinsics.json
     * and frame_metadata.csv record so it is visible offline.
     */
    private fun savedFocusMode(): Config.FocusMode =
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_AUTOFOCUS, true)) {
            Config.FocusMode.AUTO
        } else {
            Config.FocusMode.FIXED
        }

    private fun applyFocusMode(session: Session, mode: Config.FocusMode) {
        session.configure(session.config.apply { focusMode = mode })
        focusButton.text = if (mode == Config.FocusMode.AUTO) "Focus: auto" else "Focus: fixed"
        sessionMetadata = buildMetadata(session.cameraConfig)
        Log.i(TAG, "Focus mode: $mode")
    }

    /** Never mid-recording: refocusing can change intrinsics. */
    private fun toggleFocusMode() {
        val session = session ?: return
        if (recorder.isRecording) return
        val next = if (savedFocusMode() == Config.FocusMode.AUTO) Config.FocusMode.FIXED else Config.FocusMode.AUTO
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_AUTOFOCUS, next == Config.FocusMode.AUTO).apply()
        applyFocusMode(session, next)
    }

    /** Session must be paused (or not yet resumed). */
    private fun applyCameraConfig(session: Session, cameraConfig: CameraConfig) {
        session.cameraConfig = cameraConfig
        Log.i(TAG, "Chosen camera config: ${Json.write(CameraConfigs.describe(cameraConfig)).replace(Regex("\\s+"), " ")}")
        sessionMetadata = buildMetadata(cameraConfig)
        applyFocusMode(session, savedFocusMode())
        deviceInfo = DeviceInfo.collect(this, cameraConfig.cameraId, imuSource.describe())
        val size = cameraConfig.imageSize.width to cameraConfig.imageSize.height
        currentSize = size
        resolutionButton.text = "${size.first}×${size.second}"
        resolutionButton.isEnabled = availableSizes.size > 1
    }

    private fun savedSize(): Pair<Int, Int> {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return prefs.getInt(PREF_WIDTH, CameraConfigs.TARGET_WIDTH) to prefs.getInt(PREF_HEIGHT, CameraConfigs.TARGET_HEIGHT)
    }

    /** Switches to the next CPU image size. Intrinsics change with it, so never mid-recording. */
    private fun cycleResolution() {
        val session = session ?: return
        if (recorder.isRecording || availableSizes.size < 2) return
        val next = availableSizes[(availableSizes.indexOf(currentSize) + 1) % availableSizes.size]
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(PREF_WIDTH, next.first).putInt(PREF_HEIGHT, next.second).apply()

        surfaceView.onPause()
        session.pause()
        applyCameraConfig(session, CameraConfigs.select(session, next.first, next.second))
        resetTrackingGate = true
        try {
            session.resume()
        } catch (e: CameraNotAvailableException) {
            showStatus("Camera not available. Close other camera apps and reopen PoseCam.")
            return
        }
        surfaceView.onResume()
    }

    private fun logSupportedCameraConfigs(session: Session) {
        for (config in session.getSupportedCameraConfigs(CameraConfigFilter(session))) {
            Log.i(TAG, "Supported camera config (${config.facingDirection}): ${Json.write(CameraConfigs.describe(config)).replace(Regex("\\s+"), " ")}")
        }
    }

    private fun buildMetadata(cameraConfig: CameraConfig): Map<String, Any?> = linkedMapOf(
        "app_version" to packageVersion(packageName),
        "arcore_version" to packageVersion("com.google.ar.core"),
        "device" to linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "android_release" to Build.VERSION.RELEASE,
            "android_sdk" to Build.VERSION.SDK_INT,
        ),
        "camera_config" to CameraConfigs.describe(cameraConfig),
        "focus_mode" to savedFocusMode().name,
        "pose_source" to "Camera.getPose",
        "timestamp_source" to "Frame.getTimestamp",
    )

    private fun packageVersion(name: String): String? = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(name, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            stopRecording()
        } else {
            try {
                val pressedNs = SystemClock.elapsedRealtimeNanos()
                firstFrameAgeNs = null
                val dir = recorder.start(sessionMetadata, deviceInfo, pressedNs)
                imuRecorder.start(dir)
                Log.i(TAG, "Recording started: $dir")
                recordButton.text = getString(R.string.stop)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording", e)
                Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopRecording() {
        val imu = imuRecorder.stop() ?: emptyMap()
        val extra = linkedMapOf<String, Any?>(
            "imu" to imu,
            // Both should be small and positive (processing latency) if camera and IMU
            // timestamps share the elapsedRealtimeNanos clock.
            "clock_check" to linkedMapOf(
                "elapsed_realtime_minus_first_frame_timestamp_ns" to firstFrameAgeNs,
                "elapsed_realtime_minus_last_imu_timestamp_ns" to imuSource.lastSampleAgeNs,
            ),
        )
        recorder.stop(extra)?.let { onRecordingStopped(it) }
    }

    private fun onRecordingStopped(summary: PoseRecorder.Summary) {
        Log.i(TAG, "Recording stopped: $summary")
        recordButton.text = getString(R.string.record)
        Toast.makeText(
            this,
            "Saved ${summary.frameCount} poses, ${summary.imagesSaved} images" +
                (if (summary.imagesDropped > 0) ", ${summary.imagesDropped} dropped" else "") +
                (if (summary.poseJumps > 0) ", ${summary.poseJumps} POSE JUMP(S)" else ""),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        permissionRequestPending = false
        // Empty results mean the request was interrupted, not denied; onResume asks again.
        if (grantResults.isEmpty() || grantResults[0] == PackageManager.PERMISSION_GRANTED) return

        if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // "Don't ask again": the only way back is the system settings page.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        showStatus("PoseCam needs camera permission to record.")
    }

    // --- GLSurfaceView.Renderer (GL thread) ---

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        backgroundRenderer.createOnGlThread()
        textureBoundTo = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = session ?: return

        if (textureBoundTo !== session) {
            session.setCameraTextureName(backgroundRenderer.textureId)
            textureBoundTo = session
            viewportChanged = true
        }
        if (viewportChanged) {
            @Suppress("DEPRECATION")
            session.setDisplayGeometry(windowManager.defaultDisplay.rotation, viewportWidth, viewportHeight)
            viewportChanged = false
        }

        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during update", e)
            return
        }
        backgroundRenderer.draw(frame)

        val timestampNs = frame.timestamp
        if (timestampNs == 0L) return

        val camera = frame.camera
        val state = camera.trackingState
        val stateLabel = when (state) {
            TrackingState.PAUSED -> "PAUSED:${camera.trackingFailureReason}"
            else -> state.name
        }

        if (recorder.wantsFrame(timestampNs)) {
            if (firstFrameAgeNs == null) firstFrameAgeNs = SystemClock.elapsedRealtimeNanos() - timestampNs
            if (recorder.wantsIntrinsics()) {
                val k = camera.imageIntrinsics
                val f = k.focalLength
                val c = k.principalPoint
                val size = k.imageDimensions
                recorder.onIntrinsics(Intrinsics(f[0], f[1], c[0], c[1], size[0], size[1]))
            }
            val image = imageGrabber.grab(frame)
            val metadata = FrameMetadataReader.read(frame)
            if (state == TrackingState.TRACKING) {
                // Physical camera pose, not the display-oriented one.
                val pose = camera.pose
                recorder.onFrame(timestampNs, stateLabel, pose.translation, pose.rotationQuaternion, image, metadata)
            } else {
                recorder.onFrame(timestampNs, stateLabel, null, null, image, metadata)
            }
        }

        if (stateLabel != lastLoggedState) {
            Log.i(TAG, "Tracking state: $stateLabel")
            lastLoggedState = stateLabel
        }

        if (resetTrackingGate) {
            resetTrackingGate = false
            trackingGate.update(false, timestampNs)
        }
        val armed = trackingGate.update(state == TrackingState.TRACKING, timestampNs)
        if (timestampNs - lastUiUpdateTimestampNs >= UI_UPDATE_INTERVAL_NS) {
            lastUiUpdateTimestampNs = timestampNs
            updateUi(state, camera.trackingFailureReason, armed)
        }
    }

    private fun updateUi(state: TrackingState, reason: TrackingFailureReason, armed: Boolean) {
        val jumps = recorder.poseJumps
        val recording = recorder.isRecording
        val frames = recorder.recordedFrames
        val dropped = recorder.droppedImages
        val seconds = recorder.recordedDurationNs / 1e9
        val tracking = if (state == TrackingState.PAUSED && reason != TrackingFailureReason.NONE) "PAUSED ($reason)" else state.name
        val text = when {
            recording -> "● REC  %.1f s  ·  %d frames  ·  %d dropped%s\nTracking: %s"
                .format(seconds, frames, dropped, if (jumps > 0) "  ·  $jumps jump(s)" else "", tracking)
            armed -> "Ready to record\nTracking: $tracking"
            else -> "Move the phone slowly to start tracking…\nTracking: $tracking"
        }
        runOnUiThread {
            statusView.text = text
            recordButton.isEnabled = recording || armed
            resolutionButton.isEnabled = !recording && availableSizes.size > 1
            focusButton.isEnabled = !recording
        }
    }

    private fun showStatus(message: String) {
        statusView.text = message
    }

    private fun fatal(message: String) {
        fatalError = message
        showStatus(message)
        recordButton.isEnabled = false
    }

    private companion object {
        const val TAG = "PoseCam"
        const val CAMERA_PERMISSION_REQUEST = 1
        const val UI_UPDATE_INTERVAL_NS = 200_000_000L
        const val JPEG_QUALITY = 90
        const val PREFS = "posecam"
        const val PREF_WIDTH = "cpu_image_width"
        const val PREF_HEIGHT = "cpu_image_height"
        const val PREF_AUTOFOCUS = "autofocus"
    }
}
