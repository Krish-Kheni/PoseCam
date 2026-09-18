# PoseCam setup guide for a new phone

For anyone collecting data with PoseCam on their own Android phone. It takes about
30 minutes the first time. Complete **all** checks before collecting real data: phones
differ, and several problems (wrong IMU axes, stabilization, pose jumps) are silent.

## 1. Check the phone

- Look up the exact model (Settings → About phone) on Google's
  [ARCore supported devices](https://developers.google.com/ar/devices) list.
  If it's not there, PoseCam will not run.
- Android 7.0 or newer.
- At least 10 GB free: a recording at 640×480 uses about 2.5 GB per 30 minutes.

## 2. Install

You need the APK file (`app-debug.apk`) from whoever builds PoseCam, or build it
yourself (see README).

1. On the phone, turn on **Developer options**: Settings → About phone → Software
   information → tap **Build number** 7 times.
2. Settings → Developer options → turn on **USB debugging**.
3. Connect the phone to a computer with [platform-tools](https://developer.android.com/tools/releases/platform-tools)
   (`adb`), accept the "Allow USB debugging?" prompt on the phone, then:
   ```bash
   adb devices                       # the phone should be listed as "device"
   adb install -r app-debug.apk
   ```
   Alternatively, copy the APK to the phone and open it (allow "install unknown apps").
4. Open PoseCam, allow camera access, and install "Google Play Services for AR" if asked.
5. **Samsung:** Settings → Apps → PoseCam → Battery → **Unrestricted**, so long
   recordings are not throttled.

## 3. Use

1. Open PoseCam. Keep the resolution button (bottom left) at **640×480** (the team's
   locked size) and the focus button (bottom right) at **auto**. Recordings at other
   sizes are not comparable: intrinsics differ.
2. Move the phone slowly, pointing at textured things, until the status says
   **Ready to record**.
3. **Record** → capture → **Stop**.

Requirements from the training pipeline (cap_tools), for gripper demonstrations:

- **Blue jaws visible in every frame**, pointing **up** in the portrait video. The gripper
  aperture is recovered from the video by colour, not logged by the app.
- **Start each demo with the gripper wide open**, and open→close it fully at least once.
- **Tracking loss ends the take.** If the status shows PAUSED for more than a moment,
  stop, and start a new recording. Gaps longer than half a second cannot be bridged.
- Keep 30 fps (locked 640×480): action labels are 8-frame strides.

Good habits:

- Never point at blank walls, ceilings or dark areas. Tracking gets lost there, and it
  can jump when it recovers.
- Move smoothly; exposure is ~20 ms indoors, so fast motion blurs. More light helps.
- Record unplugged; the phone heats more while charging.
- Don't switch apps or lock the screen mid-recording; that ends the recording.

## 4. Get recordings onto a computer

Recordings are stored in the app's private folder
(`Android/data/com.posecam/files/captures/`), which file managers cannot browse on
modern Android. Use the **Recordings** button in the app instead. It lists every
recording with its length, frame count and size, and tapping one offers:

- **Share (zip)**: packs the recording and opens the Android share sheet. Send it to
  Google Drive, WhatsApp, email, or whatever you use. Recordings are about 1.5 MB per
  second, so a 30 s demo is ~45 MB.
- **Save zip to Downloads**: writes `Downloads/PoseCam/<recording>.zip`, which any file
  manager shows and which a computer can copy over USB (plug in, choose "File
  transfer", open the phone's `Download/PoseCam` folder).
- **Delete**: once it has been shared or saved. The app refuses to record with less
  than 1 GB free.

Send the zips to whoever runs the analysis. They unzip into the `data/` folder of the
PoseCam repository, one folder per recording, and the tools below work unchanged.

Alternatively, with USB debugging and the phone connected to a computer that has the
repository:

```bash
tools/pull_captures.sh            # copies finished recordings into data/
```

Tools run with [uv](https://docs.astral.sh/uv/), which installs their Python
dependencies automatically.

## 5. One-time checks for a new phone model

Record each of these, pull, and run the tool. Send the output to the team, and add
the results to the "Device notes" in CLAUDE.md.

| Check | Recording | Command | Pass |
|---|---|---|---|
| Basic consistency | any 20 s recording | `uv run tools/check_sync.py data/<session>` | prints `OK` |
| Camera↔IMU axes and clock | 20 s, rotate the phone about all three axes (tilt, pan, roll) | `uv run tools/check_imu_alignment.py data/<session>` | prints `OK`; if it reports a different mapping, **tell the team before using IMU data** |
| Metric accuracy | walk a measured rectangle (≥ 2×3 m) back to a floor mark, ending facing the start direction | `uv run tools/plot_trajectory.py data/<session> --save` | side lengths within a few % of the tape measure; loop closure a few % of path length |
| Lens distortion and intrinsics | 30 s of a checkerboard covering all image regions (`tools/make_checkerboard.py` renders one for a monitor) | `uv run tools/calibrate_camera.py data/<session> --pattern 9x6 --square <metres>` | report the verdict and the % differences |
| Heat | 10 min continuous, unplugged | `uv run tools/check_sync.py data/<session>` | fps per 30 s window stays near 30, no drop clusters |
| Visual alignment | any recording with a surface ~1 m ahead at the start | `uv run tools/overlay_check.py data/<session> --distance 1.0` | axes stay on the same spot in the scene |

## 6. Every recording

Run `uv run tools/check_sync.py data/<session>` on every recording. Take special note
of:

- **pose jump**: ARCore relocalized mid-recording. Poses before and after are in
  different frames. Keep the recording: split at the jump during analysis and use the
  longest segment (`check_sync.py` prints the segments). The app also shows a jump
  count while recording, so you can redo a take on the spot if you prefer.
- **optical stabilization was ON**: intrinsics are unreliable for that recording.
- **gaps / dropped images**: the phone could not keep up (heat, storage, resolution).
