# PoseCam

Android app (Kotlin + ARCore) that records 6-DoF camera poses, and later RGB frames
and IMU data, for robotics data collection. Local capture only.

- Plan: [docs/PLAN.md](docs/PLAN.md)
- Coordinate conventions: [docs/COORDINATES.md](docs/COORDINATES.md)
- Setting up a new phone (install, checks): [docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md)

## Build and install

Requires the Android SDK (`ANDROID_HOME`) and a phone with USB debugging and
[ARCore support](https://developers.google.com/ar/devices).

```bash
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Use

1. Open PoseCam and allow camera access.
2. Move the phone slowly until the status bar says **Ready to record**
   (tracking has been stable for 1 s).
3. **Record**, capture, **Stop**.

## Get the data

On the phone, **Recordings** lists sessions and can share a zip, save it to
`Downloads/PoseCam/`, or delete it (see [docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md)).
With adb:

```bash
tools/pull_captures.sh                          # copies sessions into ./data/
uv run tools/check_sync.py data/capture-…            # timing, pose/image/IMU consistency, OIS/focus
uv run tools/plot_trajectory.py data/capture-…        # stats + plot (--save writes trajectory.png)
uv run tools/overlay_check.py data/capture-…          # world-fixed axes drawn on frames (--gif for motion)
uv run tools/check_imu_alignment.py data/capture-…    # camera↔IMU axes + time offset (rotate the phone)
uv run tools/calibrate_camera.py data/capture-… --pattern 9x6 --square 0.025   # checkerboard calibration
uv run tools/export_anysense.py data/capture-…        # AnySense-style folder (MP4 + AR_Pose txt)
```

## Exporting for AnySense-based pipelines

`tools/export_anysense.py` writes the folder NYU's AnySense iPhone app produces, so
downstream code written for AnySense can consume PoseCam data:

```
exports/2026-09-17-14_58_35/
├── RGB_2026-09-17-14_58_35.mp4       H.264, portrait (camera frame rotated 90°), 30 fps
├── AR_Pose_2026-09-17-14_58_35.txt   one line per frame: "<epoch_ms>" ,qx,qy,qz,qw,tx,ty,tz
└── posecam_export.json               provenance (not part of AnySense's format)
```

Pose line N is video frame N; the consumer (`cap_tools/convert.py`) aligns by index
only. Interior frames are never deleted: tracking gaps of up to `--hold-max-frames`
(default 15) get interpolated poses, longer gaps end the segment. By default the longest
segment without pose jumps or long gaps is exported (`--all` for everything,
`--segment N` to pick one). `--rotate` sets the image rotation (default 90° = upright
portrait for a phone held upright; `--rotate 0` for a landscape gripper mount); the
consumer's gripper detector needs the **jaws pointing up** in the exported video, so
check one frame per mount. `--size 720x960` matches AnySense's video size,
`--vfr` keeps real frame timing. Poses are raw `Camera.getPose()`: no re-basing, no
smoothing, same OpenGL camera convention as ARKit (verified: forward motion is −Z).

On a new phone model, run `check_imu_alignment.py` on a recording with plenty of
rotation before using its IMU data.

## Output (format `posecam-5`)

```
capture-20260916T213140-9c433c/
├── frames/          000000_<timestamp_ns>.jpg, ...
├── poses.csv        frame_index,timestamp_ns,tx,ty,tz,qx,qy,qz,qw,tracking_state,image
├── frame_metadata.csv  per-frame exposure, ISO, focus distance, OIS mode, rolling shutter skew
├── imu.csv          timestamp_ns,sensor,x,y,z,bias_x,bias_y,bias_z
├── intrinsics.json  fx, fy, cx, cy, width, height (+ whether they changed)
├── device.json      phone, Camera2 characteristics, IMU sensor details
└── manifest.json    session info, versions, camera config, image/IMU stats, clock check
```

- One row per distinct camera frame, including untracked frames (empty pose fields).
- `image` is `saved` (a JPEG named `{frame_index:06d}_{timestamp_ns}.jpg` exists) or
  `dropped:<reason>`: `queue_full` (encoder falling behind), `not_yet_available`,
  `deadline_exceeded`, `resources_exhausted`.
- Images are the camera's CPU image in sensor-native orientation (landscape, not
  rotated for the screen), matching the orientation of the camera pose.
- `imu.csv` interleaves `accel` and `gyro_uncal` samples in arrival order. The bias
  columns are empty for `accel`. The axes are the phone's, not the camera's: see
  [COORDINATES.md](docs/COORDINATES.md).
- `manifest.json` → `record_pressed_elapsed_realtime_ns` is the Record tap on the same
  clock as all timestamps.
- `manifest.json` → `pose_jumps` lists ARCore relocalizations (see
  [COORDINATES.md](docs/COORDINATES.md)): split there at analysis time, keep the longest
  segment. Focus mode (fixed/auto) is selectable in the app; autofocus is the default.
- The CPU image size is selectable in the app (button at bottom left). Intrinsics
  change with it, so recordings at different sizes are not interchangeable.
- Older formats: `posecam-3` has no `frame_metadata.csv`; `posecam-2` also has no IMU,
  intrinsics or device files; `posecam-1` also has no `image` column and no `frames/`.
