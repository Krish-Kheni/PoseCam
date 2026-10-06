# Cloud upload

PoseCam can upload finished recordings to AWS S3 in the background. It is **off by default**: with no backend
URL built in, the app has no upload code path at all (no database is opened, no listener is installed, nothing is
shown) and behaves exactly like 0.3.0. This page describes the feature as implemented on branch
`feature/cloud-upload`. The design it follows is in `POSECAM_CLOUD_UPLOAD_GUIDE.md`.

## What it does

```text
PoseRecorder ──onSessionStarted / onSessionFinalized──> UploadCoordinator ──> Room queue (posecam-uploads.db)
"White pipe" / "Black pipe" tapped ────────choosePipe────>      │   (nothing is created or sent before this)
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
two buttons, **White pipe** and **Black pipe**. The dialog cannot be dismissed with Back or a tap outside, so the choice
cannot be skipped. **Nothing is uploaded, and the cloud session is not even created, until the collector chooses.** The
upload then goes into that pipe's folder:

```text
s3://<bucket>/sessions/white-pipe/<sessionId>/{metadata,tables,imu,frames}/...
s3://<bucket>/sessions/black-pipe/<sessionId>/{metadata,tables,imu,frames}/...
```

* "Take looks good" with cloud off keeps the original dialog (Close only). A take that should be **redone** also keeps
  Close and is not uploaded automatically.
* Recordings that were never filed (a take to redo, a take killed mid-recording, recordings made before cloud upload existed)
  show **"Choose a pipe to upload"** in Recordings, and tapping one offers **Upload as White pipe / Upload as Black pipe**.
  So enabling cloud never starts a surprise upload of the whole old backlog.
* The pipe is fixed once the session exists in the cloud (its files already live in one folder); a second tap or a stale dialog
  cannot move it.
* Backend: `CreateSessionRequest.pipe` (`white` | `black`, validated, PoseCam profile only) is stored with the session and
  decides the S3 key (`paths.build_s3_key(..., pipe)`); a session without a valid pipe is refused. The pipe is stored in the
  Room `cloud_sessions.pipe` column on the phone.

## What is uploaded

All paths are relative to the session folder. The backend allow-list is `cloud/path-rules.json`
(checked by both the Kotlin tests and `cloud/check_backend_rules.py`).

| Path | Source | Required for SYNCED |
|---|---|---|
| `manifest.json`, `device.json`, `intrinsics.json` | session folder | yes |
| `poses.csv`, `frame_metadata.csv`, `imu.csv` | session folder | yes |
| `frames-00000.zip`, `frames-00001.zip`, … | built from `frames/*.jpg`, 1,000 frames per chunk | yes |
| `export/<stem>/…` | pipeline export | no (**not wired up yet**, rules only) |

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

## Statuses and what a collector sees

* Recordings screen: a second line per recording (`On this phone only`, `Waiting for Wi-Fi`, `Queued for upload`,
  `Uploading 45% · 3 of 9 files`, `Verifying…`, `Synced`, `Upload failed, tap to retry`), a banner with progress and a
  `Sync now` / `Retry` button, `N synced, M waiting` in the storage header, and a **Cloud sync settings** button
  (upload over Wi-Fi only / any network / manual; delete synced recordings after N days or never; ask before using mobile data).
* Tapping a recording adds `Upload to cloud now` / `Retry failed upload`. Delete now says whether the recording is synced
  or "the only copy".
* End-of-take dialog: the White pipe / Black pipe choice described above, then a toast such as
  `Uploading to White pipe (Wi-Fi only).` or `Black pipe: waiting for Wi-Fi to upload.` No new vibration patterns.
* A progress notification (foreground service, type `dataSync`) while uploading, and result notifications only when the app
  is not on screen (in-app Toasts otherwise): all synced, failed, or stalled for 3 h.
* Android 13+: the notification permission is requested once, on first opening Recordings. Refusing it never blocks uploads.

Incomplete takes (`"complete": false`, i.e. the app was killed mid-take) are uploaded as `recordingStatus: "incomplete"`:
evidence, never a deliverable. Consumers must filter on it.

## Configuring a build

A blank URL means off. There is deliberately **no default URL**. Resolution order: `-Pposecam.cloud.baseUrl.<buildType>`,
then `local.properties` (git-ignored), then the environment variable `POSECAM_CLOUD_BASEURL_<BUILDTYPE>`.

```bash
# debug build talking to a dev backend on the host (emulator loopback)
./gradlew :app:assembleDebug -Pposecam.cloud.baseUrl.debug=http://10.0.2.2:8000/
# release: https only (CloudConfig refuses cleartext outside debug builds)
./gradlew :app:assembleRelease -Pposecam.cloud.baseUrl.release=https://<api-id>.execute-api.<region>.amazonaws.com/
```

Debug builds allow cleartext HTTP (`src/debug/AndroidManifest.xml`) for a local backend; release builds do not.
New permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS`, plus `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` merged in by WorkManager.

**First launch after enabling:** queue recovery adopts every recording already on the phone, which can be a large Wi-Fi
backlog. Uploads default to Wi-Fi only and pause for every take.

## Backend

PoseCam has its own backend in [`backend/`](../backend/README.md): FastAPI on AWS Lambda (HTTP API), DynamoDB as the
catalog, S3 for the bytes, deployed with SAM. It is standalone and PoseCam-only: session ids are
`capture-YYYYMMDDTHHMMSS-xxxxxx`, only the paths in the table above are accepted, and every session carries a **pipe**
(`white` | `black`, required) that decides the S3 folder. Run its tests with `cd backend && pytest -q` (290 pass) and deploy it with
`sam deploy --stack-name posecam-backend`. See `backend/README.md` for setup, configuration, the API and deployment.

Check that the app and the backend agree on every id and path:

```bash
cd backend && python ../cloud/check_backend_rules.py
```

### Security: before any field use

The backend currently runs `AUTH_MODE=none`: anyone who has the URL can mint presigned upload URLs into the bucket. Do not
put a release URL in a build collectors use until device authentication exists (`AuthProvider` is the seam; nothing else
changes). The frames show whatever the collector's camera saw; keep the bucket private and tell collectors what is uploaded.

## Testing

* `./gradlew testDebugUnitTest`: 277 tests (47 pre-existing, unchanged). Includes Robolectric tests of the White/Black pipe dialog. Includes real Room (Robolectric), MockWebServer,
  and `RecordingToUploadIntegrationTest` (real `PoseRecorder` → coordinator → processor, verifying what reaches S3 equals
  what the recorder wrote).
* Backend: `cd backend && pytest -q` (290 passed, 4 skipped), `cfn-lint template.yaml`, and `python ../cloud/check_backend_rules.py`.
* End to end on an emulator, with the real app, a real HTTP backend and an S3 emulator: see `cloud/dev/e2e_backend.py`.
  moto's S3 does not store `x-amz-checksum-sha256`, so that launcher makes it report the SHA-256 of the stored bytes (a stricter
  check). It is a test tool only. Run `moto_server -p 5000`, `adb reverse tcp:5000 tcp:5000`, start the backend from `backend/` through the
  launcher with `CATALOG_BACKEND=local S3_ENDPOINT_URL=http://127.0.0.1:5000`, install a debug build with
  the URL above, push recordings into `Android/data/com.posecam/files/captures/` and open the app.

## Known limits

* Pipeline exports are not uploaded yet (`export/…` is in the rules, not in the UI).
* After a hard kill, an in-flight PUT may already have landed, so a re-send can leave two identical S3 versions of one key.
* Authentication is not implemented (see Security).
* The pipe dialog itself is verified with Robolectric, and the pipe choice through the Recordings screen on an emulator; the dialog
  has not been seen on a phone after a real take.
* The upload state machine, queue and UI are verified on an emulator and in unit tests; a recording cannot be made on the emulator
  (no ARCore), so the recorder hook is verified by the JVM integration tests and has not yet been exercised with a real take on a phone.
