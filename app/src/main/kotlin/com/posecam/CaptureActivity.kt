package com.posecam

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.posecam.core.sync.CloudSync
import com.posecam.core.sync.CloudUiText
import com.posecam.core.sync.Pipe
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CaptureActivity : Activity(), GLSurfaceView.Renderer {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusView: TextView
    private lateinit var recordButton: Button
    private lateinit var resolutionButton: Button
    private lateinit var focusButton: Button
    private lateinit var sessionsButton: Button

    @Volatile private var session: Session? = null
    private var sessionMetadata: Map<String, Any?> = emptyMap()
    private var deviceInfo: Map<String, Any?> = emptyMap()
    private var userRequestedInstall = true
    private var permissionRequestPending = false
    private var fatalError: String? = null

    private lateinit var recorder: PoseRecorder
    private val backgroundRenderer = BackgroundRenderer()
    private val trackingGate = TrackingGate()
    /** Watches for relocalization jumps while idle, so the gate does not arm mid-settling. */
    private val idleJumpDetector = PoseJumpDetector()
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
    private var lastAlertedJumps = 0
    private var wasTrackingWhileRecording = true
    // Frame rate over the last few seconds: a slow take is unusable to the consumer, whose
    // action labels are a fixed number of frames apart.
    private var fpsWindowStartNs = 0L
    private var fpsWindowStartFrames = 0L
    private var liveFps = 30.0
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
        sessionsButton = findViewById(R.id.sessions)
        sessionsButton.setOnClickListener { startActivity(Intent(this, SessionsActivity::class.java)) }

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
            // Null when cloud upload is not configured, which makes it a no-op. A cloud problem must never
            // stop a recording from starting, hence runCatching.
            listener = runCatching { CloudSync.get(this).sessionListener }.getOrNull(),
        )
        imageGrabber = ImageGrabber(recorder.pool)
        imuSource = ImuSource(this, imuRecorder)
        // Rebuild/resume the upload queue (also adopts recordings made before cloud upload existed). No-op when off.
        runCatching { CloudSync.get(this).recoverQueue() }
        runCatching { CloudSync.get(this).refreshPipes() }

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
        if (needsFirstSignIn()) {
            startActivity(Intent(this, AuthActivity::class.java).putExtra(AuthActivity.EXTRA_REQUIRED, true))
            return
        }

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
        // ARCore re-settles after a pause: require the 3 s of stable tracking again.
        resetTrackingGate = true
        idleJumpDetector.reset()
    }

    /**
     * Cloud upload needs an account, so the very first launch asks for one. After that recording never waits for a
     * sign-in screen: an expired session is a banner in Recordings, because a collector in the field may be offline.
     */
    private fun needsFirstSignIn(): Boolean = runCatching {
        val sync = CloudSync.get(this)
        sync.config.enabled && !sync.auth.current().hasEverSignedIn
    }.getOrDefault(false)

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
        if (next == Config.FocusMode.AUTO) {
            setFocusMode(session, next)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Turn autofocus off?")
            .setMessage("The team records with autofocus on. Fixed focus freezes the lens wherever it is now, " +
                "which blurs anything at a different distance, and such recordings are not exported.")
            .setPositiveButton("Turn off") { _, _ -> setFocusMode(session, next) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setFocusMode(session: Session, next: Config.FocusMode) {
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
        AlertDialog.Builder(this)
            .setTitle("Change capture size?")
            .setMessage("The team records at ${CameraConfigs.TARGET_WIDTH}×${CameraConfigs.TARGET_HEIGHT}. " +
                "A recording at ${next.first}×${next.second} cannot be mixed with the others and will not be exported.")
            .setPositiveButton("Change") { _, _ -> applyResolution(next) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyResolution(next: Pair<Int, Int>) {
        val session = session ?: return
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
            val freeGb = freeGb()
            if (freeGb < MIN_FREE_GB) {
                // With cloud upload on, recordings already safe in the cloud can be reclaimed automatically.
                val cleaning = runCatching { CloudSync.get(this) }.getOrNull()?.takeIf { it.config.enabled }
                cleaning?.reclaimStorage()
                AlertDialog.Builder(this)
                    .setTitle("Not enough space")
                    .setMessage("Only %.1f GB free, about %.0f minutes of recording. Export and delete old recordings first."
                        .format(freeGb, 60 * freeGb / GB_PER_HOUR) +
                        if (cleaning != null) " Recordings that are already synced to the cloud are being removed to make room: try again in a moment." else "")
                    .setPositiveButton(R.string.close, null)
                    .show()
                return
            }
            try {
                val pressedNs = SystemClock.elapsedRealtimeNanos()
                firstFrameAgeNs = null
                lastAlertedJumps = 0
                wasTrackingWhileRecording = true
                fpsWindowStartNs = 0
                liveFps = 30.0
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
        val verdict = TakeVerdict.of(
            summary.seconds, summary.frameCount, summary.trackedFrames, summary.poseJumps,
            summary.longestGapSeconds, summary.imagesDropped,
        )
        if (verdict.redo) alert(REDO_PATTERN)
        val body = buildString {
            append(verdict.detail)
            if (verdict.reasons.isNotEmpty()) {
                append("\n\n")
                append(verdict.reasons.joinToString("\n") { "• $it" })
            }
            append("\n\nSaved as ")
            append(summary.directory.name)
            cloudNote(verdict.redo)?.let { append("\n\n").append(it) }
        }
        TakeResultDialog.show(this, verdict.headline, body, verdict.redo, cloudEnabled(), availablePipes()) { pipe -> onPipeChosen(summary, pipe) }
    }

    /** The backend's pipes as last cached on this phone; the three built-in ones if none was ever fetched. */
    private fun availablePipes(): List<Pipe> = runCatching { CloudSync.get(this).pipes.current() }.getOrDefault(Pipe.DEFAULTS)

    private fun cloudEnabled(): Boolean = runCatching { CloudSync.get(this).config.enabled }.getOrDefault(false)

    /** The collector chose a pipe: this take is queued for upload (earlier recordings were already uploading). */
    private fun onPipeChosen(summary: PoseRecorder.Summary, pipe: Pipe) {
        val message = runCatching {
            val sync = CloudSync.get(this)
            sync.coordinator.choosePipe(summary.directory.name, pipe)
            val policy = sync.settings.policy
            CloudUiText.pipeChosenMessage(pipe, policy, sync.networkStatus.satisfies(policy))
        }.getOrNull() ?: return
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        // Exports are automatic and wait for the rotation: asked here once, never per take.
        runCatching { ExportRotationDialog.askOnceIfUnset(this, CloudSync.get(this)) }
    }

    /** One line for the end-of-take dialog when cloud upload is on; null (nothing added) when it is off. */
    private fun cloudNote(redo: Boolean): String? = runCatching {
        if (!CloudSync.get(this).config.enabled) return@runCatching null
        if (redo) "Not uploaded. You can still file it under a pipe later, in Recordings."
        else "Choose a pipe to file this take under: it uploads once you do."
    }.getOrNull()

    private var handlingWriteFailure = false

    private fun onWriteFailure(failure: Throwable) {
        if (handlingWriteFailure || !recorder.isRecording) return
        handlingWriteFailure = true
        Log.e(TAG, "Write failed, stopping the recording", failure)
        alert(REDO_PATTERN)
        stopRecording()
        AlertDialog.Builder(this)
            .setTitle("Recording stopped: could not write")
            .setMessage("The phone may be out of space (%.1f GB free). What was recorded up to that point is saved."
                .format(freeGb()))
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener { handlingWriteFailure = false }
            .show()
    }

    /** Vibrates: the phone is on a gripper, pointed away, and nobody is watching the screen. */
    private fun alert(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
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
        } catch (e: Throwable) {
            Log.e(TAG, "session.update() failed", e)
            if (recorder.isRecording) runOnUiThread { onWriteFailure(e) }
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

        // Tell the collector the moment something goes wrong, so nobody has to watch a counter.
        if (recorder.isRecording) {
            val tracking = state == TrackingState.TRACKING
            if (!tracking && wasTrackingWhileRecording) alert(LOST_TRACKING_PATTERN)
            wasTrackingWhileRecording = tracking
            val jumps = recorder.poseJumps
            if (jumps > lastAlertedJumps) {
                lastAlertedJumps = jumps
                alert(JUMP_PATTERN)
            }
        }

        if (resetTrackingGate) {
            resetTrackingGate = false
            trackingGate.update(false, timestampNs)
        }
        val jumped = if (state == TrackingState.TRACKING) {
            val pose = camera.pose
            idleJumpDetector.onTrackedFrame(-1, timestampNs, pose.translation, pose.rotationQuaternion) != null
        } else {
            idleJumpDetector.onUntrackedFrame()
            false
        }
        if (recorder.isRecording) {
            if (fpsWindowStartNs == 0L) {
                fpsWindowStartNs = timestampNs
                fpsWindowStartFrames = recorder.recordedFrames
            } else if (timestampNs - fpsWindowStartNs >= FPS_WINDOW_NS) {
                liveFps = (recorder.recordedFrames - fpsWindowStartFrames) * 1e9 / (timestampNs - fpsWindowStartNs)
                fpsWindowStartNs = timestampNs
                fpsWindowStartFrames = recorder.recordedFrames
            }
            // A failed write (a full disk) ends the take cleanly instead of crashing the loop.
            recorder.writeFailure?.let { failure ->
                runOnUiThread { onWriteFailure(failure) }
            }
        }

        val armed = trackingGate.update(state == TrackingState.TRACKING, timestampNs, jumped)
        if (timestampNs - lastUiUpdateTimestampNs >= UI_UPDATE_INTERVAL_NS) {
            lastUiUpdateTimestampNs = timestampNs
            updateUi(state, camera.trackingFailureReason, armed)
        }
    }

    private fun freeGb(): Double = (getExternalFilesDir(null)?.usableSpace ?: 0L) / 1e9

    private fun updateUi(state: TrackingState, reason: TrackingFailureReason, armed: Boolean) {
        val jumps = recorder.poseJumps
        val recording = recorder.isRecording
        val frames = recorder.recordedFrames
        val dropped = recorder.droppedImages
        val seconds = recorder.recordedDurationNs / 1e9
        val tracking = if (state == TrackingState.PAUSED && reason != TrackingFailureReason.NONE) "PAUSED ($reason)" else state.name
        val text = when {
            recording -> "● REC  %.1f s  ·  %d frames  ·  %.1f fps%s%s\nTracking: %s".format(
                seconds, frames, liveFps,
                if (dropped > 0) "  ·  $dropped dropped" else "",
                if (jumps > 0) "  ·  $jumps jump(s)" else "", tracking)
            armed -> "Ready to record  ·  %.0f min of space left\nTracking: %s".format(60 * freeGb() / GB_PER_HOUR, tracking)
            state == TrackingState.TRACKING -> "Stabilizing, keep moving slowly…\nTracking: $tracking"
            else -> "Move the phone slowly to start tracking…\nTracking: $tracking"
        }
        runOnUiThread {
            statusView.text = text
            statusView.setBackgroundColor(
                if (recording && state != TrackingState.TRACKING) 0xCCB00020.toInt() else 0x99000000.toInt()
            )
            recordButton.isEnabled = recording || armed
            resolutionButton.isEnabled = !recording && availableSizes.size > 1
            focusButton.isEnabled = !recording
            val offProtocol = currentSize != (CameraConfigs.TARGET_WIDTH to CameraConfigs.TARGET_HEIGHT) ||
                savedFocusMode() != Config.FocusMode.AUTO
            resolutionButton.setTextColor(if (offProtocol) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt())
            focusButton.setTextColor(if (offProtocol) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt())
            sessionsButton.isEnabled = !recording
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

    internal companion object {
        const val TAG = "PoseCam"
        const val CAMERA_PERMISSION_REQUEST = 1
        const val UI_UPDATE_INTERVAL_NS = 200_000_000L
        // 75 halves the storage a take needs and costs less fidelity than the H.264 export
        // step already does at the 256x256 the consumer trains on (measured on real frames).
        const val JPEG_QUALITY = 75
        const val FPS_WINDOW_NS = 3_000_000_000L
        const val PREFS = "posecam"
        const val PREF_WIDTH = "cpu_image_width"
        const val PREF_HEIGHT = "cpu_image_height"
        const val PREF_AUTOFOCUS = "autofocus"
        // ~7 GB/h at 640x480, so 4 GB is about half an hour of headroom.
        const val MIN_FREE_GB = 4.0
        const val GB_PER_HOUR = 7.0
        // Distinct rhythms so they can be told apart without looking.
        val LOST_TRACKING_PATTERN = longArrayOf(0, 400)
        val JUMP_PATTERN = longArrayOf(0, 120, 100, 120, 100, 120)
        val REDO_PATTERN = longArrayOf(0, 250, 150, 250)
    }
}
