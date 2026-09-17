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

## Relative poses

To express poses relative to the first recorded frame (offline):

```
T_rel[i] = inverse(T[0]) · T[i]
```

## Timestamps

`timestamp_ns` is `Frame.getTimestamp()`: nanoseconds on the same clock as Camera2
`SENSOR_TIMESTAMP` and Android sensor `event.timestamp`. It is not wall time and
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
