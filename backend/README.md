# PoseCam cloud upload backend

The server side of PoseCam's optional cloud upload (see `docs/CLOUD_UPLOAD.md` in the repo root).

```
Android (records locally, immutable files)
   | 1. POST /v1/sessions, /uploads/presign, ...        (small JSON, via API Gateway -> Lambda)
   v
API Gateway (HTTP API, throttled) -> Lambda (FastAPI + Mangum) -> DynamoDB (session/file catalog)
                                              |
                                              | presigned, short-lived PUT URLs (computed locally, no data flows through Lambda)
                                              v
Android ------------- 2. PUT file bytes DIRECTLY ----------------> S3 (versioned, private, SSE)
   | 3. POST /uploads/verify  -> Lambda HeadObject(ChecksumMode=ENABLED) vs recorded size + sha256
```

Design rules: large binary data never touches API Gateway/Lambda; DynamoDB (never `ListObjects`) is the catalog; the
client never supplies bucket names or S3 keys (it sends `sessionId` + `pipe` + `relativePath` + `sizeBytes` + `sha256`, the
backend builds the key); every write is idempotent.

## Pipes: the cloud folders

Each recording is filed under a **pipe**, chosen by the collector when the take ends (**White pipe** or **Black pipe**). The
app sends `pipe` (`white` | `black`) when it creates the session; it is required, validated and stored with the session, and
decides the S3 key for every file of that session:

```
sessions/white-pipe/<sessionId>/<subdir>/<name>
sessions/black-pipe/<sessionId>/<subdir>/<name>
```

A retried create with a different pipe returns the existing session unchanged: a session never moves between pipes.

## SECURITY: read this first

**`AUTH_MODE=none` is TEMPORARY. There is no authentication.** Anyone who can reach the API URL can create sessions and
obtain presigned upload URLs into your bucket, which costs you storage/transfer money and can fill the bucket with junk.
The `X-Device-Id` header / `deviceId` field is a client-chosen label used for logging only, **it is NOT authentication**
and must never be used for an authorisation decision. **Do not put a release URL in a build collectors use until device
authentication exists.**

Mitigations in place (they limit abuse, they do not prevent it):

| Mitigation | Where |
| --- | --- |
| API Gateway throttling (default 10 rps / burst 20 on every route, parameters `ApiThrottleRateLimit`/`ApiThrottleBurstLimit`) | `template.yaml` `HttpApi` |
| Server-side key construction; client can never choose bucket/key, only one of two pipe folders | `app/services/paths.py` |
| Strict allow-list of `relativePath` values + traversal/encoding/URL/control-char rejection; strict `sessionId` format | `app/services/paths.py` |
| Size caps (small kinds <= 256 MiB, large kinds <= 4 GiB, configurable) enforced at presign and at verify | `app/services/paths.py`, `session_service.py` |
| Short-lived presigned URLs (default 900 s, hard cap 3600 s), signed with the SHA-256 the client declared | `s3_service.py` |
| Least-privilege Lambda role (only `sessions/*`, no `*` actions/resources) | `template.yaml` `FunctionRole` |
| Structured audit logs (request id, source IP, device label, event, session/path; presigned URLs are never logged) | `app/main.py`, `app/logging_utils.py` |
| Bucket: all public access blocked, TLS-only policy, SSE, versioning, multipart auto-abort after 7 days | `template.yaml` |

Known residual risks while unauthenticated: an attacker can still burn throttle budget, create many sessions/rows, and upload
up to the caps. S3 does not pin the *length* of a presigned PUT (only the checksum we signed), so oversize uploads are only
rejected at `verify`. Consider an AWS budget alarm and a WAF rate-based rule before sharing the URL widely.

**TODO(auth): where device authentication plugs in.** All auth goes through one dependency, `app.auth.get_principal` (applied
at router level; endpoints contain no `if auth enabled` branches). To add it: implement the `AuthVerifier` protocol in
`app/auth/base.py`, return it from `build_verifier()` in `app/auth/__init__.py` for `AUTH_MODE=device`, and replace the
fail-closed `NotImplementedDeviceAuthVerifier` (`app/auth/device_auth.py`). Until then `AUTH_MODE=device` answers every call
with HTTP 501 `AUTH_NOT_IMPLEMENTED` (fail closed, never silently open). Once `Principal.authenticated` is real, add
per-device authorisation (a device may only touch its own sessions: compare `Principal.device_id` with the stored `deviceId`).

**Never commit AWS credentials** (or `samconfig.toml`, which is git-ignored here). Use a named profile
(`~/.aws/credentials`), SSO, or environment variables. The Lambda uses its execution role, no keys are deployed.

**Data protection:** the frames show whatever the collector's camera saw (possibly people and places). Keep the bucket private
(the template blocks public access and uses SSE), decide who may read it and for how long, and tell collectors what is uploaded.

## Layout

```
backend/
  app/main.py                 FastAPI app, `handler = Mangum(app, lifespan="off")`, error handlers, request-id + audit middleware
  app/config.py               Settings dataclass from env (cached)
  app/errors.py               ApiError + the single error envelope
  app/auth/                   base.py (Principal, AuthVerifier), no_auth.py, device_auth.py (fail closed), __init__.py (get_principal)
  app/api/                    sessions.py, uploads.py (routers, all under /v1)
  app/models/                 session.py, upload.py (pydantic v2, camelCase JSON)
  app/services/               paths.py, session_service.py, s3_service.py, dynamodb_service.py, local_catalog.py
  app/deps.py                 service container / FastAPI dependencies (override in tests)
  scripts/bootstrap_local.py  create bucket+table on a local emulator
  tests/                      pytest (moto for DynamoDB, an S3 double for object calls, real botocore presigning)
  template.yaml               AWS SAM template
```

## Configuration (environment variables)

| Variable | Default | Notes |
| --- | --- | --- |
| `AWS_REGION` (fallback `AWS_DEFAULT_REGION`) | required | set automatically by Lambda |
| `S3_BUCKET` | required | injected by the template (`!Ref`) |
| `DYNAMODB_TABLE` | required | injected by the template (`!Ref`) |
| `AUTH_MODE` | `none` | `none` or `device` (device = 501, fail closed) |
| `PRESIGNED_URL_TTL_SECONDS` | `900` | 1..3600 |
| `MULTIPART_THRESHOLD_BYTES` | `104857600` (100 MiB) | `sizeBytes >=` this => `MULTIPART` mode |
| `MULTIPART_PART_SIZE_BYTES` | `16777216` (16 MiB) | must be >= 5 MiB (validated) |
| `MAX_SMALL_FILE_BYTES` | `268435456` (256 MiB) | metadata, tables, frame chunks, export text files |
| `MAX_LARGE_FILE_BYTES` | `4294967296` (4 GiB) | `imu.csv`, exported MP4s |
| `CATALOG_BACKEND` | `dynamodb` | `dynamodb` (needs `DYNAMODB_TABLE`; required on Lambda) or `local` (SQLite file `LOCAL_CATALOG_PATH`, default `local-catalog.db`; **development only**, refused on Lambda) |
| `S3_ENDPOINT_URL`, `DYNAMODB_ENDPOINT_URL` | unset | local emulators only |
| `ENABLE_API_DOCS` | unset | `true` exposes `/docs` and `/openapi.json` (off by default) |

An invalid/missing configuration makes every call return `500 INTERNAL_ERROR` (retryable) and logs the reason.

## Setup, run, test

```bash
cd backend
python3.12 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt        # runtime deps + pytest, moto, cfn-lint, uvicorn
pytest -q                                  # runs entirely offline (no AWS account needed)
cfn-lint template.yaml                     # template lint (needs aws-sam-translator, included in requirements-dev.txt)
python ../cloud/check_backend_rules.py     # this backend vs the app's cloud/path-rules.json
```

### Run locally against real AWS (recommended: presigned URLs are then reachable from the emulator/phone)

Shortcut: `cp .env.example .env`, fill in `AWS_PROFILE`, `AWS_REGION`, `S3_BUCKET` and `DYNAMODB_TABLE`, then run
`scripts/run_local.sh`. `.env` is git-ignored (only `.env.example` is committed). Prefer an AWS profile
(`aws configure --profile posecam-dev`, stored in `~/.aws/credentials`) over putting a key/secret in `.env`.

**No DynamoDB table?** Set `CATALOG_BACKEND=local` in `.env`: session/file metadata is then kept in a local SQLite file
(`local-catalog.db`, git-ignored) and only S3 is needed. This is for local development only; the data lives on your machine
and the API refuses this mode on Lambda. The whole API test-suite runs against both backends.

```bash
export AWS_PROFILE=posecam-dev AWS_REGION=ap-south-1
export S3_BUCKET=<BucketName output>  DYNAMODB_TABLE=<TableName output>
export AUTH_MODE=none
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
curl http://localhost:8000/v1/health
```

Android **emulator** base URL: `http://10.0.2.2:8000/` (the emulator's alias for your host). A physical device needs your
machine's LAN IP, e.g. `http://192.168.1.20:8000/`.

### Run locally against an S3 emulator

```bash
moto_server -p 5000 &
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=us-east-1
export S3_BUCKET=posecam-local CATALOG_BACKEND=local S3_ENDPOINT_URL=http://127.0.0.1:5000
python scripts/bootstrap_local.py   # creates the bucket (needs DYNAMODB_TABLE set for the DynamoDB step; ignore for local)
adb reverse tcp:5000 tcp:5000       # presigned URLs embed 127.0.0.1:5000: make it reachable from the emulator
```

Caveat: moto accepts but does not store `x-amz-checksum-sha256`, so `verify` reports `CHECKSUM_MISMATCH` against plain moto
(real S3 and MinIO store it). `../cloud/dev/e2e_backend.py` is a test-only launcher that makes the emulator report the
SHA-256 of the stored bytes. Use real AWS for true end-to-end tests.

## Deploy with SAM

Prerequisites: AWS SAM CLI, Python 3.12 on PATH (for `sam build`), an AWS profile for the target account.

Permissions the deploying identity needs (admin on a dev account is simplest): CloudFormation (create/update/describe stacks,
change sets, `CAPABILITY_IAM`), IAM (create role, put role policy, pass role), S3 (create bucket, bucket
policy/encryption/versioning/lifecycle config, plus read/write on the SAM artifact bucket), DynamoDB (create table, enable
PITR/SSE, tag), Lambda (create/update function, add permission), API Gateway v2 (create API/stage/routes/integrations),
CloudWatch Logs (create log groups, set retention, resource policies for access logging).

```bash
export AWS_PROFILE=posecam-dev AWS_REGION=ap-south-1
cd backend
sam build
sam deploy --guided          # answers are saved to samconfig.toml (git-ignored, do not commit)
```

Non-guided:

```bash
sam deploy \
  --stack-name posecam-backend \
  --region ap-south-1 --profile posecam-dev \
  --resolve-s3 \
  --capabilities CAPABILITY_IAM \
  --no-confirm-changeset \
  --parameter-overrides Stage=dev ApiThrottleRateLimit=10 ApiThrottleBurstLimit=20 \
      LogRetentionDays=30 AuthMode=none PresignedUrlTtlSeconds=900
```

Find the API URL (also printed at the end of `sam deploy`):

```bash
aws cloudformation describe-stacks --stack-name posecam-backend --region ap-south-1 \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" --output text
```

Outputs: `ApiUrl` (ends with `/`, default `$default` stage so there is no stage path), `BucketName`, `TableName`, `FunctionName`
(`sam logs -n ApiFunction --stack-name posecam-backend --tail`).

Notes: the bucket and table have `DeletionPolicy: Retain` (deleting the stack leaves your data; delete them by hand when you
are sure). Presigned URLs are signed with the Lambda role's temporary credentials, so a URL cannot outlive those credentials
even if the TTL is longer. Bucket/table names are generated by CloudFormation. Change multipart/size limits via the
function's `Environment` block.

## Android configuration

The app reads the backend URL from `local.properties` (not committed) or `-P` Gradle flags. A blank URL leaves cloud upload
**off**; there is no default.

```properties
# local.properties
posecam.cloud.baseUrl.debug=http://10.0.2.2:8000/
posecam.cloud.baseUrl.release=https://<api-id>.execute-api.<region>.amazonaws.com/
```

```bash
./gradlew assembleRelease -Pposecam.cloud.baseUrl.release=https://<api-id>.execute-api.<region>.amazonaws.com/
```

Debug builds allow cleartext HTTP (`app/src/debug/AndroidManifest.xml`); release builds require `https` and ignore a
non-https URL (upload stays off).

## API (all under `/v1`, JSON, camelCase)

Every response carries `x-request-id`. Send `X-Device-Id` on every call (observability only).

| Method + path | Purpose |
| --- | --- |
| `POST /v1/sessions` | Idempotent create (conditional put). Body needs `pipe` (`white` or `black`). `{session, created, config}` |
| `GET /v1/sessions/{sessionId}` | `{session, files[]}` (DynamoDB query, paginated internally) |
| `POST /v1/sessions/{sessionId}/uploads/presign` | `SINGLE` (presigned PUT), `MULTIPART`, or `ALREADY_VERIFIED` |
| `POST /v1/sessions/{sessionId}/uploads/multipart/start` | create/resume a multipart upload (`resumed`, `uploadedParts`) |
| `POST /v1/sessions/{sessionId}/uploads/multipart/parts` | presign up to 100 parts |
| `POST /v1/sessions/{sessionId}/uploads/multipart/complete` | complete (idempotent) -> `UPLOADED` |
| `POST /v1/sessions/{sessionId}/uploads/verify` | HeadObject + size + SHA-256 check -> `VERIFIED` |
| `POST /v1/sessions/{sessionId}/complete` | mark the session `SYNCED` once all listed files are `VERIFIED` |
| `GET /v1/health` | liveness (no auth, no AWS calls) |

Errors: `{"error":{"code","message","retryable","details"}}`. 400 `INVALID_SESSION_ID` / `INVALID_PATH` /
`INVALID_REQUEST`; 413 `FILE_TOO_LARGE`; 404 `SESSION_NOT_FOUND` / `FILE_NOT_FOUND` / `UPLOAD_NOT_FOUND`;
409 `VERIFICATION_FAILED` (`details.reason` = `OBJECT_MISSING` | `SIZE_MISMATCH` | `CHECKSUM_MISMATCH`),
`SESSION_INCOMPLETE` (`details.missing`, `details.unverified`), `UPLOAD_CONFLICT`, `SESSION_CONFLICT`;
501 `AUTH_NOT_IMPLEMENTED`; 5xx `INTERNAL_ERROR` / `UPSTREAM_ERROR` (`retryable: true`).

Session ids look like `capture-YYYYMMDDTHHMMSS-xxxxxx` (exactly what the app generates). Allowed `relativePath`
values (case-sensitive; everything else is `INVALID_PATH`):

| `relativePath` | Stored under | Size class |
| --- | --- | --- |
| `manifest.json`, `device.json`, `intrinsics.json` | `metadata/` | small |
| `poses.csv`, `frame_metadata.csv` | `tables/` | small |
| `imu.csv` | `imu/` | **large** (about 170 MB/h) |
| `frames-NNNNN.zip` (5-6 digits; 1,000 JPEGs per chunk) | `frames/` | small |
| `export/<stem>/RGB_<stem>.mp4` | `export/<stem>/` | **large** |
| `export/<stem>/AR_Pose_<stem>.txt`, `export/<stem>/posecam_export.json` | `export/<stem>/` | small |

`<stem>` is `yyyy-MM-dd-HH_mm_ss-<6 hex>-s<N>`. The app's `cloud/path-rules.json` lists the same vectors and
`cloud/check_backend_rules.py` checks this module against it.

### Uploading (client contract)

1. `presign` returns `url`, `method: PUT`, and `headers`. Send **exactly** those headers (they are the signed
   ones: `Content-Type` and `x-amz-checksum-sha256`; `Host` is set by your HTTP client). A mismatch gives S3
   `403 SignatureDoesNotMatch` / `BadDigest`.
2. For `MULTIPART`: `multipart/start` -> for each part `multipart/parts` -> `PUT` each part with the returned headers
   (collect the `ETag` response header and use the SHA-256 you sent) -> `multipart/complete`.
3. Always finish a file with `verify`. SINGLE uploads have no other post-upload call.
4. When every file is `VERIFIED`, call `sessions/{id}/complete`.

Integrity: single uploads are verified against the whole-file SHA-256 that S3 itself computed. Multipart objects
carry S3's *composite* checksum (a hash of the part hashes); the backend recomputes the expected composite from the
part checksums the client sent and compares. S3 does not support a full-object SHA-256 for multipart, so the
whole-file `sha256` of a multipart file is recorded but cannot be independently proven by the backend (the ETag is
never used as an integrity check).

## DynamoDB items (single table, `PK`/`SK`)

* `SESSION#<id>` / `META`: sessionId, **pipe**, deviceId, createdAt, recordingStatus, cloudStatus (`CREATED` -> `UPLOADING` ->
  `SYNCED`), totalFiles, verifiedFiles, totalBytes, verifiedBytes, completedAt, updatedAt (+ appVersion, serverCreatedAt)
* `SESSION#<id>` / `FILE#<relativePath>`: relativePath, s3Key, sizeBytes, sha256, state (`PRESIGNED` -> `UPLOADING` ->
  `UPLOADED` -> `VERIFIED`), uploadedAt, verifiedAt, multipartUploadId, partCount, partSizeBytes,
  expectedCompositeChecksum, contentType

Counters are only changed inside `TransactWriteItems` together with a conditional write on the file row, so repeated
presign/verify calls never double count.
