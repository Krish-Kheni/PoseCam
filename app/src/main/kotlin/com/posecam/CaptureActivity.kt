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
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
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

    @Volatile private var session: Session? = null
    private var sessionMetadata: Map<String, Any?> = emptyMap()
    private var userRequestedInstall = true
    private var permissionRequestPending = false
    private var fatalError: String? = null

    private lateinit var recorder: PoseRecorder
    private val backgroundRenderer = BackgroundRenderer()
    private val trackingGate = TrackingGate()
    private lateinit var imageGrabber: ImageGrabber

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

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)

        recordButton.setOnClickListener { toggleRecording() }

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
    }

    override fun onPause() {
        super.onPause()
        if (!::recorder.isInitialized) return
        // Stop the GL thread first so no frame arrives mid-stop.
        surfaceView.onPause()
        session?.pause()
        recorder.stop()?.let { onRecordingStopped(it) }
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
            val cameraConfig = CameraConfigs.select(newSession)
            newSession.cameraConfig = cameraConfig
            Log.i(TAG, "Chosen camera config: ${Json.write(CameraConfigs.describe(cameraConfig)).replace(Regex("\\s+"), " ")}")

            newSession.configure(Config(newSession).apply {
                // Fixed focus keeps intrinsics stable across the recording.
                focusMode = Config.FocusMode.FIXED
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                // Nothing below is needed for camera pose; disabling saves CPU and heat.
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                depthMode = Config.DepthMode.DISABLED
                instantPlacementMode = Config.InstantPlacementMode.DISABLED
                cloudAnchorMode = Config.CloudAnchorMode.DISABLED
            })

            sessionMetadata = buildMetadata(cameraConfig)
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

    private fun logSupportedCameraConfigs(session: Session) {
        for (config in session.getSupportedCameraConfigs(CameraConfigFilter(session))) {
            Log.i(TAG, "Supported camera config (${config.facingDirection}): ${Json.write(CameraConfigs.describe(config)).replace(Regex("\\s+"), " ")}")
        }
    }

    private fun buildMetadata(cameraConfig: com.google.ar.core.CameraConfig): Map<String, Any?> = linkedMapOf(
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
            recorder.stop()?.let { onRecordingStopped(it) }
        } else {
            try {
                val dir = recorder.start(sessionMetadata)
                Log.i(TAG, "Recording started: $dir")
                recordButton.text = getString(R.string.stop)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording", e)
                Toast.makeText(this, "Could not start recording: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onRecordingStopped(summary: PoseRecorder.Summary) {
        Log.i(TAG, "Recording stopped: $summary")
        recordButton.text = getString(R.string.record)
        Toast.makeText(
            this,
            "Saved ${summary.frameCount} poses, ${summary.imagesSaved} images (${summary.imagesDropped} dropped)",
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
            val image = imageGrabber.grab(frame)
            if (state == TrackingState.TRACKING) {
                // Physical camera pose, not the display-oriented one.
                val pose = camera.pose
                recorder.onFrame(timestampNs, stateLabel, pose.translation, pose.rotationQuaternion, image)
            } else {
                recorder.onFrame(timestampNs, stateLabel, null, null, image)
            }
        }

        if (stateLabel != lastLoggedState) {
            Log.i(TAG, "Tracking state: $stateLabel")
            lastLoggedState = stateLabel
        }

        val armed = trackingGate.update(state == TrackingState.TRACKING, timestampNs)
        if (timestampNs - lastUiUpdateTimestampNs >= UI_UPDATE_INTERVAL_NS) {
            lastUiUpdateTimestampNs = timestampNs
            updateUi(state, camera.trackingFailureReason, armed)
        }
    }

    private fun updateUi(state: TrackingState, reason: TrackingFailureReason, armed: Boolean) {
        val recording = recorder.isRecording
        val frames = recorder.recordedFrames
        val dropped = recorder.droppedImages
        val seconds = recorder.recordedDurationNs / 1e9
        val tracking = if (state == TrackingState.PAUSED && reason != TrackingFailureReason.NONE) "PAUSED ($reason)" else state.name
        val text = when {
            recording -> "● REC  %.1f s  ·  %d frames  ·  %d dropped\nTracking: %s".format(seconds, frames, dropped, tracking)
            armed -> "Ready to record\nTracking: $tracking"
            else -> "Move the phone slowly to start tracking…\nTracking: $tracking"
        }
        runOnUiThread {
            statusView.text = text
            recordButton.isEnabled = recording || armed
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
    }
}
