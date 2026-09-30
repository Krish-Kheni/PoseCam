# PoseCam setup guide for a new phone

**Sections 1–4 and 7 are for collectors** and need nothing but the phone.
**Sections 5–6 are for whoever runs the analysis**, from a computer with this repository,
`uv` and `adb`; they are done once per phone model, not per recording.

Installing takes about ten minutes; the one-time per-phone checks in §5 take an hour or
so including the recordings they need. Do **all** of §5 before that phone records real
data: phones differ, and several problems (wrong IMU axes, stabilization, wrong scale)
are silent. A one-page handout for collectors is [RECORDING_TIPS.md](RECORDING_TIPS.md).

## 1. Check the phone

- Look up the exact model (Settings → About phone) on Google's
  [ARCore supported devices](https://developers.google.com/ar/devices) list.
  If it's not there, PoseCam will not run.
- Android 7.0 or newer.
- At least 20 GB free. At 640×480 a recording uses roughly **3.5–4 GB per hour**
  (about 2 GB per 30 minutes), and zipping one to send it needs that much space again.
  The app refuses to start a recording below 4 GB free and shows how many minutes are
  left next to "Ready to record".

## 2. Install

You need the APK from whoever builds PoseCam — currently `PoseCam-0.3.0.apk` — or build
it yourself (see README). Install **0.3.0 or newer**: earlier builds arm Record after 1 s
(letting ARCore's first-second jumps into recordings) and cannot export on the phone.

**Upgrading from 0.2.x:** 0.3.0 is signed with a different key, so Android will refuse to
install it over the old app and it must be uninstalled first. **Uninstalling deletes every
recording still inside the app.** Before uninstalling: open PoseCam → Recordings, and for
each recording use *Export for pipeline* (or *Save raw zip to Downloads*), then confirm
with whoever processes the data that it arrived. Zips in `Downloads/PoseCam/` survive
uninstalling; the app's own recordings do not.

1. On the phone, turn on **Developer options**: Settings → About phone → Software
   information → tap **Build number** 7 times.
2. Settings → Developer options → turn on **USB debugging**.
3. Connect the phone to a computer with [platform-tools](https://developer.android.com/tools/releases/platform-tools)
   (`adb`), accept the "Allow USB debugging?" prompt on the phone, then:
   ```bash
   adb devices                       # the phone should be listed as "device"
   adb install -r PoseCam-0.3.0.apk
   ```
   Alternatively, copy the APK to the phone and open it (allow "install unknown apps").
4. Open PoseCam, allow camera access, and install "Google Play Services for AR" if asked.
5. **Samsung:** Settings → Apps → PoseCam → Battery → **Unrestricted**, so long
   recordings are not throttled.

## 3. Use

1. Open PoseCam. The bottom row has three buttons: **640×480** (left), **Focus: auto**
   (middle) and **Recordings** (right). Leave the first two exactly as they are — both
   turn red if changed, both ask for confirmation first, and recordings made with other
   settings are refused by the export because they cannot be mixed with the dataset.
2. Move the phone slowly, pointing at textured things, until the status says
   **Ready to record** (3 s of continuous, jump-free tracking; until then it says
   "Stabilizing, keep moving slowly…" and the button stays off).
3. **Record** → capture → **Stop**, then read the verdict dialog.

**One recording = one demo.** Press Record, do a single demonstration, press Stop. Several
demos in one recording cannot be separated afterwards: the export turns each continuous
stretch into exactly one demo folder.

Requirements from the training pipeline (cap_tools), for gripper demonstrations:

- **Both jaws completely inside the frame, with margin**, pointing **up** in the
  exported video (the phone can be mounted landscape or portrait; the exporter's
  `--rotate` option is set once per mount). The gripper aperture is recovered from the
  video by colour-segmenting the jaws, and a jaw cut by the frame edge corrupts that
  measurement. On the first Tecno mount the right jaw ran off the edge in half the
  frames: shift the phone toward the jaws' centre line, or move it back.
- **Plain background.** Nothing on the table in the jaws' colour: a red-flowered
  tablecloth produced jaw-sized red patches in most frames. A plain surface fixes it.
- Before recording at volume, run `uv run tools/check_gripper_view.py data/<session>`
  on a short test take (pass `--rotate` as the export will, and `--hue-lo/--hue-hi`
  for the jaw colour): it reports edge contact and background clutter.
- **Start each demo with the gripper wide open**, and open→close it fully at least once.
- **Tracking loss ends the demo.** Only gaps of up to 5 frames (about **0.17 s**) can be
  bridged; anything longer, and any pose jump, splits the recording, and each side becomes
  a separate demo (each must still be at least 3 s to be exported). The phone vibrates
  once, long, when tracking is lost, and three times when the pose jumps.
- Keep 30 fps (locked 640×480): action labels are 8-frame strides.
- **Good light.** In a dim room the exposure reaches its 33 ms maximum and fast hand
  motion blurs (seen on the Tecno takes). A desk lamp on the workspace is enough.
- **Wait for "Ready to record"** (about 3 s of stable tracking) and move the phone
  gently while waiting; ARCore is still settling its scale in the first seconds.
- Nobody needs to watch the screen: the phone **vibrates** on tracking loss (one long
  buzz) and on a pose jump (three short buzzes), and every take ends with a verdict
  dialog saying whether to redo it. See [RECORDING_TIPS.md](RECORDING_TIPS.md), the
  one-page handout for collectors.

Good habits:

- Never point at blank walls, ceilings or dark areas. Tracking gets lost there, and it
  can jump when it recovers.
- Move smoothly; exposure is ~20 ms indoors, so fast motion blurs. More light helps.
- Record unplugged; the phone heats more while charging.
- Don't switch apps or lock the screen mid-recording; that ends the recording.

## 4. Get recordings onto a computer

**The short way (no computer needed):** in the app, **Recordings → Export for pipeline
(MP4)** produces the folder the training pipeline reads (`RGB_<stem>.mp4` +
`AR_Pose_<stem>.txt`, one folder per clean segment), about a fifth of the size of the raw
recording, and shares or saves it as a zip. It applies the same rules as
`tools/export_anysense.py` and is verified against it byte for byte on real recordings.
It asks once which way the phone is mounted (the jaws must point up in the video).

**The full way (raw data, for diagnostics and calibration):**

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

Record each of these, pull, and run the tool. Send the output to the team, and keep
it with the notes for that phone model.

| Check | Recording | Command | Pass |
|---|---|---|---|
| Basic consistency | any 20 s recording | `uv run tools/check_sync.py data/<session>` | prints `OK`, or the only FAIL is `pose jump(s)` — jumps happen and the export splits them out; any other FAIL needs looking at |
| Camera↔IMU axes and clock | 20 s, rotate the phone about all three axes (tilt, pan, roll) | `uv run tools/check_imu_alignment.py data/<session>` | prints `OK`; if it reports a different mapping, **tell the team before using IMU data** |
| Metric accuracy | walk a measured rectangle (≥ 2×3 m) back to a floor mark, ending facing the start direction | `uv run tools/plot_trajectory.py data/<session> --save` | side lengths within a few % of the tape measure; loop closure a few % of path length |
| Lens distortion and intrinsics | 30 s of a checkerboard covering all image regions (`tools/make_checkerboard.py` renders one for a monitor) | `uv run tools/calibrate_camera.py data/<session> --pattern 9x6 --square <metres>` | report the verdict and the % differences |
| Heat | 10 min continuous, unplugged | `uv run tools/check_sync.py data/<session>` | fps per 30 s window stays near 30, no drop clusters |
| Visual alignment | any recording with a surface ~1 m ahead at the start | `uv run tools/overlay_check.py data/<session> --distance 1.0` | axes stay on the same spot in the scene |
| Delivery gate (per demo) | any exported demo folder | `uv run tools/check_export.py exports/<stem>` | prints `OK`: frames == pose lines, jaws visible and pointing up, gripper opens and closes |
| Gripper view | 30 s on the mount, jaws opening and closing | `uv run tools/check_gripper_view.py data/<session> --rotate <R>` | prints `OK`: two jaw blobs in ≥90% of frames, no contact with the left, right or top edges (the bottom edge is where they enter, so contact there is expected), little background clutter |
| **Metric scale** | walk a tape-measured 2×3 m rectangle back to a floor mark, ~20 s | `uv run tools/plot_trajectory.py data/<session> --save` | sides within a few % of the tape, loop closure a few % of path length — **blocking**: these poses become the action labels |

## 6. Every recording

Run `uv run tools/check_sync.py data/<session>` on every recording. Take special note
of:

- **pose jump**: ARCore relocalized mid-recording. Poses before and after are in
  different frames. Keep the recording: the export splits it at the jump and writes every
  clean stretch of at least 3 s as its own demo, so only the jump itself is lost. The
  phone vibrates three times when it happens and says so in the verdict at the end, so a
  demo can be redone on the spot.
- **optical stabilization was ON**: intrinsics are unreliable for that recording.
- **gaps / dropped images**: the phone could not keep up (heat, storage, resolution).
