"""An in-memory S3 client double for the calls moto cannot model faithfully.

moto (5.2) does not echo ``ChecksumSHA256`` from upload_part/list_parts, does not
store checksums for put_object and returns the composite checksum WITHOUT the
``-N`` suffix that real S3 returns. So the S3 *object* operations are faked here
with real-S3 semantics, while ``generate_presigned_url`` is delegated to a REAL
botocore client (presigning is purely local computation), so the presign logic
under test is the production code path. DynamoDB is tested against moto.
"""

from __future__ import annotations

import base64
import hashlib
from typing import Any

from botocore.exceptions import ClientError


def b64_sha256(data: bytes) -> str:
    return base64.b64encode(hashlib.sha256(data).digest()).decode()


def client_error(code: str, op: str, status: int = 400) -> ClientError:
    return ClientError(
        {"Error": {"Code": code, "Message": code}, "ResponseMetadata": {"HTTPStatusCode": status}}, op
    )


class FakeS3Client:
    def __init__(self, real_client: Any, bucket: str) -> None:
        self._real = real_client
        self.bucket = bucket
        self.objects: dict[str, dict[str, Any]] = {}
        self.uploads: dict[str, dict[str, Any]] = {}
        self.calls: list[str] = []
        self._counter = 0

    # ---- delegated, real botocore presigning -----------------------------------------
    def generate_presigned_url(self, *args: Any, **kwargs: Any) -> str:
        return self._real.generate_presigned_url(*args, **kwargs)

    # ---- S3 API surface used by S3Service ---------------------------------------------
    def create_multipart_upload(self, Bucket, Key, ContentType, ChecksumAlgorithm):  # noqa: N803
        self.calls.append("create_multipart_upload")
        assert Bucket == self.bucket and ChecksumAlgorithm == "SHA256"
        self._counter += 1
        upload_id = f"upload-{self._counter}"
        self.uploads[upload_id] = {"key": Key, "content_type": ContentType, "parts": {}}
        return {"UploadId": upload_id, "Bucket": Bucket, "Key": Key}

    def list_parts(self, Bucket, Key, UploadId, PartNumberMarker=0, MaxParts=1000):  # noqa: N803
        self.calls.append("list_parts")
        up = self.uploads.get(UploadId)
        if up is None or up["key"] != Key:
            raise client_error("NoSuchUpload", "ListParts", 404)
        numbers = sorted(n for n in up["parts"] if n > PartNumberMarker)
        page = numbers[:MaxParts]
        resp: dict[str, Any] = {"Parts": [dict(up["parts"][n], PartNumber=n) for n in page]}
        if len(numbers) > MaxParts:
            resp["IsTruncated"] = True
            resp["NextPartNumberMarker"] = page[-1]
        else:
            resp["IsTruncated"] = False
        return resp

    def complete_multipart_upload(self, Bucket, Key, UploadId, MultipartUpload):  # noqa: N803
        self.calls.append("complete_multipart_upload")
        up = self.uploads.get(UploadId)
        if up is None or up["key"] != Key:
            raise client_error("NoSuchUpload", "CompleteMultipartUpload", 404)
        digests = b""
        total = 0
        last = 0
        for p in MultipartUpload["Parts"]:
            if p["PartNumber"] <= last:
                raise client_error("InvalidPartOrder", "CompleteMultipartUpload")
            last = p["PartNumber"]
            stored = up["parts"].get(p["PartNumber"])
            if (
                stored is None
                or stored["ETag"] != p["ETag"]
                or stored["ChecksumSHA256"] != p["ChecksumSHA256"]
            ):
                raise client_error("InvalidPart", "CompleteMultipartUpload")
            digests += base64.b64decode(p["ChecksumSHA256"])
            total += stored["Size"]
        composite = base64.b64encode(hashlib.sha256(digests).digest()).decode()
        self.objects[Key] = {
            "ContentLength": total,
            "ChecksumSHA256": f"{composite}-{len(MultipartUpload['Parts'])}",
            "ChecksumType": "COMPOSITE",
        }
        del self.uploads[UploadId]
        return {"Bucket": Bucket, "Key": Key}

    def abort_multipart_upload(self, Bucket, Key, UploadId):  # noqa: N803
        self.calls.append("abort_multipart_upload")
        if UploadId not in self.uploads:
            raise client_error("NoSuchUpload", "AbortMultipartUpload", 404)
        del self.uploads[UploadId]
        return {}

    def head_object(self, Bucket, Key, ChecksumMode=None):  # noqa: N803
        self.calls.append("head_object")
        obj = self.objects.get(Key)
        if obj is None:
            raise client_error("404", "HeadObject", 404)
        return dict(obj) if ChecksumMode == "ENABLED" else {"ContentLength": obj["ContentLength"]}

    # ---- helpers that simulate what the Android client / S3 do ------------------------
    def simulate_put(self, key: str, data: bytes, checksum_b64: str | None = None) -> None:
        """Client PUT to the presigned URL: S3 stores the object and its SHA-256."""
        self.objects[key] = {
            "ContentLength": len(data),
            "ChecksumSHA256": checksum_b64 or b64_sha256(data),
            "ChecksumType": "FULL_OBJECT",
        }

    def simulate_upload_part(self, upload_id: str, part_number: int, data: bytes) -> dict[str, str]:
        etag = f'"{hashlib.md5(data).hexdigest()}"'  # noqa: S324 - mimics S3 ETag
        checksum = b64_sha256(data)
        self.uploads[upload_id]["parts"][part_number] = {
            "ETag": etag,
            "Size": len(data),
            "ChecksumSHA256": checksum,
        }
        return {"etag": etag, "checksumSha256": checksum}
