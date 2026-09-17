# Coordinate conventions

Everything PoseCam records is raw ARCore output. Transform offline, never in the app.

## World frame

- **+Y is up**, aligned with gravity.
- **−Z** is the horizontal direction the camera faced when the **ARCore session**
  started, projected perpendicular to gravity.
- **+X** follows from the right-hand rule.
- Units are meters.

The **origin is where the ARCore session started**. That's when the capture screen
opened, **not** when Record was pressed. The first row of `poses.csv` is therefore
generally not at `(0, 0, 0)`.

Each app launch gets its own origin. Two recordings are only in a shared frame if
they came from the same session (no app restart, no pause in between) or you align
them yourself.

## Pose

Each row of `poses.csv` is `camera.getPose()` for that frame: the transform from the
**physical camera sensor frame** to the world frame (`T_world_camera`).

- Translation `(tx, ty, tz)` is the camera position in world coordinates.
- Rotation `(qx, qy, qz, qw)` is a unit quaternion, Hamilton convention, scalar last.
  SciPy: `Rotation.from_quat([qx, qy, qz, qw])` (scalar-last by default).
- Camera frame (ARCore/OpenGL convention): **+X right, +Y up, −Z forward** (looking
  direction), relative to the sensor's native landscape orientation. It does not
  change with screen rotation. This differs from the OpenCV convention (+Y down,
  +Z forward). To convert: `T_world_cv = T_world_camera · diag(1, −1, −1, 1)`.

We deliberately do **not** use `getDisplayOrientedPose()`. It bakes in screen
rotation and is only meant for rendering.

Rotation is not identity at the start. Heading is zeroed to the initial facing
direction, but pitch and roll are absolute against gravity.

## Images

JPEGs in `frames/` are the ARCore CPU image in the sensor's native orientation
(landscape, as read out), not rotated for the screen. Image +X (columns) and +Y
(rows, downward) align with the camera frame's +X and −Y, so projecting a world
point with the pose and intrinsics needs no extra rotation for display.

## IMU

`imu.csv` rows are raw `SensorEvent` values in the **Android sensor frame**, which
is defined against the phone's natural orientation (portrait for phones), not the
camera:

- **+X** to the right of the screen, **+Y** toward the top of the screen, **+Z** out
  of the screen (toward the user). Right-handed.
- `accel`: `TYPE_ACCELEROMETER`, m/s². Includes gravity: a phone lying flat on a table
  reads about `(0, 0, +9.81)`.
- `gyro_uncal`: `TYPE_GYROSCOPE_UNCALIBRATED`, rad/s, counter-clockwise positive
  (right-hand rule). `x,y,z` are raw rates with no drift compensation; `bias_x..z` is
  the platform's drift estimate (subtract it to get the calibrated rate).
- `gyro`: `TYPE_GYROSCOPE` (calibrated), only on phones without an uncalibrated gyro.

**This is not the camera frame.** The ARCore camera frame follows the image sensor's
landscape readout, while the IMU frame follows the portrait screen. On most phones
the back camera is mounted with `SENSOR_ORIENTATION` 90° (recorded in `device.json`),
giving:

```
camera +X  =  IMU −Y
camera +Y  =  IMU +X
camera +Z  =  IMU +Z
```

Verified on the S20 FE (SM-G781B): a least-squares fit of gyro rates to angular
velocity from `poses.csv` recovered exactly this axis mapping, with a residual of about
2.5% of the signal at zero time offset. Verify it on other models the same way, or
estimate the full camera-IMU extrinsics with a calibration tool such as Kalibr. When the manufacturer
publishes it, `device.json` also carries `lens_pose_rotation`/`lens_pose_translation`.

Accelerometer and gyroscope samples are asynchronous and not aligned with camera
frames. Interpolate offline.

## Intrinsics

`intrinsics.json` holds `Camera.getImageIntrinsics()` for the CPU image in `frames/`:
`fx, fy, cx, cy` in pixels for `width × height`. It's a pinhole model, and ARCore
reports no distortion coefficients. It is not yet verified whether the CPU image is
undistorted. Camera2's factory `lens_distortion` and `lens_intrinsic_calibration`
(for the full sensor array, not this image) are in `device.json` when the phone
publishes them. On the S20 FE they show mild radial distortion (k1 ≈ 0.034), and a
scaled focal length about 3% above ARCore's. Calibrate yourself if you need
sub-pixel accuracy. Values
are sampled about once a second; `changed_during_recording` must be `false` for them
to apply to every frame.

## Per-frame capture metadata

`frame_metadata.csv` has one row per `poses.csv` row, from ARCore's
`Frame.getImageMetadata()` (Camera2 capture results):

- `exposure_time_ns`, `frame_duration_ns`, `sensitivity_iso`.
- `rolling_shutter_skew_ns`: time from the first to the last row readout. The frame
  timestamp refers to the start of exposure of the first row.
- `focus_distance_diopters`: 1/m, 0 = infinity. It's metric only when `device.json` →
  `camera.focus_distance_calibration` is `APPROXIMATE` or `CALIBRATED`.
- `ois_mode`: 1 means optical stabilization was on for that frame. The lens then moves
  the optical axis, and `intrinsics.json` cannot describe it.

## Relative poses

To express poses relative to the first recorded frame (offline). Row 0 is the first
frame received after the Record tap (`manifest.json` → `record_pressed_elapsed_realtime_ns`):

```
T_rel[i] = inverse(T[0]) · T[i]
```

## Timestamps

`timestamp_ns` is `Frame.getTimestamp()`: nanoseconds on the same clock as Camera2
`SENSOR_TIMESTAMP`. IMU rows use `SensorEvent.timestamp`. Both are on
`SystemClock.elapsedRealtimeNanos()` when `device.json` → `camera.timestamp_source`
is `REALTIME`. Otherwise they may be offset from each other: don't fuse without
checking. `manifest.json` → `clock_check` records how far each was behind
`elapsedRealtimeNanos` at capture, as a sanity check. Timestamps are not wall time and
not comparable across devices or reboots. `manifest.json` records wall time for
humans only.

Each JPEG is the camera image ARCore used for that frame's pose. Filenames carry
the frame timestamp. The image's own `Image.getTimestamp()` differs from it by about
a millisecond (−0.01 to +1.1 ms measured on an S20 FE), far below the 33 ms frame
interval. `manifest.json` records the measured range per session.

## Tracking

Rows with `tracking_state` other than `TRACKING` have empty pose fields.
`PAUSED:<reason>` carries ARCore's `TrackingFailureReason`. Poses can jump when ARCore
relocalizes; this is logged as-is, so detect it offline by thresholding per-frame
translation.
