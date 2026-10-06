from __future__ import annotations

import hashlib
import secrets
from typing import Any

# Every recording is filed under a pipe; tests use "white" unless they are about pipes.
PIPE = "white"


def new_session_id() -> str:
    """A fresh PoseCam session id: capture-YYYYMMDDTHHMMSS-xxxxxx."""
    return f"capture-20260916T143052-{secrets.token_hex(3)}"


def sha_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def presign(client, sid: str, rel: str, data: bytes | None = None, *, size: int | None = None,
            sha: str | None = None, **kw):
    body = {
        "relativePath": rel,
        "sizeBytes": len(data) if size is None else size,
        "sha256": sha if sha is not None else sha_hex(data or b""),
    }
    return client.post(f"/v1/sessions/{sid}/uploads/presign", json=body, **kw)


def verify(client, sid: str, rel: str):
    return client.post(f"/v1/sessions/{sid}/uploads/verify", json={"relativePath": rel})


def s3_key(sid: str, subdir: str, name: str, pipe: str = PIPE) -> str:
    return f"sessions/{pipe}-pipe/{sid}/{subdir}/{name}"


def upload_single_and_verify(client, fake_s3, sid: str, rel: str, subdir: str, name: str, data: bytes) -> None:
    r = presign(client, sid, rel, data)
    assert r.status_code == 200 and r.json()["mode"] == "SINGLE", r.text
    fake_s3.simulate_put(s3_key(sid, subdir, name), data)
    r = verify(client, sid, rel)
    assert r.status_code == 200, r.text


def meta(client, sid: str) -> dict[str, Any]:
    return client.get(f"/v1/sessions/{sid}").json()["session"]
