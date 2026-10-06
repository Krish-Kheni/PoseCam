import dataclasses

import pytest

from app.auth import build_verifier
from app.auth.base import Principal
from app.auth.device_auth import NotImplementedDeviceAuthVerifier
from app.auth.no_auth import NoAuthVerifier, sanitize_device_id
from app.config import ConfigError
from tests.conftest import build_client, session_body
from tests.helpers import presign, new_session_id

ALL_REQUESTS = [
    ("post", "/v1/sessions", session_body("00000000-0000-4000-8000-000000000000")),
    ("get", "/v1/sessions/00000000-0000-4000-8000-000000000000", None),
    ("post", "/v1/sessions/00000000-0000-4000-8000-000000000000/complete",
     {"recordingStatus": "x", "files": [{"relativePath": "manifest.json", "sizeBytes": 1}]}),
    ("post", "/v1/sessions/00000000-0000-4000-8000-000000000000/uploads/presign",
     {"relativePath": "manifest.json", "sizeBytes": 1, "sha256": "a" * 64}),
    ("post", "/v1/sessions/00000000-0000-4000-8000-000000000000/uploads/multipart/start",
     {"relativePath": "manifest.json", "sizeBytes": 1, "sha256": "a" * 64}),
    ("post", "/v1/sessions/00000000-0000-4000-8000-000000000000/uploads/multipart/parts",
     {"relativePath": "manifest.json", "uploadId": "u", "parts": [{"partNumber": 1, "sizeBytes": 1, "checksumSha256": "a"}]}),
    ("post", "/v1/sessions/00000000-0000-4000-8000-000000000000/uploads/multipart/complete",
     {"relativePath": "manifest.json", "uploadId": "u", "parts": [{"partNumber": 1, "etag": "e", "checksumSha256": "a"}]}),
    ("post", "/v1/sessions/00000000-0000-4000-8000-000000000000/uploads/verify", {"relativePath": "manifest.json"}),
]


def test_auth_mode_none_works_and_device_header_is_observability_only(client):
    sid = new_session_id()
    body = session_body(sid)
    del body["deviceId"]
    r = client.post("/v1/sessions", json=body, headers={"X-Device-Id": "abc"})
    assert r.status_code == 200 and r.json()["session"]["deviceId"] == "abc"
    # works without any header too
    assert client.post("/v1/sessions", json=session_body(new_session_id())).status_code == 200
    assert presign(client, sid, "manifest.json", b"x").status_code == 200


@pytest.mark.parametrize("method,path,body", ALL_REQUESTS)
def test_auth_mode_device_fails_closed_on_every_endpoint(device_auth_client, method, path, body):
    r = getattr(device_auth_client, method)(path, **({"json": body} if body is not None else {}),
                                            headers={"X-Device-Id": "abc"})
    assert r.status_code == 501
    err = r.json()["error"]
    assert err["code"] == "AUTH_NOT_IMPLEMENTED"
    assert "not implemented" in err["message"]
    assert r.headers["x-request-id"]


def test_device_mode_does_not_touch_storage(device_auth_client, db, settings):
    device_auth_client.post("/v1/sessions", json=session_body(new_session_id()))
    assert db.all_items() == []


def test_verifier_selection():
    assert isinstance(build_verifier("none"), NoAuthVerifier)
    assert isinstance(build_verifier("device"), NotImplementedDeviceAuthVerifier)
    with pytest.raises(ConfigError):
        build_verifier("bogus")  # unknown mode never silently allows


def test_principal_in_none_mode():
    class R:  # minimal Request stand-in
        headers = {"x-device-id": "dev-1"}

    p = NoAuthVerifier().verify(R())  # type: ignore[arg-type]
    assert p == Principal(device_id="dev-1", subject=None, authenticated=False)


def test_device_id_sanitising():
    assert sanitize_device_id("Pixel 7 (abc)") == "Pixel 7 (abc)"
    assert sanitize_device_id("x" * 129) is None
    assert sanitize_device_id("bad\nid") is None
    assert sanitize_device_id("café") is None
    assert sanitize_device_id("") is None
    assert sanitize_device_id(None) is None
