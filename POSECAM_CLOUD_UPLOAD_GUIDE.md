# Adding cloud upload to PoseCam (port of ThumbCam's cloud sync)

This guide describes how to bring the cloud-sync feature from **ThumbCam Recorder** (this repo, see
[CLOUD_SYNC.md](CLOUD_SYNC.md) and [../backend/README.md](../backend/README.md)) into **PoseCam**
(`IMPLEMENTATION_README.md`, app `0.3.0`, format `posecam-5`).

> **What this is based on.** ThumbCam: read from the source in this repo. PoseCam: read **only from its
> `IMPLEMENTATION_README.md`**, not from its code. Class names, thresholds and behaviours below are PoseCam's as
> that document states them. Anything marked **verify** is an assumption about PoseCam's code that you should check
> before relying on it.

---

## 1. Summary and decisions

PoseCam today has **no network code at all** (no `INTERNET` permission; "no upload" is in its scope statement). Data
leaves the phone by a manual hand-off (share sheet, Save to Downloads, `adb pull`). The goal is to add a
**local-first, automatic, resumable, verified upload to AWS S3** without changing how recording works.

The ThumbCam design transfers well: a persistent queue (Room), one unique WorkManager chain, presigned S3 URLs (the
phone sends bytes straight to S3, the backend only handles small JSON), per-file SHA-256 verification, and local
copies deleted only after the backend has verified the cloud copy. **Three things in PoseCam do not fit as-is** and
drive most of the work:

| # | PoseCam fact | Consequence | Section |
|---|---|---|---|
| 1 | A take is **thousands of small JPEGs** (about 4,500 for a 2.5 min take), not a few large segment files | ThumbCam's "one queue row + 2 API calls per file" would mean ~4,500 rows and ~9,000 API calls per take against a 10 req/s throttle. Pack frames into chunk archives. | 4 |
| 2 | Session ids look like `capture-20260916T143052-a3f9c1` | The ThumbCam backend only accepts UUID/ULID session ids and would answer `INVALID_SESSION_ID`. | 5 |
| 3 | UI is plain `android.app.Activity` + XML Views: no Compose, no AppCompat, no Material, no `lifecycleScope`, no coroutines/Room/WorkManager/OkHttp today | The Compose screens cannot be copied. The core (pure Kotlin) can. | 7, 9 |

Also: `minSdk 24` (ThumbCam is 29), so a few `java.util.Base64` / `java.time` calls need replacing (section 8.8).

### Decisions I made (change them if they are wrong)

| Decision | Choice | Why |
|---|---|---|
| What to upload | **The raw session** (poses, IMU, metadata, frames) | It is the source of truth; the export can be regenerated from it on a PC with `export_anysense.py`. Pipeline exports are an optional phase (section 11). |
| How frames travel | **Zip chunks of 1,000 frames** (`frames-00000.zip`, about 31-36 MB each), built on demand | Bounded temp space, small retry unit, few API calls. Alternatives rejected in 4.3. |
| When uploads run | Automatically after **Stop**, on **unmetered** networks by default, **never while a take is being recorded** | PoseCam's known failure mode is heat/CPU pressure causing `queue_full` drops. Hashing + TLS + radio during a take would cause them. |
| Backend | **Same code as ThumbCam's `backend/`, second deployment** with a PoseCam "profile" (path allow-list + session-id format) | Reuses presign/multipart/verify/complete as-is. Isolated data and abuse blast radius. |
| Auth | **None in phase 1** (same as ThumbCam), with a hard gate before field use (section 12) | The `AuthProvider` seam exists; do not ship an open API to collectors. |
| Format | **`posecam-5` unchanged, no cloud state written into session folders** | Folders stay immutable and `pull_captures.sh` keeps working. Cloud state lives in Room. |

---

## 2. How ThumbCam does it (the parts that matter)

```text
recording code ──(reports "this file is closed")──> SessionFileListener
                                                          │ non-blocking channel send
                                                          ▼
                                       UploadCoordinator ──> Room queue (uploads, cloud_sessions)
                                                          │
                                                          ▼
                              WorkManager unique chain ──> UploadWorker ──> UploadProcessor
                                                                              │
              POST /v1/sessions  ─────────────────────────────────────────────┤ 1 create cloud session
              POST …/uploads/presign  → SINGLE | MULTIPART | ALREADY_VERIFIED │ 2 ask how to upload
              PUT bytes straight to S3 (presigned, own HTTP client, no auth)  │ 3 transfer
              POST …/uploads/verify  (backend HeadObject: size + SHA-256)     │ 4 verify  -> VERIFIED
              POST …/sessions/{id}/complete (all required files VERIFIED)     │ 5 session -> SYNCED
                                                                              ▼
                                          LocalRetentionManager (SYNCED + retention) deletes local copy
```

Invariants to **preserve** when porting (they are why it is robust):

1. Recording code only *reports* closed files; the listener never blocks and never throws into the recorder.
2. A file is queued only when closed and immutable; recovery skips sessions that are still recording.
3. The queue is the source of truth (Room), unique on `(sessionId, relativePath)`: enqueue and recovery are idempotent.
4. One unique WorkManager chain, files uploaded one at a time; temporary failures retry forever with backoff; only
   permanent errors (non-retryable 4xx, missing local file, repeated failed verification) become `FAILED`.
5. Multipart progress (part number, ETag, base64 SHA-256) is persisted after every part, so a crash repeats at most one part.
6. `UPLOADED -> VERIFIED` only after the **backend** checks the S3 object. A PUT returning 200 proves nothing.
7. Local data is deleted only for sessions the backend confirmed `SYNCED`, with every local required file still matching
   what was verified.
8. A blank backend URL disables cloud sync entirely (no DB opened, no listener): the app behaves exactly as before.
9. The client never chooses buckets or S3 keys; it sends `sessionId + relativePath + sizeBytes + sha256`.
10. Presigned URLs and tokens are never logged; bearer tokens are never sent to S3.

---

## 3. Differences between the two apps

| Area | ThumbCam | PoseCam | Port impact |
|---|---|---|---|
| Session folder | `ThumbCam/sessions/<id>/` with few large `segment-NNNNN.rjmj` + CSV/JSON | `captures/<id>/` with `frames/NNNNNN_<ts>.jpg` (thousands), `poses.csv`, `frame_metadata.csv`, `imu.csv`, `intrinsics.json`, `device.json`, `manifest.json` | New file rules, frame chunking |
| File "closed" events | Segments rotate during recording, so files finalize early | Nothing is finalized until **Stop** (CSVs and JPEG queue are open until then) | Only `started` and `finalized` events; no early upload |
| Session id | UUID/ULID | `capture-YYYYMMDDTHHMMSS-xxxxxx` (6 hex = 24 random bits) | Backend regex change |
| Manifest status | `"status"`: `complete`, `interrupted` or `recovered-interrupted` | `"complete": true/false` (+ `session_id`, `start_wall_time_utc`, `frame_count`, `images.written`) | New `SessionManifestInfo` |
| Crash recovery | `SessionRecoveryScanner` repairs crashed sessions | None; killed take = `complete:false`, shown `INCOMPLETE`, cannot be exported | Treat `complete:false` as a finished-but-incomplete session (see 8.6) |
| Process during recording | Foreground `RecordingService` | Activity in foreground, screen kept on; `onPause` ends the take | No recording FGS; uploads can use the `dataSync` FGS freely when not recording |
| UI | Compose + Material3 snackbars | Views; no Material, no AppCompat, no lifecycleScope | Re-implement UI (section 9) |
| Platform | minSdk 29 | minSdk 24, targetSdk 37 | Replace API-26 calls; Android 13+ notification permission |
| Storage rule | Low-storage guard | Refuses to record below **4 GB** free | Retention feeds the same threshold |

---

## 4. Target architecture for PoseCam

### 4.1 What gets uploaded per session

All paths are session-relative and sent to the backend as `relativePath`.

| `relativePath` | Source | Kind | Required for SYNCED | Backend size class |
|---|---|---|---|---|
| `manifest.json`, `device.json`, `intrinsics.json` | session folder | METADATA | yes | small |
| `poses.csv`, `frame_metadata.csv` | session folder | TABLE | yes | small (about 12 MB/h) |
| `imu.csv` | session folder | IMU | yes | **large** (about 170 MB/h, would hit the 256 MiB small cap on long takes) |
| `frames-00000.zip`, `frames-00001.zip`, ... | built from `frames/*.jpg`, 1,000 frames each | FRAME_CHUNK | yes | small (about 31-36 MB) |
| `export/<stem>/RGB_<stem>.mp4`, `AR_Pose_<stem>.txt`, `posecam_export.json` | pipeline export | EXPORT | **no** (optional) | mp4 large |

Rationale for sizes: PoseCam reports 3.4-3.9 GB/h, so about 31-36 KB per JPEG. A 2.5 min take is about 4,500 frames,
so 5 chunks, 11 files in total, about 150-250 MB.

### 4.2 Why chunk (the numbers)

| | Per-JPEG queue rows | 1,000-frame chunks |
|---|---|---|
| Rows / files for a 2.5 min take | about 4,500 | 11 |
| Backend calls (presign + verify per file, plus create + complete) | about 9,000 | about 24 |
| Time on API alone at the 10 req/s throttle | 15+ minutes | seconds |
| Room writes, DynamoDB items | thousands | a handful |
| Retry unit on failure | one 31 KB file | one 31 MB file (cheap) |

### 4.3 Alternatives considered

| Option | Verdict |
|---|---|
| One zip of the whole session (what "Share raw zip" builds) | Needs temp space equal to the recording, near the 4 GiB large-file cap at about 1.2 h, and the whole zip must survive until uploaded. Rejected. |
| Upload each JPEG | See 4.2. Rejected. |
| Upload only pipeline exports | Valid cheaper phase 1 if raw data is not needed in the cloud: about 45 MB per 2.5 min and 3 files per demo. See section 11. |
| Chunk zips built lazily (chosen) | At most one chunk (about 35 MB) staged at a time, deterministic, resumable. |

### 4.4 Chunk rules (must be deterministic)

* Chunk number = `frameIndex / 1000`, where `frameIndex` is the leading number in `NNNNNN_<ts>.jpg`.
* Chunk contents = whatever `frames/*.jpg` exist for that index range **when the session is finalized**. A frame
  whose JPEG failed to write (`images.write_failures`) is simply absent; `poses.csv` still says `saved` (known PoseCam
  quirk, README section 16.4). Do not "fix" it in the uploader.
* Entries sorted by file name, **stored** (no compression, JPEGs are already compressed), **constant entry timestamp**
  (e.g. DOS epoch), no extra fields. The same inputs must always yield the same bytes, because the backend records the
  `sha256` declared at presign and a rebuild after a crash must declare the same one. **verify:** if PoseCam's
  `SessionZipper` stamps real mtimes, add a dedicated chunk writer instead of reusing it.
* Build to `<name>.tmp`, then rename. Stage under `getExternalFilesDir(null)/upload-staging/<sessionId>/`
  (not `cacheDir`, which the OS may purge mid-upload). Delete the staged file once its row is `VERIFIED`.
* Everything inside a chunk is already covered by the whole-file SHA-256 the backend verifies.

---

## 5. Backend changes (small, all in one file)

Reuse [../backend/](../backend/) unchanged except for `app/services/paths.py`, which is the only place that validates
session ids and relative paths (`normalize_session_id`, `classify_relative_path`). Everything else (presign,
multipart, verify, complete, DynamoDB, S3) is generic.

### 5.1 Profile switch

Add `APP_PROFILE` (`thumbcam` default | `posecam`) to `app/config.py`, a SAM parameter in `template.yaml`, and branch
inside `paths.py`. Deploy a **second stack** (`sam deploy --stack-name posecam-backend --parameter-overrides AppProfile=posecam`)
so the two apps never share a bucket, table or throttle budget.

### 5.2 Session id

```python
_POSECAM_SESSION_RE = re.compile(r"capture-[0-9]{8}T[0-9]{6}-[0-9a-f]{6}", re.ASCII)

def normalize_session_id(raw: object) -> str:
    if not isinstance(raw, str):
        raise errors.invalid_session_id()
    if PROFILE == "posecam":
        if _POSECAM_SESSION_RE.fullmatch(raw):
            return raw                       # already lowercase hex / digits
        raise errors.invalid_session_id()
    ...                                      # existing UUID / ULID logic
```

Update the error message (`errors.invalid_session_id`) per profile. Collision note: two devices recording in the same
second with the same 24-bit suffix would merge into one cloud session; probability is about 1 in 16 million per
same-second pair, so I accept it. If you want zero risk, put `installationId` into the cloud id on the app side
(`<installationId8>-<localId>`) and widen the regex accordingly.

### 5.3 Path allow-list (PoseCam profile)

```python
_PC_METADATA = frozenset({"manifest.json", "device.json", "intrinsics.json"})
_PC_TABLES   = frozenset({"poses.csv", "frame_metadata.csv"})
_PC_CHUNK_RE = re.compile(r"frames-[0-9]{5,6}\.zip", re.ASCII)
_STEM = r"[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}_[0-9]{2}_[0-9]{2}-[0-9a-f]{6}-s[0-9]{1,3}"
_PC_EXPORT_RE = re.compile(
    rf"export/(?P<stem>{_STEM})/(?P<name>RGB_(?P=stem)\.mp4|AR_Pose_(?P=stem)\.txt|posecam_export\.json)",
    re.ASCII,
)
```

| Match | `subdir` | `name` | size class | content type |
|---|---|---|---|---|
| `manifest.json`, `device.json`, `intrinsics.json` | `metadata` | same | small | `application/json` |
| `poses.csv`, `frame_metadata.csv` | `tables` | same | small | `text/csv` |
| `imu.csv` | `imu` | same | **large** | `text/csv` |
| `frames-NNNNN.zip` | `frames` | same | small | `application/zip` |
| `export/<stem>/RGB_<stem>.mp4` | `export/<stem>` | file name | large | `video/mp4` |
| `export/<stem>/AR_Pose_<stem>.txt` | `export/<stem>` | file name | small | `text/plain` |
| `export/<stem>/posecam_export.json` | `export/<stem>` | file name | small | `application/json` |

Notes:

* `_reject_hostile` already allows `/` between non-empty segments and rejects `..`, `%`, schemes (`:`), control chars.
  Check that the stem regex contains none of those (it does not).
* Add `".zip": "application/zip"` and `".txt": "text/plain"` to `_CONTENT_TYPES`.
* S3 key becomes `sessions/<sessionId>/<subdir>/<name>` as before.
* The presign signs `Content-Type` and `x-amz-checksum-sha256`; the app must send exactly the returned headers (the
  existing `OkHttpS3Transport` already does).
* Keep `MAX_SMALL_FILE_BYTES = 256 MiB`, `MAX_LARGE_FILE_BYTES = 4 GiB`, multipart threshold 100 MiB, part size 16 MiB.
  Chunks (about 35 MB) go `SINGLE`; `imu.csv` on a long take and exported MP4s go `MULTIPART`.
* Update `tests/test_paths.py`, `test_presign.py`, `test_session_complete.py` with PoseCam cases (valid ids and paths,
  traversal, wrong stem, chunk index width). The suite already runs against both catalog backends.

### 5.4 Deploy

Same as [../backend/README.md](../backend/README.md): `sam build && sam deploy`, note the `ApiUrl` output. Release
builds need an **https** URL.

---

## 6. PoseCam build and manifest changes

### 6.1 `gradle/libs.versions.toml` and `app/build.gradle.kts`

Add what ThumbCam uses (versions from this repo; check compatibility with PoseCam's AGP 9.4.0 / Gradle 9.7.1):

```toml
coroutines = "1.10.2"
okhttp = "4.12.0"
workManager = "2.10.0"      # ThumbCam is on 2.9.0; for targetSdk 37 prefer a 2.10+ line (dataSync FGS timeout callbacks). verify
room = "2.8.5"
ksp = "2.3.12"              # must match your Kotlin version; ThumbCam: Kotlin 2.2.10, AGP 9.3.1
```

```kotlin
plugins { alias(libs.plugins.ksp) }

android {
    buildFeatures { buildConfig = true }          // for CLOUD_BASE_URL
    buildTypes {
        debug   { buildConfigField("String", "CLOUD_BASE_URL", "\"${cloudBaseUrl("debug", "http://10.0.2.2:8000/")}\"") }
        release { buildConfigField("String", "CLOUD_BASE_URL", "\"${cloudBaseUrl("release", "")}\"") }
    }
    // Only if you keep java.time anywhere (minSdk 24): coreLibraryDesugaring. Preferred: don't use java.time (8.8).
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)             // android.jar's org.json is stubbed in JVM tests
    // Optional: Robolectric + androidx.test.core + work-testing for the real-Room tests
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
```

Copy ThumbCam's `cloudBaseUrl()` helper verbatim, renaming the keys to `posecam.cloud.baseUrl.<type>`. Resolution
order: `-P` property, `local.properties`, environment variable. A blank URL means cloud sync is off. Keep R8 off
(PoseCam already does).

### 6.2 `AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application android:name=".PoseCamApp" ...>
    <!-- WorkManager's foreground service, typed for network transfers (required on Android 14+) -->
    <service android:name="androidx.work.impl.foreground.SystemForegroundService"
             android:foregroundServiceType="dataSync" tools:node="merge" />
</application>
```

`src/debug/AndroidManifest.xml`: `<application android:usesCleartextTraffic="true" />` (debug only, for the local
`uvicorn` dev server; release stays https-only). PoseCam keeps `allowBackup="false"`, which also keeps the Room DB
out of backups.

### 6.3 `PoseCamApp` (new `Application` subclass)

Needed for three reasons: a place for app-level visibility tracking, WorkManager can start the process with no
activity, and `CloudSync` must work from there.

```kotlin
class PoseCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: Activity)  { if (++started == 1) AppVisibility.inForeground = true;  onVisible() }
            override fun onActivityStopped(a: Activity)  { if (--started == 0) AppVisibility.inForeground = false }
            /* other callbacks empty */
        })
    }
    private fun onVisible() = runCatching { CloudSync.get(this).notifier.dismissEventNotifications() }
}
```

PoseCam has two activities (`CaptureActivity`, `SessionsActivity`), so a started-activity counter replaces
ThumbCam's `MainActivity.onStart/onStop`.

---

## 7. Port map: every ThumbCam file and what to do with it

Package `com.staycold.thumbcam` becomes `com.posecam`. `core/cloud` and `core/sync` keep their names.

### 7.1 `core/cloud` (about 600 lines)

| File | Action |
|---|---|
| `AuthProvider.kt`, `NoAuthProvider.kt`, `OkHttpExt.kt`, `CloudModels.kt`, `CloudApi.kt`, `CloudApiClient.kt` | **Copy verbatim** (package rename). `CloudApiClient` already speaks the generic v1 API. |
| `CloudConfig.kt` | Copy verbatim (it reads `BuildConfig.CLOUD_BASE_URL` and `BuildConfig.DEBUG`). |
| `InstallationId.kt` | Copy; change the prefs name to `posecam_cloud`. |
| `CloudLog.kt` | Copy; tag `PoseCamCloud`. |

### 7.2 `core/sync`

| File | Action |
|---|---|
| `UploadState.kt` | Copy. **Replace** `UploadFileType` with `{ METADATA, TABLE, IMU, FRAME_CHUNK, EXPORT }`. Keep `SessionCloudStatus`. |
| `UploadEntity.kt` | Copy. **Add** `kind: UploadSourceKind` (`PLAIN`, `FRAME_CHUNK`) and `itemCount: Int = 0` (JPEGs in the chunk). Keep the unique index. |
| `UploadDao.kt`, `MultipartState.kt`, `MultipartUploader.kt`, `S3Transport.kt` | **Copy verbatim.** |
| `UploadDatabase.kt` | Copy; DB name `posecam-uploads.db`; version 1 (new install, no migration). Export the schema. |
| `UploadRepository.kt` | Copy. Change `enqueue(file: FinalizedSessionFile)` to take a new `QueuedFile` (section 8.3). Keep every transition and the `Transactor`. |
| `UploadCoordinator.kt` | Copy; **drop** `FileClosed` and `LateFile` events (PoseCam has no rotation); keep `Started`, `Finalized`, actions. |
| `UploadProcessor.kt` | Copy, then the four changes in 8.4. |
| `UploadScheduler.kt` | Copy; rename work names; add `pause()` and battery-not-low (8.5). |
| `UploadWorker.kt` | Copy; unchanged apart from `ActiveRecordingSessions` semantics (8.5). |
| `UploadThreads.kt` | Keep the background-priority executor/dispatcher. **Replace** `UploadThrottle.whileRecording` with a hard gate (8.5). |
| `FileHasher.kt` | Copy, but replace `java.util.Base64` with `android.util.Base64.encodeToString(bytes, Base64.NO_WRAP)` (API 26 vs minSdk 24). |
| `UploadQueueRecovery.kt` | Copy; scan `File(root, "captures")` instead of `ThumbCam/sessions` (8.6). |
| `CloudFileRules.kt` | **Rewrite** (8.1). Must stay in step with the backend profile in 5.3. |
| `SessionManifestInfo.kt` | **Rewrite** (8.2). |
| `ActiveRecordingSessions.kt`, `AppVisibility.kt`, `NetworkStatus.kt` | Copy verbatim. |
| `CloudSync.kt` | Copy; `root` = `captures` dir; `sessionListener` unchanged; see 8.7. |
| `CloudSyncSettings.kt` | Copy; prefs names `posecam_cloud` / `posecam_cloud_announce`. |
| `CloudOverview.kt`, `SessionCloudSummary.kt`, `CloudCardAction.kt` (incl. `MobileDataGuard`) | **Copy verbatim** (pure logic). |
| `SyncAnnouncer.kt` (incl. `CloudEventRouter`, `SnackbarAction`) | **Copy verbatim** (pure logic, unit-tested). Only the *rendering* of snackbars changes (section 10). |
| `LocalRetentionManager.kt` | Copy, then change the coverage check (8.9). |
| `CloudNotifier.kt` | Adapt (section 10). |

### 7.3 Recording side and UI

| ThumbCam | PoseCam |
|---|---|
| `core/storage/SessionFileEvents.kt` | New, smaller `SessionFileListener` (8.3) |
| `SessionWriter` calls the listener | `PoseRecorder` calls it (8.3) |
| `RecordingService` passes `CloudSync...sessionListener` | `CaptureActivity` passes it into `PoseRecorder` (8.3) |
| Compose: `CloudStatusButton`, `CloudProgressBar`, `CloudSyncBanner`, `CloudStatusPresentation`, `CloudUiBridge`, Settings section | **Do not copy.** Re-implement with Views (section 9). `CloudUiBridge` is still a good shape for the activity-to-core contract. |
| `debug/Cloud*DevPanel.kt` | Skip. |
| `backend/` | Reuse (section 5). |
| Tests | Copy and adapt (section 13). |

---

## 8. PoseCam-specific implementation

### 8.1 `CloudFileRules` (PoseCam version)

```kotlin
object CloudFileRules {
    private val METADATA = setOf("manifest.json", "device.json", "intrinsics.json")
    private val TABLES   = setOf("poses.csv", "frame_metadata.csv")
    private const val IMU = "imu.csv"
    private val CHUNK  = Regex("""^frames-\d{5,6}\.zip$""")
    private val EXPORT = Regex("""^export/[0-9_-]+-s\d{1,3}/(RGB_.+\.mp4|AR_Pose_.+\.txt|posecam_export\.json)$""")

    fun classify(path: String): UploadFileType? = when {
        path in METADATA -> UploadFileType.METADATA
        path in TABLES -> UploadFileType.TABLE
        path == IMU -> UploadFileType.IMU
        CHUNK.matches(path) -> UploadFileType.FRAME_CHUNK
        EXPORT.matches(path) -> UploadFileType.EXPORT
        else -> null
    }
    fun isRequired(path: String) = classify(path).let { it != null && it != UploadFileType.EXPORT }

    /** Plain files that exist now. Frame chunks are planned separately by FrameChunks. */
    fun listPlainUploadable(sessionDir: File): List<File> =
        sessionDir.listFiles().orEmpty().filter { it.isFile && classify(it.name) != null }.sortedBy { it.name }
}
```

Keep the allow-list identical to backend 5.3 (the ThumbCam comment says the same: a path the backend rejects fails
permanently).

### 8.2 `SessionManifestInfo` (PoseCam version)

PoseCam writes `manifest.json` with `Json.write` (`"key": value`). Parse with Android's `org.json` rather than regex.

```kotlin
data class SessionManifestInfo(val sessionId: String, val complete: Boolean, val startWallTimeUtc: String?, val frameCount: Int) {
    /** What we tell the backend. A killed take is "incomplete": uploaded as evidence, never as a deliverable. */
    val recordingStatus get() = if (complete) "complete" else "incomplete"
    companion object { fun read(dir: File): SessionManifestInfo? { /* JSONObject: session_id, complete, start_wall_time_utc, frame_count */ } }
}
```

`isFinal` in ThumbCam means "recording is over". For PoseCam that is `complete == true` **or** the session is not
the active one in this process (8.6).

### 8.3 Recording hook (the only change to recording code)

New, smaller listener (no per-file event: nothing closes early in PoseCam):

```kotlin
interface SessionFileListener {
    fun onSessionStarted(sessionId: String, directory: File) {}
    fun onSessionFinalized(sessionId: String, directory: File, recordingStatus: String) {}
}
```

`PoseRecorder` gets a constructor parameter `listener: SessionFileListener? = null` and calls it in exactly two places,
through a wrapper that swallows everything (ThumbCam's `notifyListener`):

| Where in `PoseRecorder` | Call |
|---|---|
| `start(...)`, after the folder, `device.json` and the initial `manifest.json` (`complete:false`) exist | `onSessionStarted(id, dir)` |
| `stop(extra)`, **last step**, after `frames.finish()` returned and the final `intrinsics.json` and `manifest.json` (`complete:true`) are written | `onSessionFinalized(id, dir, status)` where `status` is read back from the manifest (do not assume `complete`; a write-failure stop may differ) |

`CaptureActivity` constructs the recorder with
`runCatching { CloudSync.get(this).sessionListener }.getOrNull()` (null when sync is off or setup fails; **a cloud problem
must never stop a recording starting**).

`UploadCoordinator` is unchanged in spirit: `onSessionStarted` does `ActiveRecordingSessions.add` synchronously, then
`trySend`; `onSessionFinalized` enqueues the files (8.3.1) and calls `ActiveRecordingSessions.remove` after they are queued.

#### 8.3.1 Queueing at finalize

```kotlin
data class QueuedFile(
    val sessionId: String, val relativePath: String, val localPath: String,
    val kind: UploadSourceKind, val sizeBytes: Long,   // for chunks: estimate, see 8.4
    val itemCount: Int = 0, val sha256: String? = null,
)
```

At finalize the coordinator builds the list once, in one Room transaction (`repository.finalizeSession`):

* one `PLAIN` row for each file in `CloudFileRules.listPlainUploadable(dir)`;
* `FrameChunks.plan(File(dir, "frames"))` returns one `FRAME_CHUNK` row per chunk:
  `relativePath = frames-%05d.zip`, `localPath = <staging>/<sessionId>/frames-%05d.zip`, `itemCount = number of JPEGs`,
  `sizeBytes = Σ JPEG lengths` (an estimate that only feeds progress; it becomes exact when the chunk is built).

### 8.4 Changes inside `UploadProcessor`

1. **Materialize before hashing.** In `processFile`, replace `val file = File(row.localPath)` with
   `val file = materializer.ensure(row)`: for `PLAIN` it returns `File(row.localPath)`; for `FRAME_CHUNK` it reuses the
   staged zip if present, otherwise builds it (`FrameChunks.build`, 4.4) and calls
   `repository.saveMaterialized(row.id, sizeBytes = file.length())`. That method updates `sizeBytes` **without** resetting
   state or clearing the session's `syncedAt` (unlike `rebaseline`).
2. **Skip the "file changed" rebaseline for chunks.** The check
   `file.length() != row.sizeBytes -> rebaseline` is for plain files. For chunks the size is set by `saveMaterialized`
   and the staged file is the single source for hashing and uploading.
3. **Release the staging file** after `markVerified` (and on `ALREADY_VERIFIED`):
   `materializer.release(row)` deletes it. Failure to delete is non-fatal.
4. **Recording gate.** At the top of each loop iteration in `drainQueue`, if
   `ActiveRecordingSessions.snapshot().isNotEmpty()` return `QueueRunResult.DONE` (see 8.5). Also replace
   `java.time.Instant.ofEpochMilli(...)` (API 26) in `createPendingSessions` with `manifest?.startWallTimeUtc` and, as
   a fallback, a `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)` set to UTC.

Everything else (multipart, retry classification, `UPLOAD_CONFLICT` restart, `VERIFICATION_FAILED` cap of 3,
`SESSION_NOT_FOUND` recreate, `SESSION_INCOMPLETE` requeue) stays as is.

Disk-full while building a chunk raises `IOException`; the existing handler turns that into a temporary retry, which
is correct (retention and the user's cleanup will free space).

### 8.5 Do not upload while recording

ThumbCam *throttles* to 3 MB/s while recording. PoseCam should do more, because its documented failure under load is
dropped images (`queue_full`) and tracking quality depends on CPU/heat headroom:

* `UploadCoordinator.onSessionStarted` calls `scheduler.pause()`, which cancels both unique works
  (`cancelUniqueWork(UNIQUE_WORK_NAME)` and the manual one). Cancellation is safe: rows keep their state
  (`UPLOADING`/`UPLOADED` are runnable), multipart keeps its parts, and a single PUT of a chunk restarts from zero
  (about 35 MB, cheap).
* `UploadWorker.doWork` also returns `Result.success()` immediately if a recording is active (covers a worker that
  starts in the gap).
* `onSessionFinalized` calls `scheduler.schedule()`, which resumes everything.
* The Recordings button is already disabled while recording, so manual upload taps cannot race it.
* Keep `UploadThreads` (background-priority threads). Hashing and TLS run there, never on the GL thread.
* In `WorkManagerUploadScheduler.enqueue` add `.setRequiresBatteryNotLow(true)` to the constraints. Optionally
  `setRequiresCharging` as a user setting; note PoseCam's own guidance says charging heats the phone, but that matters
  during a take, not afterwards.

Foreground service: because PoseCam has **no** recording service, `UploadWorker` can call `setForeground(...)` whenever
it runs (drop the "skip while recording" branch; recording never overlaps a run now). Keep it wrapped in `runCatching`,
since Android 12+ can refuse to start an FGS from the background.

### 8.6 Recovery at app start

`UploadQueueRecovery.run()` does, in order: reset `PREPARING` rows, scan the sessions root, `finalizeSession` for every
finished session (idempotent), drop rows of sessions deleted from disk, schedule. For PoseCam:

* Scan `File(getExternalFilesDir(null), "captures")` (PoseCam's session root).
* A session is "over" when `ActiveRecordingSessions` does not contain it. In PoseCam nothing records without the
  activity in the foreground, so after a cold start every session folder is over, including those with `complete:false`.
* `complete:false` sessions are queued with `recordingStatus = "incomplete"`. PoseCam's `pull_captures.sh` already treats
  these as evidence ("a take that did not stop cleanly is still evidence"), and the backend accepts any status string
  you send. Make it a build flag (`UPLOAD_INCOMPLETE`, default true) if you would rather not upload them.
* Missing `frames/` or zero JPEGs: queue the plain files only; the session still reaches SYNCED.
* Run it from `CaptureActivity.onCreate` (launcher) as ThumbCam does from `MainActivity.onCreate`. Sessions made
  before this feature existed are adopted automatically, which will start a large backlog upload on first launch after
  the update: say so in the release notes, and default to Wi-Fi only.

### 8.7 `CloudSync` (service locator) adaptations

* `retention` root: `getExternalFilesDir(null)/captures`.
* `storagePressure`: `StatFs` free bytes below `MIN_FREE_GB + 2 GB` (PoseCam refuses to record below 4 GB, so start
  reclaiming at 6 GB). There is no `LowStorageGuard` in PoseCam; implement a tiny helper.
* `sessionListener`: null when `config.enabled` is false, exactly as ThumbCam.
* Add `fun pauseForRecording()` / `fun resume()` thin wrappers if you prefer the activity not to touch the coordinator.
* Call `reclaimStorage()` (a) in `CaptureActivity.toggleRecording` when the 4 GB check fails (then re-evaluate on the
  next tap and change the dialog text to mention automatic cleanup of synced recordings), (b) when `SessionsActivity`
  opens, (c) after each worker run (already in `UploadWorker`).

### 8.8 minSdk 24 checklist

| ThumbCam code | Problem | Replacement |
|---|---|---|
| `java.util.Base64` in `FileHasher` | API 26 | `android.util.Base64.encodeToString(bytes, Base64.NO_WRAP)` |
| `java.time.Instant` in `UploadProcessor` | API 26 | manifest string or `SimpleDateFormat` (8.4) |
| `ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` | API 29 | already guarded by `SDK_INT >= Q` in `CloudNotifier` |
| `NotificationChannel` | API 26 | already guarded |
| `registerDefaultNetworkCallback` | API 24 | OK |
| `ConcurrentHashMap.newKeySet()` | API 24 | OK |
| Notifications on Android 13+ | runtime permission | request `POST_NOTIFICATIONS` once (e.g. first time `SessionsActivity` opens); denial must not block uploads, only hide the notification |

### 8.9 `LocalRetentionManager`: coverage check for chunks

ThumbCam's `blockedReason` verifies every required **plain** file on disk is covered by a `VERIFIED` row of the same size.
`frames/*.jpg` are not plain files, so add:

```kotlin
val jpegs = File(dir, "frames").listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().size
val covered = rows.filter { it.kind == FRAME_CHUNK && it.state == VERIFIED }.sumOf { it.itemCount }
if (jpegs != covered) return RetentionBlock.LOCAL_FILE_NOT_COVERED
```

so a frame that exists locally but is in no verified chunk blocks deletion. Retention rules otherwise stay: only
`SYNCED` sessions, 7 days by default, oldest-first earlier under storage pressure, never `LOCAL_ONLY`/`PENDING`/
`UPLOADING`/`FAILED`. Folders under `cache/pipeline` and `cache/shared` are not retention's business.

Make deletion from the Recordings screen cloud-aware: in the Delete dialog, replace "Make sure it has been shared or
saved first" with the actual state ("Not uploaded yet: this deletes the only copy" vs "Synced to the cloud") and call
`coordinator.onSessionDeletedLocally(sessionId)` after a successful delete (ThumbCam does this in `onDeleteSession`).

---

## 9. UI in plain Views

PoseCam has no Compose, so keep the contract from ThumbCam's `CloudUiBridge` (summaries flow, waiting flag, settings
flow, messages flow, actions) and bind it with Views.

Important platform notes:

* `android.app.Activity` is not a `LifecycleOwner`: there is **no `lifecycleScope`**. Create
  `private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, start collecting in
  `onStart`, and `uiScope.coroutineContext.cancelChildren()` in `onStop`.
* No Material components, so no `Snackbar`. Use `Toast` for momentary messages and a banner `TextView` for ongoing
  state (section 10).

### 9.1 Recordings screen (`SessionsActivity`)

| Element | Implementation |
|---|---|
| Per-row cloud status | Add a second text line to each row, built from `SessionCloudSummary.displayStatus(waitingForNetwork)`: `On this phone only`, `Waiting for Wi-Fi`, `Uploading 45% · 3 of 9 files`, `Verifying…`, `Synced`, `Upload failed, tap to retry`. Reuse `CloudCardAction.infoMessage` strings. |
| Tap menu | Add `Upload to cloud now` (status LOCAL_ONLY/PENDING/WAITING), `Retry failed upload` (FAILED) via `CloudCardAction.forStatus`. For `UPLOADING/VERIFYING/SYNCED`, show the info line as a disabled first item. Existing actions (Export, Share, Save, Delete) stay. |
| Metered-network guard | Before starting/retrying, `MobileDataGuard.needsConfirmation(policy, cloud.isMetered(), settings.confirmMobileData)` then an `AlertDialog` with a "Don't ask again" checkbox and the byte estimate (`SessionCloudSummary.totalBytes` or the manifest size). |
| Header banner | New `TextView`/small `LinearLayout` at the top of `activity_sessions.xml`, driven by `CloudOverview.from(...)`: `message` + progress bar (`progressPercent`, indeterminate when -1) + a `Sync now` / `Retry` button when `kind` is WAITING/FAILED. Hidden when `kind == NONE`. |
| "Cloud sync" settings | A button in the header opening an `AlertDialog` with: policy (`Wi-Fi only` default / `Any network` / `Manual only`) via `setSingleChoiceItems`; auto-delete synced recordings (on/off) and retention days (`CloudSyncSettings.RETENTION_CHOICES_DAYS`); "Ask before using mobile data". Writes go through `CloudSync.setPolicy` etc. (unchanged). |
| Storage header | PoseCam already shows count / GB used / GB free; add "N synced, M waiting". |

The list is refreshed from `manifest.json` by regex today (README 16.6); the cloud line is independent of that and is
keyed by `session_id`.

### 9.2 Capture screen (`CaptureActivity`)

Keep it quiet: the phone is on a gripper and nobody is watching.

* No upload UI during recording. (Uploads are paused, section 8.5.)
* In the end-of-take verdict dialog add one line when cloud sync is enabled: `Queued for upload (Wi-Fi only)` or
  `Upload paused: no Wi-Fi`. Do not add vibration patterns; PoseCam's three patterns have fixed meanings (README 7.10).
* Do not block, delay or change `Record`/`Stop` for any cloud reason.

---

## 10. Notifications and in-app messages

Keep ThumbCam's single rule: **in-app message when the app is on screen, notification when not, never both.** The
decision logic (`SyncAnnouncer`, `CloudEventRouter`, `AppVisibility`) is copied unchanged; only the rendering differs.

| Piece | PoseCam change |
|---|---|
| Channel | One low-importance **"Cloud sync"** channel (`posecam_cloud_sync`), separate from anything else. Created in `CloudNotifier.init`. |
| Ongoing progress | Same: the `UploadWorker` foreground notification (type `dataSync`), updated at most once a second from `observeSummaries().sample(1000)`; text `Uploading recordings · 3 of 9 files · 120 / 560 MB`. |
| Event notifications | Same three: all synced / failed (count rises) / stalled (3 h of temporary failures). Ids 3002-3004 are fine. |
| Notification tap target | `SessionsActivity` (not the launcher), with `FLAG_ACTIVITY_NEW_TASK or CLEAR_TOP or SINGLE_TOP`, `FLAG_IMMUTABLE`. |
| Small icon | PoseCam's launcher foreground or a dedicated monochrome vector (ThumbCam used `ic_launcher_foreground`). |
| In-app routing | `EventRoute.Snackbar` becomes `Toast.makeText(...)` (and, for `RETRY`/`SYNC_NOW`, the banner button from 9.1 is the action). `CloudNotifier.messages` (a `SharedFlow`) is collected by `SessionsActivity` and by `CaptureActivity` only for the verdict line. |
| Temporary failures / waiting for Wi-Fi | Banner only; no notification (it is expected and would be noise). |

---

## 11. Optional phase: uploading pipeline exports

The Recordings screen's **Export for pipeline (MP4)** writes demos to `cache/pipeline/<session>-pipeline/<stem>/`.
That folder is in `cacheDir`, which the OS can purge, so queue rows pointing into it can vanish and would turn
`FAILED: Local file is missing`. Two safe ways:

1. **Move exports out of `cache/`** to `getExternalFilesDir(null)/exports/<session>/<stem>/` and keep the current
   share/save actions pointing there (they zip the folder into `cache/shared` anyway). Update `file_paths.xml` only if
   you start sharing directly from there. Delete after `VERIFIED` plus retention.
2. Upload from the cache and accept `FAILED`+re-export when purged. Not recommended.

Then add a third button to the export-result dialog: **Upload export**. It enqueues, for each demo folder,
`export/<stem>/RGB_<stem>.mp4`, `AR_Pose_<stem>.txt`, `posecam_export.json` as `PLAIN`, `required=false` rows of the
**same cloud session** as the raw recording (the export's `source_session` tells you which). Optional files do not hold
up `SYNCED`, exactly as `preview.mp4` does in ThumbCam. MP4 exports above 100 MiB take the multipart path (about 45 MB
per 2.5 min, so about 180 MB at 10 min).

If you decide raw upload is too heavy, this phase alone is a valid MVP: skip `FrameChunks` and the chunk rows entirely,
ship only exports plus `manifest.json`/`intrinsics.json`, and expose the `Upload export` button only.

---

## 12. Security and abuse: do before real collectors use it

ThumbCam's backend runs `AUTH_MODE=none`, which its README calls temporary and abusable: anyone with the URL can mint
presigned PUT URLs into your bucket (cost, junk data). PoseCam collectors are many people with phones, so:

1. **Phase 1 (internal testing only):** none, behind API Gateway throttling (10 rps / burst 20) and an AWS budget alarm.
2. **Before field rollout, minimum:** implement an `AuthProvider` that returns a bearer token and a matching verifier
   in `app/auth/` (`AuthVerifier`, `AUTH_MODE=device`). A single shared secret baked into the APK is extractable and
   only raises the bar; per-device enrolment (Cognito, or a one-time enrolment code exchanged for a device token) is the
   real fix. No change is needed in the worker, repository or recording code (ThumbCam's design point).
3. Once `Principal.device_id` is real, enforce that a device can only touch its own sessions (compare with the stored
   `deviceId`).
4. The `X-Device-Id` header is a label, never an authorization input.
5. Release builds require `https` (`CloudConfig.enabled` enforces it); never log presigned URLs or tokens.
6. **Data protection:** the frames show whatever the collector's camera saw (possibly people, places). Keep the bucket
   private (the template already blocks public access and uses SSE), decide retention and access, and tell collectors
   what is uploaded. PoseCam's current README promises "nothing is sent anywhere"; that statement changes.

---

## 13. Tests

Copy ThumbCam's tests and adapt; most core tests are pure JVM:

| Test (ThumbCam) | PoseCam action |
|---|---|
| `CloudApiClientTest` (MockWebServer), `CloudModelsTest`, `OkHttpS3TransportTest` | Copy as-is |
| `UploadProcessorTest`, `UploadCoordinatorTest`, `UploadRepositoryTest`, `UploadSchedulerTest`, `UploadDatabaseTest`, `UploadQueueRecoveryTest`, `SyncFixture`, `SyncFakes` | Copy; fixtures create PoseCam-style sessions (a `frames/` dir of fake JPEGs, `poses.csv`, `manifest.json` with `complete`) |
| `SyncAnnouncerTest`, `CloudOverviewTest`, `SessionCloudSummaryTest`, `CloudCardActionTest`, `LocalRetentionManagerTest` | Copy as-is, plus the chunk-coverage case in 8.9 |
| `SessionFileListenerTest` | Rewrite for `PoseRecorder`: listener is called once on start and once after the final manifest; a throwing listener does not affect the recording; `null` listener is a no-op |
| New `FrameChunksTest` | Same frames produce byte-identical zips; contiguous ranges; missing frame; non-`.jpg` and `.tmp` files ignored; entry names sorted; chunk count for 0, 1, 999, 1000, 1001 frames |
| New `CloudFileRulesTest` | Allow-list matches backend 5.3 exactly (consider a shared JSON of paths used by both test suites) |
| Backend `tests/test_paths.py` etc. | PoseCam profile cases (5.3) |

`PoseRecorderTest`, `FrameWriterTest` and the other PoseCam tests must pass unchanged with a `null` listener: that is
the guard that recording is unaffected.

Real-Room tests need Robolectric (`UploadDatabaseTest`); PoseCam has none today. Add it, or run those against the
`UploadDao` fake in `SyncFakes` as ThumbCam's other tests do.

---

## 14. Rollout plan with acceptance checks

| Phase | Work | Done when |
|---|---|---|
| **A. Backend** | PoseCam profile in `paths.py`, tests, second SAM deployment, `curl /v1/health` | `pytest -q` green; `POST /v1/sessions` with a PoseCam id returns 200 |
| **B. Core port** | Gradle deps, manifest, `PoseCamApp`, copy `core/cloud` + `core/sync`, rewrite `CloudFileRules` and `SessionManifestInfo`, `FrameChunks`, processor changes | Unit tests green; with an emulator and local `uvicorn` a hand-made session uploads and reaches `SYNCED` |
| **C. Recording hook** | `SessionFileListener` + `PoseRecorder` calls, `CaptureActivity` wiring, pause on record | A real take: recorder tests unchanged with `null` listener; with cloud on, `frame_count`, dropped images and fps equal a cloud-off take |
| **D. UI** | Row status, banner, settings dialog, notifications, metered-data dialog, delete warning | Manual: Wi-Fi off then on; kill the app mid-upload; airplane mode; background notification vs in-app toast |
| **E. Hardening** | 10-minute take on each phone model, battery/heat, Samsung "Unrestricted" setting, big backlog first launch, S3 verification of a downloaded chunk | `check_sync.py` passes on a session restored from S3 (unzip chunks into `frames/`); no new `dropped:queue_full` |
| **F. Auth + exports** | Section 12, section 11 | Auth enforced; export demos reach S3 and `check_export.py` passes on them |

Acceptance tests I would insist on:

1. Record a take while a 3 GB backlog is pending: uploads stop at Record and resume after Stop; drops and fps are the
   same as a cloud-off take on the same phone.
2. Force-stop during a chunk upload, relaunch: it resumes, one `part_uploaded`/`upload_completed` per file in the log,
   no duplicate objects (S3 versioning shows one version per key).
3. Corrupt a staged chunk byte: backend returns `VERIFICATION_FAILED`, app re-uploads, fails to `FAILED` after 3.
4. Delete a session locally while it uploads: no crash, rows forgotten.
5. Retention never deletes a session that has a JPEG not covered by a verified chunk.
6. Reconstruct the original session from S3 (metadata + unzipped chunks) and run `check_sync.py` on it.

---

## 15. Docs to update in PoseCam when this ships

* `IMPLEMENTATION_README.md` section 1 ("Nothing is sent anywhere"), section 3 ("PoseCam has no upload feature";
  the whole "no `INTERNET` permission" argument), section 5.3 (permissions table), section 5.4 (settings), section 6.3
  (file list), section 9 (Recordings screen), section 12.1 (collector steps), section 13 (constants: 1,000 frames per
  chunk, 6 GB reclaim threshold, retention 7 days, stall 3 h), section 16 (gotchas), section 17 (troubleshooting rows).
* `SETUP_GUIDE.md` (Wi-Fi, "Unrestricted" battery mode, first-launch backlog), `RECORDING_TIPS.md` (leave the phone on
  Wi-Fi after recording), `.gitignore` (`local.properties` already ignored; add nothing secret).
* Add a `CLOUD_SYNC.md` modelled on ThumbCam's, including the `event=<name> key=value` log vocabulary under tag
  `PoseCamCloud`.

---

## 16. Gotchas collected while reading both codebases

1. **Session id format** (backend rejects `capture-...` until section 5.2 is done).
2. **API 26 calls** at minSdk 24 (8.8).
3. **No `lifecycleScope`** in a plain `Activity` (section 9).
4. **Chunk determinism**: a rebuilt chunk with a different SHA-256 for an already-registered path conflicts at the
   backend; keep staging in `filesDir`/external files, not cache, and build deterministically.
5. **Failed JPEG still reads `saved`** in `poses.csv` (PoseCam README 16.4). Chunks contain what exists on disk;
   consumers must keep treating `images.write_failures` as authoritative.
6. **A pause ends the take** in PoseCam (`onPause` calls `stopRecording`), so `onSessionFinalized` can fire from the
   lifecycle callback while the activity is going away. Because the coordinator only does a `trySend`, this is safe; do
   not do Room or network work inline there.
7. **`complete:false` sessions** are uploaded as `incomplete`; the pipeline and any dashboards must filter on
   `recordingStatus`.
8. **WorkManager can start the process with no activity.** `CloudSync.get(applicationContext)` must not need an
   `Activity` anywhere (the notification tap intent is the only place that references one).
9. **Android 15+ `dataSync` foreground services have a daily time limit.** WorkManager reschedules on timeout; because
   the queue is idempotent, a stop mid-file only repeats at most one part or one chunk.
10. **Backlog on first launch**: recovery adopts every old recording. For a phone with 30 GB of old takes this is a
    long Wi-Fi upload; consider "adopt old recordings only when the user opts in" if that is a concern.
11. **Keep the repo docs honest**: this guide describes PoseCam only as far as its README does. Re-check names such as
    `PoseRecorder.start/stop`, `SessionZipper` and the `captures` path against the code before starting.
