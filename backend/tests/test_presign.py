import base64
from urllib.parse import parse_qs, urlsplit

from app.config import MiB
from tests.helpers import meta, presign, s3_key, sha_hex, verify, new_session_id

DATA = b'{"hello":"world"}'


def test_presign_single_url_and_headers(client, session_id, settings):
    r = presign(client, session_id, "manifest.json", DATA)
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["mode"] == "SINGLE" and j["method"] == "PUT" and j["partSizeBytes"] is None
    assert j["relativePath"] == "manifest.json"
    assert j["expiresInSeconds"] == settings.presigned_url_ttl_seconds

    u = urlsplit(j["url"])
    q = parse_qs(u.query)
    assert u.scheme == "https"
    assert u.hostname == f"{settings.s3_bucket}.s3.amazonaws.com"
    assert u.path == "/" + s3_key(session_id, "metadata", "manifest.json")
    assert int(q["X-Amz-Expires"][0]) <= settings.presigned_url_ttl_seconds
    assert q["X-Amz-Algorithm"] == ["AWS4-HMAC-SHA256"]
    assert "X-Amz-Signature" in q

    signed = set(q["X-Amz-SignedHeaders"][0].split(";"))
    assert signed == {"content-type", "host", "x-amz-checksum-sha256"}
    # The headers we tell the client to send == signed headers minus Host (set by the HTTP client).
    returned = {k.lower(): v for k, v in j["headers"].items()}
    assert set(returned) == signed - {"host"}
    assert returned["content-type"] == "application/json"
    assert returned["x-amz-checksum-sha256"] == base64.b64encode(bytes.fromhex(sha_hex(DATA))).decode()
    # Nothing listed in headers is ALSO in the query string (i.e. nothing was hoisted).
    assert not any(h in {k.lower() for k in q} for h in returned)


def test_presign_never_includes_bucket_or_key_from_client(client, session_id):
    r = client.post(
        f"/v1/sessions/{session_id}/uploads/presign",
        json={"relativePath": "manifest.json", "sizeBytes": 1, "sha256": "a" * 64,
              "bucket": "evil", "key": "evil/key", "s3Key": "evil"},
    )
    assert r.status_code == 200
    path = urlsplit(r.json()["url"]).path
    assert "evil" not in r.json()["url"]
    assert path.startswith(f"/sessions/white-pipe/{session_id}/")


def test_presign_uses_per_file_content_types(client, session_id):
    cases = {
        "device.json": "application/json",
        "poses.csv": "text/csv",
        "frames-00000.zip": "application/zip",
        "export/2026-09-16-14_30_52-a3f9c1-s1/AR_Pose_2026-09-16-14_30_52-a3f9c1-s1.txt": "text/plain",
        "export/2026-09-16-14_30_52-a3f9c1-s1/RGB_2026-09-16-14_30_52-a3f9c1-s1.mp4": "video/mp4",
        "imu.csv": "text/csv",
    }
    for rel, ctype in cases.items():
        r = presign(client, session_id, rel, b"x")
        assert r.status_code == 200, (rel, r.text)
        assert r.json()["headers"]["Content-Type"] == ctype


def test_presign_session_not_found_and_no_phantom_meta(client, db, settings):
    sid = new_session_id()
    r = presign(client, sid, "manifest.json", DATA)
    assert r.status_code == 404 and r.json()["error"]["code"] == "SESSION_NOT_FOUND"
    # Neither a META nor a FILE row was created.
    assert db.all_items() == []


def test_presign_validation_errors(client, session_id):
    r = presign(client, session_id, "../manifest.json", DATA)
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_PATH"
    r = presign(client, session_id, "manifest.json", DATA, sha="zz")
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"
    r = presign(client, session_id, "manifest.json", size=-1, sha="a" * 64)
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"
    r = client.post(
        f"/v1/sessions/{session_id}/uploads/presign",
        json={"relativePath": "manifest.json", "sizeBytes": "12", "sha256": "a" * 64},
    )
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"


def test_presign_oversized_file_is_413(client, session_id, settings):
    r = presign(client, session_id, "manifest.json", size=settings.max_small_file_bytes + 1, sha="a" * 64)
    assert r.status_code == 413
    err = r.json()["error"]
    assert err["code"] == "FILE_TOO_LARGE" and err["retryable"] is False
    r = presign(client, session_id, "export/2026-09-16-14_30_52-a3f9c1-s1/RGB_2026-09-16-14_30_52-a3f9c1-s1.mp4", size=settings.max_large_file_bytes + 1, sha="a" * 64)
    assert r.status_code == 413
    # A large kind may exceed the small limit.
    r = presign(client, session_id, "export/2026-09-16-14_30_52-a3f9c1-s1/RGB_2026-09-16-14_30_52-a3f9c1-s1.mp4", size=settings.max_small_file_bytes + 1, sha="a" * 64)
    assert r.status_code == 200 and r.json()["mode"] == "MULTIPART"


def test_presign_multipart_mode_for_big_files(client, session_id, settings):
    r = presign(client, session_id, "imu.csv", size=settings.multipart_threshold_bytes, sha="c" * 64)
    j = r.json()
    assert j["mode"] == "MULTIPART" and j["url"] is None and j["headers"] == {}
    assert j["partSizeBytes"] == settings.multipart_part_size_bytes
    r = presign(client, session_id, "export/2026-09-16-14_30_52-a3f9c1-s1/RGB_2026-09-16-14_30_52-a3f9c1-s1.mp4", size=settings.multipart_threshold_bytes - 1, sha="c" * 64)
    assert r.json()["mode"] == "SINGLE"


def test_empty_file_is_allowed(client, session_id):
    r = presign(client, session_id, "poses.csv", b"")
    assert r.status_code == 200 and r.json()["mode"] == "SINGLE"


def test_presign_already_verified(client, fake_s3, session_id):
    assert presign(client, session_id, "manifest.json", DATA).status_code == 200
    fake_s3.simulate_put(s3_key(session_id, "metadata", "manifest.json"), DATA)
    assert verify(client, session_id, "manifest.json").status_code == 200
    r = presign(client, session_id, "manifest.json", DATA)
    assert r.status_code == 200
    j = r.json()
    assert j["mode"] == "ALREADY_VERIFIED" and j["url"] is None and j["headers"] == {}


def test_repeated_presign_does_not_double_count(client, session_id):
    for _ in range(5):
        assert presign(client, session_id, "manifest.json", DATA).status_code == 200
    m = meta(client, session_id)
    assert m["totalFiles"] == 1 and m["totalBytes"] == len(DATA)
    assert m["cloudStatus"] == "UPLOADING"
    presign(client, session_id, "intrinsics.json", b"123456")
    m = meta(client, session_id)
    assert m["totalFiles"] == 2 and m["totalBytes"] == len(DATA) + 6


def test_presign_with_changed_content_adjusts_counters(client, session_id):
    presign(client, session_id, "manifest.json", b"1234567890")
    r = presign(client, session_id, "manifest.json", b"abc")
    assert r.status_code == 200
    m = meta(client, session_id)
    assert (m["totalFiles"], m["totalBytes"]) == (1, 3)
    files = client.get(f"/v1/sessions/{session_id}").json()["files"]
    assert files[0]["sizeBytes"] == 3 and files[0]["sha256"] == sha_hex(b"abc")
    assert files[0]["state"] == "PRESIGNED"


def test_reupload_of_verified_file_with_new_content(client, fake_s3, session_id):
    old, new = b"0123456789", b"abcdefghijklmnop"
    presign(client, session_id, "manifest.json", old)
    fake_s3.simulate_put(s3_key(session_id, "metadata", "manifest.json"), old)
    assert verify(client, session_id, "manifest.json").status_code == 200
    m = meta(client, session_id)
    assert (m["verifiedFiles"], m["verifiedBytes"]) == (1, 10)

    r = presign(client, session_id, "manifest.json", new)
    assert r.json()["mode"] == "SINGLE"
    m = meta(client, session_id)
    assert (m["totalFiles"], m["totalBytes"], m["verifiedFiles"], m["verifiedBytes"]) == (1, 16, 0, 0)
    f = client.get(f"/v1/sessions/{session_id}").json()["files"][0]
    assert f["state"] == "PRESIGNED" and f["verifiedAt"] is None

    # Old object is still in S3 (size 10) -> verification must fail until the new one is uploaded.
    assert verify(client, session_id, "manifest.json").status_code == 409
    fake_s3.simulate_put(s3_key(session_id, "metadata", "manifest.json"), new)
    assert verify(client, session_id, "manifest.json").status_code == 200
    m = meta(client, session_id)
    assert (m["verifiedFiles"], m["verifiedBytes"]) == (1, 16)


def test_late_files_do_not_demote_synced_session(client, fake_s3, session_id):
    data = b"hello"
    presign(client, session_id, "manifest.json", data)
    fake_s3.simulate_put(s3_key(session_id, "metadata", "manifest.json"), data)
    verify(client, session_id, "manifest.json")
    r = client.post(
        f"/v1/sessions/{session_id}/complete",
        json={"recordingStatus": "COMPLETED", "files": [{"relativePath": "manifest.json", "sizeBytes": 5}]},
    )
    assert r.json()["session"]["cloudStatus"] == "SYNCED"
    presign(client, session_id, "export/2026-09-16-14_30_52-a3f9c1-s1/RGB_2026-09-16-14_30_52-a3f9c1-s1.mp4", b"late")
    m = meta(client, session_id)
    assert m["cloudStatus"] == "SYNCED" and m["totalFiles"] == 2
