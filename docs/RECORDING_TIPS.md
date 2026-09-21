# Recording demos with PoseCam

One page for whoever records the data. Nothing here needs a developer.

## Install

Download the APK to the phone first, then open it from **My Files → Downloads**.
Installing straight from WhatsApp or Drive often hangs at "Installing". Allow the camera
permission, and install "Google Play Services for AR" if the phone asks.

## Set up the mount, once

- **Both jaws fully inside the picture, with a margin.** If a jaw runs off the edge of the
  frame, the gripper opening cannot be measured and the demo is wasted. Record five
  seconds, look at the video, and adjust before recording anything real.
- **Plain background.** Anything in the jaws' colour on the table competes with them:
  a patterned cloth with red or orange in it is enough to confuse the measurement.
- **Light on the work area.** In a dim room the camera slows its shutter and fast
  movements come out blurred. A desk lamp is plenty.
- In the app, leave **640×480** and **Focus: auto** as they are. Recordings made with
  other settings cannot be mixed with the rest of the dataset.

## Each demo

1. Hold the gripper **wide open** to start.
2. Wait until the top bar says **"Ready to record"** — about three seconds; move the phone
   gently while you wait. The button stays off until then on purpose: the tracking needs a
   moment to settle, and recordings started too early have bad positions at the beginning.
3. Press **Record**, do the task, press **Stop**. Open and close the gripper **fully** at
   least once during the demo.
4. Read the message that appears: **"Take looks good"** or **"Better to record this one
   again"**, with the reason. That is the only thing you need to check.

**You do not need to watch the screen while recording.** If tracking is lost or the
position jumps, the phone **vibrates**:

| Vibration | Meaning | What to do |
|---|---|---|
| One long buzz | Tracking lost — the camera cannot tell where it is | Point at objects with texture, not a bare wall. If it keeps happening, stop and redo the take. |
| Three short buzzes | The position jumped | Finish the take, but expect to redo it; the recording gets split at that point. |
| Two medium buzzes at the end | The finished take is worth redoing | Read the message for the reason. |

## Sending the data back

Open **Recordings** in the app and tap a recording:

- **Export for pipeline (MP4)** — the small version to send (about 45 MB per 2.5 minutes).
  It asks which way the phone is mounted the first time; pick the option that makes the
  jaws point **up** in the video, and it remembers. Then **Share** it, or **Save to
  Downloads** and copy it over USB.
- **Share raw recording (zip)** — everything (about 250 MB per 2.5 minutes). Only send this
  if asked for it.
- **Delete** — once the export has been sent, to free up space. Recording needs about
  **5 GB per hour** free; the app refuses to start below 1 GB.

Keep the raw recording until whoever processes it confirms the export arrived.
