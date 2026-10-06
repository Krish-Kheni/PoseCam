"""Business logic: session catalog + upload orchestration.

Layering: API routers -> SessionService -> (CatalogStore, S3Service).
Everything the client sends is validated here before it touches AWS, and every
S3 key is built here from validated parts (``paths.build_s3_key``).
"""

from __future__ import annotations

import base64
import hashlib
import logging
import math
from typing import Any

from app import errors
from app.auth.base import Principal
from app.auth.no_auth import sanitize_device_id
from app.config import S3_MAX_PARTS, Settings
from app.logging_utils import audit
from app.models.session import (
    CompleteSessionRequest,
    CompleteSessionResponse,
    CreateSessionRequest,
    CreateSessionResponse,
    FileView,
    GetSessionResponse,
    SessionView,
    UploadConfig,
)
from app.models.upload import (
    MultipartCompleteRequest,
    MultipartCompleteResponse,
    MultipartPartsRequest,
    MultipartPartsResponse,
    MultipartStartRequest,
    MultipartStartResponse,
    PartUrl,
    PresignRequest,
    PresignResponse,
    UploadedPart,
    VerifyRequest,
    VerifyResponse,
)
from app.services import paths
from app.services.catalog import CatalogStore, CreateFileResult, VerifyTransition
from app.services.dynamodb_service import file_sk, session_pk
from app.services.s3_service import InvalidPartsError, NoSuchUploadError, S3Service
from app.util import utc_now_iso

logger = logging.getLogger("posecam.session")

_MAX_REGISTER_ATTEMPTS = 4


def composite_checksum(part_checksums_b64: list[str]) -> str:
    """S3's COMPOSITE checksum for a multipart object: b64(sha256(concat(part digests)))-N."""
    digest = hashlib.sha256(b"".join(base64.b64decode(c) for c in part_checksums_b64)).digest()
    return f"{base64.b64encode(digest).decode('ascii')}-{len(part_checksums_b64)}"


def to_session_view(meta: dict[str, Any]) -> SessionView:
    status = meta.get("cloudStatus", "CREATED")
    if status == "CREATED" and int(meta.get("totalFiles", 0)) > 0:
        # The CREATED -> UPLOADING promotion is a separate best-effort write; derive it
        # here so the view is right even if that write has not landed yet.
        status = "UPLOADING"
    return SessionView(
        sessionId=meta["sessionId"],
        deviceId=meta["deviceId"],
        createdAt=meta["createdAt"],
        recordingStatus=meta["recordingStatus"],
        cloudStatus=status,
        totalFiles=int(meta.get("totalFiles", 0)),
        verifiedFiles=int(meta.get("verifiedFiles", 0)),
        totalBytes=int(meta.get("totalBytes", 0)),
        verifiedBytes=int(meta.get("verifiedBytes", 0)),
        completedAt=meta.get("completedAt"),
    )


class SessionService:
    def __init__(self, db: CatalogStore, s3: S3Service, settings: Settings) -> None:
        self._db = db
        self._s3 = s3
        self._settings = settings

    # ===================================================================================
    # Sessions
    # ===================================================================================
    def upload_config(self) -> UploadConfig:
        s = self._settings
        return UploadConfig(
            multipartThresholdBytes=s.multipart_threshold_bytes,
            partSizeBytes=s.multipart_part_size_bytes,
            presignedUrlTtlSeconds=s.presigned_url_ttl_seconds,
        )

    def create_session(
        self, req: CreateSessionRequest, principal: Principal
    ) -> CreateSessionResponse:
        session_id = paths.normalize_session_id(req.sessionId)
        device_id = self._resolve_device_id(req.deviceId, principal)
        pipe = self._resolve_pipe(req.pipe)
        now = utc_now_iso()
        item: dict[str, Any] = {
            "PK": session_pk(session_id),
            "SK": "META",
            "sessionId": session_id,
            "deviceId": device_id,
            "createdAt": req.createdAt,
            "recordingStatus": req.recordingStatus,
            "cloudStatus": "CREATED",
            "totalFiles": 0,
            "verifiedFiles": 0,
            "totalBytes": 0,
            "verifiedBytes": 0,
            "appVersion": req.appVersion,
            "serverCreatedAt": now,
            "updatedAt": now,
        }
        item["pipe"] = pipe
        created = self._db.put_session_if_absent(item)
        if created:
            meta = item
        else:
            meta = self._db.get_session(session_id)
            if meta is None:  # pragma: no cover - deleted between the two calls
                raise errors.session_conflict("Session changed concurrently, retry")
            if meta.get("deviceId") != device_id:
                audit(
                    "session_device_mismatch",
                    level=logging.WARNING,
                    sessionId=session_id,
                    storedDeviceId=meta.get("deviceId"),
                    requestDeviceId=device_id,
                )
        audit("session_create", sessionId=session_id, created=created)
        return CreateSessionResponse(
            session=to_session_view(meta), created=created, config=self.upload_config()
        )

    @staticmethod
    def _resolve_pipe(raw: str | None) -> str:
        """Every recording is filed under a pipe (the folder its files go to); the app only creates a session once chosen."""
        if raw is None:
            raise errors.invalid_request(f"pipe is required: one of {list(paths.PIPES)}")
        if raw not in paths.PIPES:
            raise errors.invalid_request(f"pipe must be one of {list(paths.PIPES)}")
        return raw

    def _key(self, session_id: str, cp: paths.ClassifiedPath) -> str:
        """The S3 key of a file: always inside the folder of the pipe the session was filed under."""
        meta = self._db.get_session(session_id)
        if meta is None:
            raise errors.session_not_found()
        return cp.s3_key(session_id, meta["pipe"])

    @staticmethod
    def _resolve_device_id(body_device_id: str | None, principal: Principal) -> str:
        if body_device_id is not None:
            cleaned = sanitize_device_id(body_device_id)
            if cleaned is None:
                raise errors.invalid_request(
                    "deviceId must be 1-128 printable ASCII characters"
                )
            return cleaned
        if principal.device_id:
            return principal.device_id
        raise errors.invalid_request("deviceId is required (body field or X-Device-Id header)")

    def get_session(self, raw_session_id: str) -> GetSessionResponse:
        session_id = paths.normalize_session_id(raw_session_id)
        meta = self._db.get_session(session_id)
        if meta is None:
            raise errors.session_not_found()
        files = sorted(self._db.list_files(session_id), key=lambda f: f["relativePath"])
        return GetSessionResponse(
            session=to_session_view(meta),
            files=[
                FileView(
                    relativePath=f["relativePath"],
                    sizeBytes=int(f["sizeBytes"]),
                    sha256=f["sha256"],
                    state=f["state"],
                    uploadedAt=f.get("uploadedAt"),
                    verifiedAt=f.get("verifiedAt"),
                )
                for f in files
            ],
        )

    def complete_session(
        self, raw_session_id: str, req: CompleteSessionRequest, principal: Principal
    ) -> CompleteSessionResponse:
        session_id = paths.normalize_session_id(raw_session_id)
        listed: dict[str, int] = {}
        for entry in req.files:
            cp = paths.classify_relative_path(entry.relativePath)
            listed[cp.relative_path] = entry.sizeBytes
        meta = self._db.get_session(session_id)
        if meta is None:
            raise errors.session_not_found()
        if meta.get("cloudStatus") == "SYNCED":
            return CompleteSessionResponse(session=to_session_view(meta))

        rows = {f["relativePath"]: f for f in self._db.list_files(session_id)}
        missing: list[str] = []
        unverified: list[str] = []
        for rel, size in sorted(listed.items()):
            row = rows.get(rel)
            if row is None:
                missing.append(rel)
            elif row["state"] != "VERIFIED" or int(row["sizeBytes"]) != size:
                unverified.append(rel)
        if missing or unverified:
            audit("session_complete_rejected", sessionId=session_id, missing=len(missing), unverified=len(unverified))
            raise errors.session_incomplete(missing, unverified)

        updated = self._db.complete_session(session_id, req.recordingStatus, utc_now_iso())
        if updated is None:  # a concurrent complete won: return the stored state
            updated = self._db.get_session(session_id) or meta
        audit("session_complete", sessionId=session_id, files=len(listed))
        return CompleteSessionResponse(session=to_session_view(updated))

    # ===================================================================================
    # Uploads
    # ===================================================================================
    def presign(
        self, raw_session_id: str, req: PresignRequest, principal: Principal
    ) -> PresignResponse:
        session_id, cp, sha, size = self._validate_descriptor(
            raw_session_id, req.relativePath, req.sizeBytes, req.sha256
        )
        row = self._register_file(session_id, cp, size, sha)
        key = self._key(session_id, cp)

        if row["state"] == "VERIFIED":
            mode, response = "ALREADY_VERIFIED", PresignResponse(
                relativePath=cp.relative_path, mode="ALREADY_VERIFIED", url=None,
                headers={}, expiresInSeconds=0, partSizeBytes=None,
            )
        elif size >= self._settings.multipart_threshold_bytes:
            mode, response = "MULTIPART", PresignResponse(
                relativePath=cp.relative_path, mode="MULTIPART", url=None, headers={},
                expiresInSeconds=0, partSizeBytes=self._settings.multipart_part_size_bytes,
            )
        else:
            signed = self._s3.presign_put(key, cp.content_type, sha)
            mode, response = "SINGLE", PresignResponse(
                relativePath=cp.relative_path, mode="SINGLE", url=signed.url,
                headers=signed.headers, expiresInSeconds=signed.expires_in_seconds,
                partSizeBytes=None,
            )
        audit("presign", sessionId=session_id, relativePath=cp.relative_path, sizeBytes=size, mode=mode)
        return response

    def multipart_start(
        self, raw_session_id: str, req: MultipartStartRequest, principal: Principal
    ) -> MultipartStartResponse:
        session_id, cp, sha, size = self._validate_descriptor(
            raw_session_id, req.relativePath, req.sizeBytes, req.sha256
        )
        if size <= 0:
            raise errors.invalid_request("sizeBytes must be > 0 for a multipart upload")
        part_size = self._settings.multipart_part_size_bytes
        if math.ceil(size / part_size) > S3_MAX_PARTS:
            raise errors.file_too_large(
                f"File would need more than {S3_MAX_PARTS} parts", {"sizeBytes": size}
            )
        key = self._key(session_id, cp)
        row = self._register_file(session_id, cp, size, sha)

        if row["state"] in ("VERIFIED", "UPLOADED"):
            raise errors.upload_conflict(
                "File is already uploaded; call verify instead of starting a new upload",
                {"reason": "ALREADY_VERIFIED" if row["state"] == "VERIFIED" else "ALREADY_UPLOADED",
                 "state": row["state"]},
            )

        old_upload_id: str | None = row.get("multipartUploadId")
        stored_part_size = int(row.get("partSizeBytes") or part_size)
        if old_upload_id:
            try:
                parts = self._s3.list_parts(key, old_upload_id)
            except NoSuchUploadError:
                parts = None
            if parts is not None:
                return self._start_response(cp, row, old_upload_id, parts, resumed=True)

        upload_id = self._s3.create_multipart_upload(key, cp.content_type)
        part_count = math.ceil(size / part_size)
        ok = self._db.set_multipart_upload(
            session_id, cp.relative_path, upload_id, part_count, part_size, old_upload_id, utc_now_iso()
        )
        if not ok:
            # Lost a race with a concurrent start: only ONE live upload id per file row.
            self._s3.abort_multipart_upload(key, upload_id)
            current = self._db.get_file(session_id, cp.relative_path)
            winner = current.get("multipartUploadId") if current else None
            if not winner:
                raise errors.upload_conflict("Concurrent upload start, retry")
            try:
                parts = self._s3.list_parts(key, winner)
            except NoSuchUploadError:
                raise errors.upload_conflict("Concurrent upload start, retry") from None
            return self._start_response(cp, current, winner, parts, resumed=True)
        audit("multipart_start", sessionId=session_id, relativePath=cp.relative_path, sizeBytes=size, partCount=part_count)
        row = {**row, "multipartUploadId": upload_id, "partCount": part_count, "partSizeBytes": part_size}
        return self._start_response(cp, row, upload_id, [], resumed=False)

    def _start_response(
        self, cp: paths.ClassifiedPath, row: dict[str, Any], upload_id: str,
        s3_parts: list[dict[str, Any]], resumed: bool,
    ) -> MultipartStartResponse:
        part_size = int(row.get("partSizeBytes") or self._settings.multipart_part_size_bytes)
        part_count = int(row.get("partCount") or math.ceil(int(row["sizeBytes"]) / part_size))
        return MultipartStartResponse(
            relativePath=cp.relative_path,
            uploadId=upload_id,
            partSizeBytes=part_size,
            partCount=part_count,
            resumed=resumed,
            uploadedParts=[
                UploadedPart(
                    partNumber=int(p["PartNumber"]),
                    sizeBytes=int(p["Size"]),
                    etag=p["ETag"],
                    checksumSha256=p.get("ChecksumSHA256"),
                )
                for p in sorted(s3_parts, key=lambda p: p["PartNumber"])
            ],
        )

    def multipart_parts(
        self, raw_session_id: str, req: MultipartPartsRequest, principal: Principal
    ) -> MultipartPartsResponse:
        session_id = paths.normalize_session_id(raw_session_id)
        cp = paths.classify_relative_path(req.relativePath)
        row = self._require_file(session_id, cp.relative_path)
        part_count, part_size = self._check_upload_id(row, req.uploadId)
        total = int(row["sizeBytes"])

        seen: set[int] = set()
        for p in req.parts:
            if p.partNumber in seen:
                raise errors.invalid_request("Duplicate partNumber", {"partNumber": p.partNumber})
            seen.add(p.partNumber)
            if not 1 <= p.partNumber <= part_count:
                raise errors.invalid_request(
                    f"partNumber must be between 1 and {part_count}",
                    {"partNumber": p.partNumber, "partCount": part_count},
                )
            expected = part_size if p.partNumber < part_count else total - part_size * (part_count - 1)
            if p.sizeBytes != expected:
                raise errors.invalid_request(
                    "sizeBytes does not match the expected size of this part",
                    {"partNumber": p.partNumber, "expectedSizeBytes": expected, "sizeBytes": p.sizeBytes},
                )
        key = self._key(session_id, cp)
        out = []
        for p in req.parts:
            checksum = paths.normalize_checksum_b64(p.checksumSha256)
            signed = self._s3.presign_part(key, req.uploadId, p.partNumber, checksum)
            out.append(
                PartUrl(partNumber=p.partNumber, url=signed.url, headers=signed.headers,
                        expiresInSeconds=signed.expires_in_seconds)
            )
        audit("multipart_parts_presign", sessionId=session_id, relativePath=cp.relative_path, parts=len(out))
        return MultipartPartsResponse(parts=out)

    def multipart_complete(
        self, raw_session_id: str, req: MultipartCompleteRequest, principal: Principal
    ) -> MultipartCompleteResponse:
        session_id = paths.normalize_session_id(raw_session_id)
        cp = paths.classify_relative_path(req.relativePath)
        row = self._require_file(session_id, cp.relative_path)
        size = int(row["sizeBytes"])
        if row["state"] in ("UPLOADED", "VERIFIED"):  # idempotent duplicate complete
            return MultipartCompleteResponse(relativePath=cp.relative_path, sizeBytes=size)

        part_count, _ = self._check_upload_id(row, req.uploadId)
        if len(req.parts) != part_count:
            raise errors.invalid_request(
                f"Expected exactly {part_count} parts", {"expectedPartCount": part_count, "received": len(req.parts)}
            )
        s3_parts: list[dict[str, Any]] = []
        checksums: list[str] = []
        for index, p in enumerate(req.parts, start=1):
            if p.partNumber != index:
                raise errors.invalid_request(
                    "parts must contain every part number exactly once, in ascending order",
                    {"expectedPartNumber": index, "partNumber": p.partNumber},
                )
            checksum = paths.normalize_checksum_b64(p.checksumSha256)
            checksums.append(checksum)
            s3_parts.append({"ETag": p.etag, "PartNumber": p.partNumber, "ChecksumSHA256": checksum})
        composite = composite_checksum(checksums)
        key = self._key(session_id, cp)

        try:
            self._s3.complete_multipart_upload(key, req.uploadId, s3_parts)
        except NoSuchUploadError:
            head = self._s3.head_object(key)
            if head is None or int(head.get("ContentLength", -1)) != size:
                raise errors.upload_not_found("Multipart upload no longer exists and no matching object was found") from None
            s3_sum = head.get("ChecksumSHA256")
            if s3_sum and "-" in s3_sum and s3_sum != composite:
                raise errors.upload_not_found("Existing object does not match this upload") from None
            # Already completed by an earlier (lost-response) call: treat as success.
        except InvalidPartsError as exc:
            raise errors.upload_conflict(
                "S3 rejected the part list; re-upload the affected parts", {"s3Code": exc.s3_code}
            ) from exc

        self._db.mark_uploaded(session_id, cp.relative_path, utc_now_iso(), composite)
        audit("multipart_complete", sessionId=session_id, relativePath=cp.relative_path, sizeBytes=size)
        return MultipartCompleteResponse(relativePath=cp.relative_path, sizeBytes=size)

    def verify(
        self, raw_session_id: str, req: VerifyRequest, principal: Principal
    ) -> VerifyResponse:
        session_id = paths.normalize_session_id(raw_session_id)
        cp = paths.classify_relative_path(req.relativePath)
        row = self._require_file(session_id, cp.relative_path)
        size = int(row["sizeBytes"])
        key = self._key(session_id, cp)

        head = self._s3.head_object(key)
        if head is None:
            audit("verify_failed", level=logging.WARNING, sessionId=session_id, relativePath=cp.relative_path, reason="OBJECT_MISSING")
            raise errors.verification_failed("OBJECT_MISSING", "Object not found in S3")
        actual_size = int(head.get("ContentLength", -1))
        if actual_size != size:
            audit("verify_failed", level=logging.WARNING, sessionId=session_id, relativePath=cp.relative_path, reason="SIZE_MISMATCH")
            raise errors.verification_failed(
                "SIZE_MISMATCH", "Object size differs from the registered size",
                expectedSizeBytes=size, actualSizeBytes=actual_size,
            )
        if not self._checksum_matches(row, head.get("ChecksumSHA256")):
            audit("verify_failed", level=logging.WARNING, sessionId=session_id, relativePath=cp.relative_path, reason="CHECKSUM_MISMATCH")
            raise errors.verification_failed(
                "CHECKSUM_MISMATCH", "Object checksum differs from the registered sha256"
            )

        now = utc_now_iso()
        outcome = self._db.mark_verified(session_id, row, now)
        if outcome is VerifyTransition.CONFLICT:
            raise errors.upload_conflict("File was re-registered concurrently; retry")
        verified_at = now
        if outcome is VerifyTransition.ALREADY_VERIFIED:
            current = self._db.get_file(session_id, cp.relative_path)
            verified_at = (current or {}).get("verifiedAt") or now
        audit("verify_ok", sessionId=session_id, relativePath=cp.relative_path, sizeBytes=size, firstTime=outcome is VerifyTransition.TRANSITIONED)
        return VerifyResponse(relativePath=cp.relative_path, sizeBytes=size, verifiedAt=verified_at)

    @staticmethod
    def _checksum_matches(row: dict[str, Any], s3_checksum: str | None) -> bool:
        """Compare S3's reported SHA-256 to what we expect.

        * plain object (single PUT, or FULL_OBJECT type): value == base64(sha256 bytes)
        * multipart COMPOSITE object: value == "<b64>-<N>" == persisted expectedCompositeChecksum
        The ETag is never used as an integrity check.
        """
        if not s3_checksum:
            return False
        if "-" in s3_checksum:
            expected = row.get("expectedCompositeChecksum")
            return bool(expected) and s3_checksum == expected
        return s3_checksum == paths.sha256_hex_to_b64(row["sha256"])

    # ===================================================================================
    # Shared helpers
    # ===================================================================================
    def _validate_descriptor(
        self, raw_session_id: str, raw_path: str, size: int, raw_sha: str
    ) -> tuple[str, paths.ClassifiedPath, str, int]:
        session_id = paths.normalize_session_id(raw_session_id)
        cp = paths.classify_relative_path(raw_path)
        paths.check_size(size, cp, self._settings)
        sha = paths.normalize_sha256(raw_sha)
        return session_id, cp, sha, size

    def _require_file(self, session_id: str, relative_path: str) -> dict[str, Any]:
        row = self._db.get_file(session_id, relative_path)
        if row is not None:
            return row
        if self._db.get_session(session_id) is None:
            raise errors.session_not_found()
        raise errors.file_not_found()

    @staticmethod
    def _check_upload_id(row: dict[str, Any], upload_id: str) -> tuple[int, int]:
        """Validate the client's uploadId against the row. Returns (partCount, partSize)."""
        stored = row.get("multipartUploadId")
        if not stored:
            raise errors.upload_not_found()
        if stored != upload_id:
            raise errors.upload_conflict("uploadId does not match the active upload for this file")
        if row["state"] in ("UPLOADED", "VERIFIED"):
            raise errors.upload_conflict(
                "Upload already completed", {"reason": "ALREADY_UPLOADED", "state": row["state"]}
            )
        return int(row["partCount"]), int(row["partSizeBytes"])

    def _register_file(
        self, session_id: str, cp: paths.ClassifiedPath, size: int, sha: str
    ) -> dict[str, Any]:
        """Idempotently upsert the FILE row (shared by presign and multipart/start).

        * new row: Put(attribute_not_exists) + META counters, in ONE transaction
        * same size+sha: untouched (counters never double count)
        * different size/sha: row reset to PRESIGNED, counters adjusted, old multipart aborted
        """
        key = self._key(session_id, cp)
        for _ in range(_MAX_REGISTER_ATTEMPTS):
            now = utc_now_iso()
            new_row = {
                "PK": session_pk(session_id),
                "SK": file_sk(cp.relative_path),
                "relativePath": cp.relative_path,
                "s3Key": key,
                "sizeBytes": size,
                "sha256": sha,
                "contentType": cp.content_type,
                "state": "PRESIGNED",
                "createdAt": now,
                "updatedAt": now,
            }
            result = self._db.create_file(session_id, new_row)
            if result is CreateFileResult.NO_SESSION:
                raise errors.session_not_found()
            if result is CreateFileResult.CREATED:
                self._db.promote_to_uploading(session_id, now)
                return new_row

            existing = self._db.get_file(session_id, cp.relative_path)
            if existing is None:  # pragma: no cover - vanished between calls
                continue
            if int(existing["sizeBytes"]) == size and existing["sha256"] == sha:
                return existing
            if self._db.retarget_file(session_id, existing, size, sha, now):
                old_upload = existing.get("multipartUploadId")
                if old_upload:
                    self._s3.abort_multipart_upload(key, old_upload)
                audit(
                    "file_reregistered", sessionId=session_id, relativePath=cp.relative_path,
                    oldSizeBytes=existing["sizeBytes"], sizeBytes=size, wasVerified=existing["state"] == "VERIFIED",
                )
                return {
                    **{k: v for k, v in existing.items() if k not in _RESET_ATTRS},
                    "sizeBytes": size, "sha256": sha, "state": "PRESIGNED", "updatedAt": now,
                }
        raise errors.upload_conflict("Concurrent modification of this file, retry")


_RESET_ATTRS = frozenset(
    {"multipartUploadId", "partCount", "partSizeBytes", "expectedCompositeChecksum", "uploadedAt", "verifiedAt"}
)
