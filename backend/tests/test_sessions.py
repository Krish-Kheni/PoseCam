from tests.helpers import new_session_id

import pytest

from tests.conftest import session_body


def test_create_session_is_idempotent(client, db, settings):
    sid = new_session_id()
    r1 = client.post("/v1/sessions", json=session_body(sid))
    assert r1.status_code == 200
    j1 = r1.json()
    assert j1["created"] is True
    assert j1["session"] == {
        "sessionId": sid,
        "deviceId": "device-1",
        "createdAt": "2026-10-01T10:00:00Z",
        "recordingStatus": "RECORDING",
        "cloudStatus": "CREATED",
        "totalFiles": 0,
        "verifiedFiles": 0,
        "totalBytes": 0,
        "verifiedBytes": 0,
        "completedAt": None,
    }
    assert j1["config"] == {
        "multipartThresholdBytes": settings.multipart_threshold_bytes,
        "partSizeBytes": settings.multipart_part_size_bytes,
        "presignedUrlTtlSeconds": 900,
    }
    assert r1.headers["x-request-id"]

    # Second call: different mutable fields and a different device must NOT overwrite.
    r2 = client.post(
        "/v1/sessions", json=session_body(sid, deviceId="other", recordingStatus="STOPPED")
    )
    assert r2.status_code == 200
    j2 = r2.json()
    assert j2["created"] is False
    assert j2["session"]["deviceId"] == "device-1"
    assert j2["session"]["recordingStatus"] == "RECORDING"

    items = db.all_items()
    assert len(items) == 1
    assert items[0]["PK"] == f"SESSION#{sid}" and items[0]["SK"] == "META"


def test_session_id_is_taken_exactly_as_sent_and_never_case_folded(client):
    # Ids are already lowercase; an uppercase one would be a different object, so it is refused instead of folded.
    sid = new_session_id()
    r = client.post("/v1/sessions", json=session_body(sid.upper()))
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_SESSION_ID"
    assert client.post("/v1/sessions", json=session_body(sid)).json()["created"] is True
    assert client.post("/v1/sessions", json=session_body(sid)).json()["created"] is False


def test_invalid_session_id(client):
    for bad in ["not-a-uuid", "../x", "1234", ""]:
        r = client.post("/v1/sessions", json=session_body(bad))
        assert r.status_code == 400, bad
        err = r.json()["error"]
        assert err["code"] == "INVALID_SESSION_ID" and err["retryable"] is False
    r = client.get("/v1/sessions/not-a-uuid")
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_SESSION_ID"
    r = client.post(
        "/v1/sessions/not-a-uuid/uploads/presign",
        json={"relativePath": "manifest.json", "sizeBytes": 1, "sha256": "a" * 64},
    )
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_SESSION_ID"


def test_validation_errors_are_remapped_to_400_invalid_request(client):
    r = client.post("/v1/sessions", json={"sessionId": new_session_id()})
    assert r.status_code == 400
    err = r.json()["error"]
    assert err["code"] == "INVALID_REQUEST"
    assert err["retryable"] is False
    assert {e["field"] for e in err["details"]["errors"]} >= {"createdAt", "recordingStatus"}
    assert r.headers["x-request-id"]

    r = client.post("/v1/sessions", content=b"{not json", headers={"content-type": "application/json"})
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"

    r = client.post("/v1/sessions", json=session_body(new_session_id(), createdAt="yesterday"))
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"


def test_device_id_falls_back_to_header_and_is_required(client):
    sid = new_session_id()
    body = session_body(sid)
    del body["deviceId"]
    r = client.post("/v1/sessions", json=body)
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"
    r = client.post("/v1/sessions", json=body, headers={"X-Device-Id": "hdr-device"})
    assert r.status_code == 200 and r.json()["session"]["deviceId"] == "hdr-device"


def test_get_session_and_not_found(client, session_id):
    r = client.get(f"/v1/sessions/{session_id}")
    assert r.status_code == 200
    assert r.json()["files"] == []
    assert r.json()["session"]["sessionId"] == session_id

    r = client.get(f"/v1/sessions/{new_session_id()}")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "SESSION_NOT_FOUND"


def test_get_session_lists_files_paginating(client, db, session_id):
    if not hasattr(db, "_c"):
        pytest.skip("patches the DynamoDB client; only meaningful for the DynamoDB backend")
    # More rows than a single Query page would return are fetched via LastEvaluatedKey.
    for i in range(30):
        rel = f"frames-{i:05d}.zip"
        r = client.post(
            f"/v1/sessions/{session_id}/uploads/presign",
            json={"relativePath": rel, "sizeBytes": 10, "sha256": "b" * 64},
        )
        assert r.status_code == 200
    # Force pagination: wrap the client so every Query returns one item at a time.
    original = db._c.query

    def tiny_pages(**kw):
        kw["Limit"] = 7
        return original(**kw)

    db._c.query = tiny_pages
    try:
        r = client.get(f"/v1/sessions/{session_id}")
    finally:
        db._c.query = original
    files = r.json()["files"]
    assert len(files) == 30
    assert [f["relativePath"] for f in files] == sorted(f["relativePath"] for f in files)
    assert set(files[0]) == {"relativePath", "sizeBytes", "sha256", "state", "uploadedAt", "verifiedAt"}
    assert r.json()["session"]["cloudStatus"] == "UPLOADING"
    assert r.json()["session"]["totalFiles"] == 30


def test_unknown_route_has_error_envelope(client):
    r = client.get("/v1/nope")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_FOUND"
    assert r.headers["x-request-id"]


def test_health_is_unauthenticated(device_auth_client):
    assert device_auth_client.get("/v1/health").json() == {"status": "ok"}
