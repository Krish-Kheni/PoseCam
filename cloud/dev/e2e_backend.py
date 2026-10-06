"""TEST-ONLY launcher for the PoseCam end-to-end run (run from backend/). NOT part of the product.

moto's S3 server accepts the x-amz-checksum-sha256 header but does not store it, so HeadObject never reports a
checksum (real S3 does). This shim makes the emulator report the SHA-256 of the bytes it actually holds, so the
backend still verifies *the real object contents* against what the phone declared -- a stricter check than S3's own.
"""
import base64
import hashlib
import sys

import uvicorn

sys.path.insert(0, ".")
from app.services import s3_service  # noqa: E402

_orig = s3_service.S3Service.head_object


def head_object(self, key):
    head = _orig(self, key)
    if head is not None and not head.get("ChecksumSHA256"):
        body = self._client.get_object(Bucket=self._bucket, Key=key)["Body"].read()
        head = dict(head, ChecksumSHA256=base64.b64encode(hashlib.sha256(body).digest()).decode())
    return head


s3_service.S3Service.head_object = head_object
uvicorn.run("app.main:app", host="0.0.0.0", port=8000, log_level="warning")
