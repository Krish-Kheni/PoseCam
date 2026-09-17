# PoseCam

Android app (Kotlin + ARCore) that records 6-DoF camera poses, and later RGB frames
and IMU data, for robotics data collection. Local capture only.

- Plan: [docs/PLAN.md](docs/PLAN.md)
- Coordinate conventions: [docs/COORDINATES.md](docs/COORDINATES.md)

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

```bash
tools/pull_captures.sh                          # copies sessions into ./data/
uv run tools/check_sync.py data/capture-…      # timing + pose/image consistency checks
uv run tools/plot_trajectory.py data/capture-…  # stats + plot (--save writes trajectory.png)
```

## Output (format `posecam-3`)

```
capture-20260916T213140-9c433c/
├── frames/          000000_<timestamp_ns>.jpg, ...
├── poses.csv        frame_index,timestamp_ns,tx,ty,tz,qx,qy,qz,qw,tracking_state,image
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
- Older formats: `posecam-2` has no IMU, intrinsics or device files; `posecam-1` also
  has no `image` column and no `frames/`.
