# PoseCam — Build Plan

An AnySense-style capture app for Android: press record, get synchronized RGB frames and 6-DoF camera poses out the other side. Kotlin + ARCore, local-only, no cloud.

**Target device:** Samsung Galaxy
**Scope:** RGB + pose + intrinsics + IMU. No depth, no meshing, no viewer, no upload.

---

## Design decisions (and why)

**Log poses live, never via ARCore's Recording & Playback API.**
That API stores an MP4 of raw sensor data and re-solves the AR state on replay. ARCore explicitly warns that playback can yield different poses than were seen during recording, and different results between playback runs. So the trajectory is not stored, it's recomputed — useless as ground truth. We write `pose` to disk inside the live frame loop instead. This is what every serious logger does.

**Frames before video.**
Phase 2 writes one JPEG per frame rather than an MP4. Reasons: each file carries its own exact timestamp so sync is correct by construction; no MediaCodec/MediaMuxer/EGL plumbing; and you'll decode to frames downstream anyway. MP4 is Phase 5, an optimization for when disk or framerate actually bites.

**Timestamps are the whole ballgame.**
`Frame.getTimestamp()` is nanoseconds on the same clock as Camera2's `SENSOR_TIMESTAMP`. Every artifact we write is keyed by that number. If pose↔image alignment is ever wrong, it will be because something used `System.currentTimeMillis()` instead. Don't.

---

## Output format

One session = one folder, under app-specific external storage
(`getExternalFilesDir(null)/captures/`), so no scoped-storage fights.

```
capture-20260916T143052-a3f9c1/
├── frames/
│   ├── 000000_1663341052123456789.jpg
│   ├── 000001_1663341052156789012.jpg
│   └── ...
├── poses.csv
├── imu.csv
├── intrinsics.json
├── device.json
└── manifest.json
```

**poses.csv** — one row per tracked frame:

```
frame_index,timestamp_ns,tx,ty,tz,qx,qy,qz,qw,tracking_state
```

`tracking_state` is `TRACKING` / `PAUSED` / `STOPPED`, plus the
`TrackingFailureReason` when paused. Keep the untracked rows — don't
silently drop frames, or your indices lie.

**imu.csv** — `timestamp_ns,sensor,x,y,z` for accelerometer and gyroscope,
registered at `SENSOR_DELAY_FASTEST` (~100–200 Hz on Galaxy hardware).

**intrinsics.json** — from `camera.getImageIntrinsics()`: focal length,
principal point, image dimensions. Write once at session start, and again
at the end to assert it didn't change.

**manifest.json** — format version string, session id, start/stop wall
time, frame count, app version, ARCore version. Makes the folder
self-describing so you can still read it in six months.

---

## Coordinate conventions — write this into a doc file on day one

ARCore world frame: **+Y is up**, aligned to gravity. **−Z** points in the
direction the camera faced when the session started, perpendicular to
gravity. **+X** follows from the right-hand rule.

So, as discussed: position starts at `(0,0,0)`, but the starting rotation
is **not identity** unless the phone happened to be held perfectly upright
at session start. Only heading is zeroed to the initial direction; pitch
and roll are absolute against gravity.

Use `camera.getPose()` (physical sensor frame), **not**
`camera.getDisplayOrientedPose()` — the latter bakes in screen rotation
and is for rendering only.

If you need poses relative to the phone's exact starting orientation,
post-process: `T_rel[i] = inverse(T[0]) · T[i]`. Do this offline, not in
the app, so the raw log stays raw.

---

## Phases

### Phase 0 — Groundwork (half a day)

- New Android Studio project, Kotlin, min SDK 24.
- Add `com.google.ar.core:core` (latest stable).
- `ArCoreApk.checkAvailability()` on launch; route the user to install
  "Google Play Services for AR" if it's missing. Your Galaxy almost
  certainly supports ARCore, but verify the exact model against Google's
  supported-devices list before writing anything else.
- Camera permission flow.
- `FLAG_KEEP_SCREEN_ON` on the capture activity — a screen-off kills the
  session mid-take.

**Done when:** app opens, shows the camera feed through an ARCore session,
and logs "TRACKING" to logcat once you move it around.

### Phase 1 — Pose logging only (one day)

The core loop, in your `GLSurfaceView.Renderer.onDrawFrame`:

```kotlin
val frame = session.update()
val cam = frame.camera
val state = cam.trackingState

if (state == TrackingState.TRACKING) {
    val p = cam.pose
    val t = p.translation          // FloatArray(3)
    val q = p.rotationQuaternion   // FloatArray(4) — x, y, z, w
    poseWriter.append(
        "$frameIndex,${frame.timestamp}," +
        "${t[0]},${t[1]},${t[2]}," +
        "${q[0]},${q[1]},${q[2]},${q[3]},TRACKING\n"
    )
} else {
    poseWriter.append("$frameIndex,${frame.timestamp},,,,,,,, $state\n")
}
frameIndex++
```

Buffer writes (a `BufferedWriter` flushed every ~100 rows); do not open a
file handle per frame.

Add start/stop buttons and session-folder creation.

**Done when:** you walk a closed loop around a room, and the trajectory
plot (Phase 4) comes back looking like the shape you actually walked.
Do this before writing any image code — if pose is wrong, nothing else
matters.

### Phase 2 — Frame capture (one to two days)

In the same loop, after the pose write:

```kotlin
frame.acquireCameraImage().use { image ->   // YUV_420_888
    // convert to JPEG, write to frames/%06d_%d.jpg
}
```

Two hard rules:

1. **Always close the image.** `acquireCameraImage()` draws from a small
   pool; leak a couple and the session stalls with
   `NotYetAvailableException` forever. Use `.use { }`.
2. **Never encode on the GL thread.** Hand the YUV planes to a
   single-threaded background executor with a bounded queue. If the queue
   is full, drop the frame and *record the drop* in poses.csv rather than
   blocking the tracker.

YUV→JPEG: `YuvImage.compressToJpeg()` is the easy path and fast enough at
~15–20 fps. If you need more, switch to `libyuv` or an `ImageWriter` into
a hardware JPEG encoder — but only if measurement says you need it.

**Done when:** frame count matches pose count (minus recorded drops), and
timestamps in filenames match timestamps in poses.csv exactly.

### Phase 3 — IMU + metadata (half a day)

- `SensorManager` listeners for `TYPE_ACCELEROMETER` and
  `TYPE_GYROSCOPE_UNCALIBRATED` (uncalibrated so you get raw values plus
  the bias estimate separately).
- Sensor events give `event.timestamp` in nanoseconds on the same
  monotonic clock. Good — no conversion needed.
- Write `intrinsics.json`, `device.json` (model, Android version, ARCore
  version), `manifest.json`.

### Phase 4 — Validation tooling (half a day, Python on your PC)

This isn't optional; it's how you find out the data is wrong before you
collect a hundred episodes.

- `plot_trajectory.py` — 3D plot of tx/ty/tz from poses.csv. Walk a
  square, check you get a square.
- `check_sync.py` — assert monotonic timestamps, report gaps > 2× the
  median interval, confirm every frame file has a matching pose row.
- `overlay_check.py` — render a small axis triad projected with the
  intrinsics onto a few sampled frames. If the axes look glued to the
  world as the camera moves, pose and image are aligned.

### Phase 5 — Optional: MP4 instead of frames

Only if disk or framerate forces it. Render ARCore's background OES
texture to a `MediaCodec` input surface via EGL, mux with `MediaMuxer`.
The MARS logger / VideoIMUCapture codebases (grafika-derived) are the
reference implementations — read them rather than inventing it.
Keep writing poses.csv exactly as before; the MP4 replaces `frames/`,
nothing else changes.

---

## Samsung-specific gotchas

- **Stabilization must be off.** OIS and Samsung's VDIS / "Super Steady"
  silently invalidate your camera intrinsics — the effective principal
  point moves frame to frame and no pipeline will reconstruct correctly.
  ARCore normally requests stabilization off for its own session, but
  verify it rather than assuming: capture a static scene, wave the phone,
  and check whether the image counter-rotates against the motion.
- **Battery optimization.** Samsung's aggressive background management can
  throttle or kill long captures. Exempt the app in settings during
  development.
- **Thermal throttling.** Sustained ARCore + JPEG encoding heats a Galaxy
  fast; framerate will sag after several minutes. Log actual inter-frame
  intervals so you can see it happening rather than guessing.
- **Camera config.** `session.getSupportedCameraConfigs()` then
  `setCameraConfig()` — pick your resolution explicitly rather than taking
  whatever default the device hands you, so sessions are comparable.

## General gotchas

- Tracking can **jump**. ARCore relocalizes and corrects drift, so
  consecutive poses occasionally teleport. Don't smooth it away in the
  app — log it, detect it offline by thresholding inter-frame translation.
- Each session has its **own origin**. Two recordings are not in a shared
  frame unless you align them yourself.
- The first ~1–2 seconds of any session are garbage while ARCore
  initializes. Either discard them offline or add a "wait for stable
  tracking" gate before the record button arms.

---

## Kickoff prompt for Claude Code

Paste this into Claude Code in an empty project directory:

> I'm building an Android app in Kotlin that records RGB frames and
> 6-DoF camera poses from ARCore, for robotics data collection. Target
> device is a Samsung Galaxy. No depth, no 3D reconstruction, no
> networking — local capture only.
>
> Set up Phase 0 and Phase 1: an Android Studio project with ARCore,
> camera permissions, an availability check for Google Play Services for
> AR, a GLSurfaceView-based capture activity showing the camera feed, and
> start/stop buttons that write a `poses.csv` file into a timestamped
> session folder under `getExternalFilesDir(null)/captures/`.
>
> The pose row format is:
> `frame_index,timestamp_ns,tx,ty,tz,qx,qy,qz,qw,tracking_state`
> using `frame.timestamp`, `camera.pose.translation` and
> `camera.pose.rotationQuaternion`. Use `camera.getPose()`, not
> `getDisplayOrientedPose()`. Keep rows for untracked frames with empty
> pose fields and the tracking state recorded. Buffer the writer, don't
> open a file per frame.
>
> Don't add image capture yet — I want to validate the trajectory first.

Then, once the trajectory plot looks right, ask it for Phase 2 with the
two hard rules above stated explicitly (always `.use { }` the acquired
image; encode off the GL thread with a bounded queue and recorded drops).

---

## Reference implementations worth reading

- `PyojinKim/ARCore-Data-Logger` — the minimal pose+pointcloud logger.
  Closest thing to a Phase 1 reference.
- `3dlg-hcvc/multiscan` (Android scanner app) — full ARCore capture with
  per-frame pose and intrinsics JSON. Heavier than you need, but it's
  been run on real hardware.
- `OSUPCVLab/mobile-ar-sensor-logger` and
  `davidgillsjo/videoimucapture-android` — the video + IMU + MediaCodec
  reference for Phase 5.