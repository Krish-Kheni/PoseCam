# Cloud upload

PoseCam can upload finished recordings to Cloudflare R2 in the background (through the LabelNow labeling server). It is **off by default**: with no backend
URL built in, the app has no upload code path at all (no database is opened, no listener is installed, nothing is
shown) and behaves exactly like 0.3.0. This page describes the feature as implemented on branch
`feature/cloud-upload`. The design it follows is in `POSECAM_CLOUD_UPLOAD_GUIDE.md`.

## What it does

```text
PoseRecorder ──onSessionStarted / onSessionFinalized──> UploadCoordinator ──> Room queue (posecam-uploads.db)
White / Black / Black/White pipe tapped ───choosePipe────>      │   (nothing is created or sent before this)
                                                              │
        PipelineExportStage (RGB_<stem>.mp4 + AR_Pose_<stem>.txt per clean segment) ──> export/<stem>/... rows, front of the queue
                                                              │
                                  WorkManager unique chain ──> UploadWorker ──> UploadProcessor
                                                                                   │
     POST /v1/sessions                                          create the cloud session
     POST …/uploads/presign   → SINGLE | MULTIPART | ALREADY_VERIFIED
     PUT bytes straight to S3 (presigned URL, separate HTTP client, no auth header)
     POST …/uploads/verify    (backend HeadObject: size + SHA-256)  → VERIFIED
     POST …/sessions/{id}/complete (every required file VERIFIED)   → SYNCED
                                                                                   │
                                                        LocalRetentionManager deletes synced recordings
```

* The **recorder only reports two facts**: a session started, a session was finalized. The listener never blocks and
  never throws into the recorder (`PoseRecorder.notifyListener` swallows everything).
* **Never while recording.** Starting a take pauses the upload chain; the processor and the worker also refuse to run while
  any take is active; Stop resumes it. PoseCam's known failure under load is dropped images, so no hashing, zipping, TLS
  or radio work happens during a take.
* The **queue is the source of truth** (Room, unique on `(sessionId, relativePath)`), so enqueueing and recovery are
  idempotent. Temporary failures retry forever with WorkManager backoff; only permanent errors become `FAILED`.
* **Verified, not assumed.** A file is `VERIFIED` only after the backend checked the S3 object (size and SHA-256); a 200
  from S3 proves nothing.
* A local recording is deleted only after the backend confirmed the whole session `SYNCED`, and only if every file and
  every JPEG still on disk is covered by what was verified.

## Pipes: where a recording is filed

When a take ends and the dialog says **"Take looks good"**, a build with cloud upload on asks which pipe to file it under:
three buttons, **White pipe**, **Black pipe** and **Black/White pipe** (LabelNow's three categories). The dialog cannot be
dismissed with Back or a tap outside, so the choice cannot be skipped. **Nothing is uploaded, and the cloud session is not even created, until the collector chooses.** The
upload then goes into that pipe's folder:

| app pipe (`Pipe.wire`) | cloud folder | LabelNow category |
|---|---|---|
| `white` | `white-pipe` | White pipes |
| `black` | `black-pipe` | Black pipes |
| `black-white` | `black-white-pipe` | Black/White pipes |

```text
<prefix>/<white|black|black-white>-pipe/<sessionId>/{metadata,tables,imu,frames,export}/...
```

* "Take looks good" with cloud off keeps the original dialog (Close only). A take that should be **redone** also keeps
  Close and is not uploaded automatically.
* Recordings that were never filed (a take to redo, a take killed mid-recording, recordings made before cloud upload existed)
  show **"Choose a pipe to upload"** in Recordings, and tapping one offers **Upload as White pipe / Black pipe / Black/White pipe**.
  So enabling cloud never starts a surprise upload of the whole old backlog.
* The pipe is fixed once the session exists in the cloud (its files already live in one folder); a second tap or a stale dialog
  cannot move it.
* Backend: `CreateSessionRequest.pipe` (`white` | `black` | `black-white`) is stored with the session and decides the
  folder; a session without a valid pipe is refused (`400 INVALID_REQUEST`). The pipe is stored in the Room
  `cloud_sessions.pipe` column on the phone (a plain string: a new pipe needs no schema change).

## What is uploaded

All paths are relative to the session folder. The backend allow-list is `cloud/path-rules.json`
(checked by both the Kotlin tests and `cloud/check_backend_rules.py`).

| Path | Source | Required for SYNCED |
|---|---|---|
| `manifest.json`, `device.json`, `intrinsics.json` | session folder | yes |
| `poses.csv`, `frame_metadata.csv`, `imu.csv` | session folder | yes |
| `frames-00000.zip`, `frames-00001.zip`, … | built from `frames/*.jpg`, 1,000 frames per chunk | yes |
| `export/<stem>/RGB_<stem>.mp4`, `AR_Pose_<stem>.txt`, `posecam_export.json` | pipeline export, one folder per clean segment | no (never holds up SYNCED) |

### Frame chunks

A 2.5 minute take is about 4,500 JPEGs. One queue row and two API calls per JPEG would be ~9,000 calls per take, so
frames travel in zip chunks: chunk *n* holds frame indices `n*1000 … n*1000+999`.

* Built **one at a time, just before upload**, into `getExternalFilesDir(null)/upload-staging/<session>/` (not
  `cacheDir`, which the OS may purge) and deleted as soon as the chunk is verified. At most ~35 MB is staged.
* **Deterministic:** entries sorted by name, STORED, fixed 1980-01-01 timestamp, no extras, written by `DeterministicZip`
  (not `ZipOutputStream`, whose DOS time depends on time zone and file mtimes). The backend records the SHA-256 declared at
  presign, so a chunk rebuilt after a crash must be byte-identical. Tested across time zones and file mtimes.
* A JPEG that failed to write is simply absent from its chunk (`poses.csv` still says `saved`; `manifest.json`
  `images.write_failures` is authoritative). The uploader does not "fix" this.
* The queued size of a chunk is an estimate (sum of JPEG sizes) until it is built; idempotency for chunks therefore compares
  the number of JPEGs, never sizes.

To restore a recording from S3: download the plain files, unzip every `frames-*.zip` into `frames/`.

### Pipeline export (what LabelNow actually uses)

The raw files above are evidence; LabelNow's pipeline consumes the **pipeline export**: `RGB_<stem>.mp4` + `AR_Pose_<stem>.txt`
(~50 MB, against ~3.4 GB per hour of raw data). Once a recording is filed under a pipe, `PipelineExportStage` runs
`PipelineExporter.export()` on it, automatically, and queues the result:

```text
export/<stem>/RGB_<stem>.mp4
export/<stem>/AR_Pose_<stem>.txt
export/<stem>/posecam_export.json
```

* **One folder per clean segment** (`-s1`, `-s2`, …), so one recording can become several labelable sets. `<stem>` is exactly
  what `SessionExport.stem` writes: `YYYY-MM-DD-HH_MM_SS-<6 lowercase hex>-s<N>`, and the stem inside each filename must equal
  its folder's (the server answers `403 PATH_NOT_ALLOWED` otherwise). `UploadPlan.forExport` refuses a folder that breaks this
  or lacks one of the three files, so an export is queued whole or not at all.
* **It runs inside the upload pass**, on the upload worker's background-priority threads, after the session is filed and
  before any file is sent. It is **never run while a take is being recorded**: the encoder's progress tick checks, so a take
  that starts mid-export stops it within about a second, its half-written output is deleted, and it simply runs again after Stop.
* **Written to staging** (`upload-staging/<session>/export/`), queued as `EXPORT` rows at the **front** of the queue (small and
  the part LabelNow uses, so a labeler's recording becomes labelable long before the raw upload ends), and deleted from staging
  as each file is verified. If staging is wiped before upload, the export is made again.
* **The rotation is a setting, not a question per take.** `rotateDegrees` (which way is up so the gripper jaws point up) lives in
  **Cloud sync settings → Pipeline export** and is the same value manual "Export for pipeline" uses (one key,
  `export_rotation_degrees`, so a value chosen there carries over). Until it has been set, **nothing is exported** (a wrongly
  rotated video would be uploaded and labeled unnoticed); the first time a recording is filed the collector is asked once.
  Exports that waited start as soon as it is chosen.
* **One video or several.** By default a recording is cut into one video per jump-free stretch (the reference exporter's rule):
  at every ARCore pose jump, and wherever more than 5 camera images in a row were dropped. **Cloud sync settings → "Keep each
  recording as one video"** exports the whole take as a single `-s1` video instead. Pose jumps then stay *inside* the video (the poses
  either side are in different ARCore frames, so the trajectory is not continuous there) and runs of dropped images repeat the last saved
  frame. Rows with no pose at all cannot be written and are skipped. Where, is listed in `posecam_export.json`
  (`pose_jumps_inside_video`, `omitted_rows_inside_video`, `whole_recording: true`). Off by default; confirm the labeling/training side can
  handle a jump inside one video before turning it on for labelers. It applies to exports made after it is switched on.
* **Export failure never fails the session.** Raw upload completes and the session reaches `SYNCED` without an export. It is
  never silent either, because that recording will not appear in LabelNow:

  | Why there is no export | Recordings row | Retried? |
  |---|---|---|
  | off protocol (not 640×480, 30 fps, auto focus) | `Synced — no pipeline export (off protocol)` | no |
  | killed take, no clean stretch, frames missing | `Synced — no pipeline export (cannot be exported)` | no |
  | crashed (storage, encoder) | `Synced — pipeline export failed, tap to retry` | only by **Retry pipeline export** |
  | an export file refused/failed to upload | `Synced — pipeline export upload failed, tap to retry` | only by retry |
  | no rotation chosen yet | `Synced — set the video rotation to make the pipeline export` | when it is chosen |

  Tapping such a recording offers **Why no pipeline export?**, which shows the exporter's own message (e.g. "recorded at
  640x360, not the team's 640x480").
* **A synced recording is kept on the phone until its export is verified in the cloud** (or is known to be impossible): the raw
  frames are the only source an export can be remade from.
* Raw upload stays on top of the export (the team's decision). If data plans become a problem, the natural split is export on
  any connection and raw on Wi-Fi only; today both follow the one network policy.

## Statuses and what a collector sees

* Recordings screen: a second line per recording (`On this phone only`, `Waiting for Wi-Fi`, `Queued for upload`,
  `Uploading 45% · 3 of 9 files`, `Verifying…`, `Synced`, `Upload failed, tap to retry`), a banner with progress and a
  `Sync now` / `Retry` button, `N synced, M waiting` in the storage header, and a **Cloud sync settings** button
  (upload over Wi-Fi only / any network / manual; the video rotation for pipeline exports; delete synced recordings after N days or never; ask before using mobile data).
* With cloud upload on, **Share raw recording**, **Save raw zip to Downloads** and the export dialog's **Share export** / **Save export to
  Downloads** are hidden: the app is how recordings leave the phone, and also sending one by hand would make the downstream pipeline redo
  work the upload already did. Without a backend URL they are all offered exactly as before.
* Tapping a recording adds `Upload to cloud now` / `Retry failed upload`. Delete now says whether the recording is synced
  or "the only copy".
* End-of-take dialog: the White / Black / Black/White pipe choice described above, then a toast such as
  `Uploading to White pipe (Wi-Fi only).` or `Black/White pipe: waiting for Wi-Fi to upload.` No new vibration patterns.
* A progress notification (foreground service, type `dataSync`) while uploading, and result notifications only when the app
  is not on screen (in-app Toasts otherwise): all synced, failed, or stalled for 3 h.
* Android 13+: the notification permission is requested once, on first opening Recordings. Refusing it never blocks uploads.

Incomplete takes (`"complete": false`, i.e. the app was killed mid-take) are uploaded as `recordingStatus: "incomplete"`:
evidence, never a deliverable. Consumers must filter on it.

## Configuring a build

A blank URL means off. There is deliberately **no default URL**. Resolution order: `-Pposecam.cloud.baseUrl.<buildType>`,
then `local.properties` (git-ignored), then the environment variable `POSECAM_CLOUD_BASEURL_<BUILDTYPE>`.

```bash
# debug build talking to the LabelNow server on the host (emulator loopback); keep the trailing slash
./gradlew :app:assembleDebug -Pposecam.cloud.baseUrl.debug=http://10.0.2.2:5001/api/posecam-cloud/
# physical phone on the same Wi-Fi: forward the port over USB instead of using the host's address
adb reverse tcp:5001 tcp:5001
./gradlew :app:assembleDebug -Pposecam.cloud.baseUrl.debug=http://127.0.0.1:5001/api/posecam-cloud/
# release: https only (CloudConfig refuses cleartext outside debug builds)
./gradlew :app:assembleRelease -Pposecam.cloud.baseUrl.release=https://<labeling-server-host>/api/posecam-cloud/
```

Debug builds allow cleartext HTTP (`src/debug/AndroidManifest.xml`) for a local backend; release builds do not.
New permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS`, plus `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` merged in by WorkManager.

**First launch after enabling:** queue recovery adopts every recording already on the phone, which can be a large Wi-Fi
backlog. Uploads default to Wi-Fi only and pause for every take.

## Backend

The backend is the LabelNow **labeling-server** (`labelnow/labeling-server`, Express + MongoDB), and bytes go to **Cloudflare
R2**. It implements the same `/v1` API the app was built against, mounted at `/api/posecam-cloud`, so the app needs no code
change: set the base URL to `https://<labeling-server-host>/api/posecam-cloud/` (see Configuration above). The server mints
presigned R2 URLs (R2's S3-compatible API); the phone PUTs straight to R2. Objects land at
`<POSECAM_UPLOAD_PREFIX>/<white|black|black-white>-pipe/<sessionId>/...` and the session/file catalog is in MongoDB
(`posecam_upload_sessions`, `posecam_upload_files`). Server setup and env vars: `labelnow/README.md`, section "PoseCam cloud
upload (R2)".

Differences from the old AWS backend: R2 has no whole-file SHA-256 for multipart objects, so `imu.csv` and exported MP4s
(multipart) are verified by size, while single-PUT files (frame chunks, metadata, tables) also carry a signed SHA-256 that R2
checks on receipt. The original FastAPI/Lambda/S3 backend in [`backend/`](../backend/README.md) is unchanged and no longer
needed and **does not know the `black-white` pipe**; `cloud/check_backend_rules.py` still checks its path rules against the app's.
**Checksums:** `S3Transport.put` forwards every header the backend returns except Content-Type/Length/Host, and that must stay.
The server returns `x-amz-checksum-sha256` as a signed *header* because R2 ignores it as a query parameter (silently, so
"verified" would shrink to a size check). If a PUT ever fails with `SignatureDoesNotMatch`, do not drop the header: that silently
disables integrity checking.

### Security: before any field use

The upload endpoints have no authentication (like the rest of the labeling-server API): anyone who has the URL can mint presigned upload URLs into the bucket. Do not
put a release URL in a build collectors use until device authentication exists (`AuthProvider` is the seam; nothing else
changes). The frames show whatever the collector's camera saw; keep the bucket private and tell collectors what is uploaded.

## Testing

* `./gradlew testDebugUnitTest`: 313 tests. Includes Robolectric tests of the three-button pipe dialog and of the rotation dialog, the pipeline-export
  stage and its upload (`PipelineExportUploadTest`, with a fake exporter: `MediaCodec` does not run on the JVM), and the Room 1→2
  migration against the exported v1 schema. Includes real Room (Robolectric), MockWebServer,
  and `RecordingToUploadIntegrationTest` (real `PoseRecorder` → coordinator → processor, verifying what reaches S3 equals
  what the recorder wrote).
* Backend: `cd backend && pytest -q` (290 passed, 4 skipped), `cfn-lint template.yaml`, and `python ../cloud/check_backend_rules.py`.
* End to end on an emulator, with the real app, a real HTTP backend and an S3 emulator: see `cloud/dev/e2e_backend.py`.
  moto's S3 does not store `x-amz-checksum-sha256`, so that launcher makes it report the SHA-256 of the stored bytes (a stricter
  check). It is a test tool only. Run `moto_server -p 5000`, `adb reverse tcp:5000 tcp:5000`, start the backend from `backend/` through the
  launcher with `CATALOG_BACKEND=local S3_ENDPOINT_URL=http://127.0.0.1:5000`, install a debug build with
  the URL above, push recordings into `Android/data/com.posecam/files/captures/` and open the app.

## Known limits

* `PipelineExporter` (the real MP4 encoder) is verified against the reference exporter by its own tests; the automatic export
  *stage* around it is verified with a fake exporter, and has not run on a phone.
* Exports are made only inside an upload pass, so on the default Wi-Fi-only policy they wait for Wi-Fi even though making them needs
  no network.
* After a hard kill, an in-flight PUT may already have landed, so a re-send can leave two identical S3 versions of one key.
* Authentication is not implemented (see Security).
* The pipe dialog itself is verified with Robolectric, and the pipe choice through the Recordings screen on an emulator; the dialog
  has not been seen on a phone after a real take.
* The upload state machine, queue and UI are verified on an emulator and in unit tests; a recording cannot be made on the emulator
  (no ARCore), so the recorder hook is verified by the JVM integration tests and has not yet been exercised with a real take on a phone.
