"""Thin wrapper around boto3 S3: presigning plus the few object/multipart calls we need.

Presigned URLs are computed locally by botocore (no network call) with the
Lambda role's credentials. S3 is never listed to discover sessions/files.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any
from urllib.parse import parse_qs, urlsplit

import boto3
from botocore.client import BaseClient
from botocore.config import Config
from botocore.exceptions import ClientError

from app.config import Settings
from app.services.paths import sha256_hex_to_b64

logger = logging.getLogger("posecam.s3")

_NOT_FOUND_CODES = {"404", "NoSuchKey", "NotFound"}


class NoSuchUploadError(Exception):
    """The S3 multipart upload id is unknown (completed, aborted or expired)."""


class InvalidPartsError(Exception):
    """S3 rejected the part list (InvalidPart / InvalidPartOrder / EntityTooSmall ...)."""

    def __init__(self, s3_code: str, message: str) -> None:
        super().__init__(message)
        self.s3_code = s3_code


@dataclass(frozen=True)
class PresignedRequest:
    url: str
    headers: dict[str, str]
    expires_in_seconds: int


def build_s3_client(settings: Settings) -> BaseClient:
    config = Config(
        signature_version="s3v4",
        # Path style against custom endpoints (moto/MinIO/localstack), virtual-hosted on AWS.
        s3={"addressing_style": "path" if settings.s3_endpoint_url else "virtual"},
        retries={"max_attempts": 3, "mode": "standard"},
        connect_timeout=3,
        read_timeout=10,
        # Do not let botocore add default CRC32 flexible-checksum headers/params to presigned
        # requests: the only checksum we sign is the SHA-256 we ask for explicitly.
        request_checksum_calculation="when_required",
        response_checksum_validation="when_required",
    )
    return boto3.client(
        "s3",
        region_name=settings.aws_region,
        endpoint_url=settings.s3_endpoint_url,
        config=config,
    )


class S3Service:
    def __init__(self, client: BaseClient, bucket: str, ttl_seconds: int) -> None:
        self._client = client
        self._bucket = bucket
        self._ttl = ttl_seconds

    @property
    def ttl_seconds(self) -> int:
        return self._ttl

    # -- presigning --------------------------------------------------------------------
    def presign_put(self, key: str, content_type: str, sha256_hex: str) -> PresignedRequest:
        checksum_b64 = sha256_hex_to_b64(sha256_hex)
        url = self._client.generate_presigned_url(
            "put_object",
            Params={
                "Bucket": self._bucket,
                "Key": key,
                "ContentType": content_type,
                "ChecksumSHA256": checksum_b64,
            },
            ExpiresIn=self._ttl,
            HttpMethod="PUT",
        )
        headers = self._required_headers(
            url, {"content-type": content_type, "x-amz-checksum-sha256": checksum_b64}
        )
        return PresignedRequest(url, headers, self._ttl)

    def presign_part(
        self, key: str, upload_id: str, part_number: int, checksum_b64: str
    ) -> PresignedRequest:
        url = self._client.generate_presigned_url(
            "upload_part",
            Params={
                "Bucket": self._bucket,
                "Key": key,
                "UploadId": upload_id,
                "PartNumber": part_number,
                "ChecksumSHA256": checksum_b64,
            },
            ExpiresIn=self._ttl,
            HttpMethod="PUT",
        )
        headers = self._required_headers(url, {"x-amz-checksum-sha256": checksum_b64})
        return PresignedRequest(url, headers, self._ttl)

    @staticmethod
    def _required_headers(url: str, known: dict[str, str]) -> dict[str, str]:
        """Headers the client MUST send for the signature to validate.

        Derived from what botocore actually signed (``X-Amz-SignedHeaders``), so
        headers that botocore hoisted into the query string are not listed.
        ``host`` is signed too, but the HTTP client sets it from the URL.
        """
        query = parse_qs(urlsplit(url).query)
        signed = query.get("X-Amz-SignedHeaders", [""])[0].split(";")
        out: dict[str, str] = {}
        for name in signed:
            if not name or name == "host":
                continue
            if name not in known:
                # Fail loudly rather than hand the client an incomplete header list.
                raise RuntimeError(f"Unexpected signed header {name!r} in presigned URL")
            out["Content-Type" if name == "content-type" else name] = known[name]
        return out

    # -- multipart ---------------------------------------------------------------------
    def create_multipart_upload(self, key: str, content_type: str) -> str:
        resp = self._client.create_multipart_upload(
            Bucket=self._bucket,
            Key=key,
            ContentType=content_type,
            ChecksumAlgorithm="SHA256",
        )
        return resp["UploadId"]

    def list_parts(self, key: str, upload_id: str) -> list[dict[str, Any]]:
        """All uploaded parts (paginated). Raises :class:`NoSuchUploadError`."""
        parts: list[dict[str, Any]] = []
        marker = 0
        while True:
            try:
                resp = self._client.list_parts(
                    Bucket=self._bucket,
                    Key=key,
                    UploadId=upload_id,
                    PartNumberMarker=marker,
                    MaxParts=1000,
                )
            except ClientError as exc:
                if _code(exc) == "NoSuchUpload":
                    raise NoSuchUploadError(upload_id) from exc
                raise
            parts.extend(resp.get("Parts", []))
            if not resp.get("IsTruncated"):
                return parts
            marker = int(resp["NextPartNumberMarker"])

    def complete_multipart_upload(
        self, key: str, upload_id: str, parts: list[dict[str, Any]]
    ) -> None:
        """``parts``: [{"PartNumber", "ETag", "ChecksumSHA256"}] in ascending order."""
        try:
            self._client.complete_multipart_upload(
                Bucket=self._bucket,
                Key=key,
                UploadId=upload_id,
                MultipartUpload={"Parts": parts},
            )
        except ClientError as exc:
            code = _code(exc)
            if code == "NoSuchUpload":
                raise NoSuchUploadError(upload_id) from exc
            if code in {"InvalidPart", "InvalidPartOrder", "EntityTooSmall", "BadDigest", "InvalidRequest"}:
                raise InvalidPartsError(code, exc.response.get("Error", {}).get("Message", code)) from exc
            raise

    def abort_multipart_upload(self, key: str, upload_id: str) -> None:
        """Best effort; never raises."""
        try:
            self._client.abort_multipart_upload(Bucket=self._bucket, Key=key, UploadId=upload_id)
        except Exception as exc:  # noqa: BLE001 - best effort by design
            logger.warning("abort_multipart_upload failed: %s", _code(exc) if isinstance(exc, ClientError) else type(exc).__name__)

    # -- objects -----------------------------------------------------------------------
    def head_object(self, key: str) -> dict[str, Any] | None:
        """HeadObject with checksums. ``None`` if the object does not exist."""
        try:
            return self._client.head_object(Bucket=self._bucket, Key=key, ChecksumMode="ENABLED")
        except ClientError as exc:
            if _code(exc) in _NOT_FOUND_CODES:
                return None
            raise


def _code(exc: ClientError) -> str:
    return str(exc.response.get("Error", {}).get("Code", ""))
