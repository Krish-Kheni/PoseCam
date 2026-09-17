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
uv run tools/plot_trajectory.py data/capture-…  # stats + plot (--save writes trajectory.png)
```

## Output (format `posecam-1`)

```
capture-20260916T213140-9c433c/
├── poses.csv       frame_index,timestamp_ns,tx,ty,tz,qx,qy,qz,qw,tracking_state
└── manifest.json   session info, device, ARCore version, camera config
```

One row per distinct camera frame, including untracked frames (empty pose fields).
