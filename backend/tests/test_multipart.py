import base64
import hashlib
from urllib.parse import parse_qs, urlsplit

import pytest

from app.config import MiB
from app.services.session_service import composite_checksum
from tests.fakes import b64_sha256
from tests.helpers import meta, s3_key, sha_hex, verify, new_session_id

REL = "imu.csv"
PART = 5 * MiB
# 2 full parts + a 1000 byte tail = 3 parts. Different byte patterns per part.
PARTS_DATA = [b"a" * PART, b"b" * PART, b"c" * 1000]
TOTAL = sum(len(p) for p in PARTS_DATA)
FILE_SHA = hashlib.sha256(b"".join(PARTS_DATA)).hexdigest()
URL = "/v1/sessions/{sid}/uploads/multipart/{op}"


def start(client, sid, *, size=TOTAL, sha=FILE_SHA, rel=REL):
    return client.post(
        URL.format(sid=sid, op="start"), json={"relativePath": rel, "sizeBytes": size, "sha256": sha}
    )


def parts_req(client, sid, upload_id, parts, rel=REL):
    return client.post(
        URL.format(sid=sid, op="parts"), json={"relativePath": rel, "uploadId": upload_id, "parts": parts}
    )


def complete(client, sid, upload_id, parts, rel=REL):
    return client.post(
        URL.format(sid=sid, op="complete"), json={"relativePath": rel, "uploadId": upload_id, "parts": parts}
    )


def upload_all(fake_s3, upload_id, upto=3):
    out = []
    for i, data in enumerate(PARTS_DATA[:upto], start=1):
        r = fake_s3.simulate_upload_part(upload_id, i, data)
        out.append({"partNumber": i, **r})
    return out


def test_start_creates_upload_and_row(client, fake_s3, session_id, settings):
    r = start(client, session_id)
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["relativePath"] == REL and j["resumed"] is False and j["uploadedParts"] == []
    assert j["partSizeBytes"] == settings.multipart_part_size_bytes and j["partCount"] == 3
    assert fake_s3.uploads[j["uploadId"]]["key"] == s3_key(session_id, "imu", REL)
    assert fake_s3.uploads[j["uploadId"]]["content_type"] == "text/csv"
    f = client.get(f"/v1/sessions/{session_id}").json()["files"][0]
    assert f["state"] == "UPLOADING"
    m = meta(client, session_id)
    assert (m["totalFiles"], m["totalBytes"]) == (1, TOTAL)


def test_start_is_idempotent_and_resumes(client, fake_s3, session_id):
    first = start(client, session_id).json()
    uid = first["uploadId"]
    uploaded = upload_all(fake_s3, uid, upto=2)

    again = start(client, session_id).json()
    assert again["uploadId"] == uid and again["resumed"] is True
    assert [p["partNumber"] for p in again["uploadedParts"]] == [1, 2]
    assert again["uploadedParts"][0] == {
        "partNumber": 1, "sizeBytes": PART, "etag": uploaded[0]["etag"],
        "checksumSha256": uploaded[0]["checksumSha256"],
    }
    assert len(fake_s3.uploads) == 1  # only ONE live upload id
    assert meta(client, session_id)["totalFiles"] == 1


def test_start_resume_paginates_list_parts(client, fake_s3, session_id, monkeypatch):
    uid = start(client, session_id).json()["uploadId"]
    upload_all(fake_s3, uid)
    original = fake_s3.list_parts
    monkeypatch.setattr(fake_s3, "list_parts", lambda **kw: original(**{**kw, "MaxParts": 1}))
    again = start(client, session_id).json()
    assert [p["partNumber"] for p in again["uploadedParts"]] == [1, 2, 3]


def test_start_recreates_upload_when_s3_lost_it(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    del fake_s3.uploads[uid]  # expired / aborted by the lifecycle rule
    j = start(client, session_id).json()
    assert j["uploadId"] != uid and j["resumed"] is False and j["uploadedParts"] == []
    # the new id is the one persisted
    assert parts_req(client, session_id, j["uploadId"], [
        {"partNumber": 1, "sizeBytes": PART, "checksumSha256": b64_sha256(PARTS_DATA[0])}]).status_code == 200
    assert parts_req(client, session_id, uid, [
        {"partNumber": 1, "sizeBytes": PART, "checksumSha256": b64_sha256(PARTS_DATA[0])}]).status_code == 409


def test_start_with_changed_content_aborts_old_upload(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    j = start(client, session_id, size=PART + 10, sha="d" * 64).json()
    assert j["uploadId"] != uid and uid not in fake_s3.uploads
    assert j["partCount"] == 2
    assert meta(client, session_id)["totalBytes"] == PART + 10


def test_start_validation(client, session_id, settings):
    assert start(client, session_id, size=0).status_code == 400
    r = start(client, session_id, rel="manifest.json", size=10)  # small kinds may use multipart too
    assert r.status_code == 200 and r.json()["partCount"] == 1
    assert start(client, session_id, rel="../x").json()["error"]["code"] == "INVALID_PATH"
    r = start(client, session_id, rel="export/2026-09-16-14_30_52-a3f9c1-s1/RGB_2026-09-16-14_30_52-a3f9c1-s1.mp4", size=settings.max_large_file_bytes + 1)
    assert r.status_code == 413
    assert start(client, "bad-id").json()["error"]["code"] == "INVALID_SESSION_ID"


def test_start_session_not_found(client):
    import uuid
    r = start(client, new_session_id())
    assert r.status_code == 404 and r.json()["error"]["code"] == "SESSION_NOT_FOUND"


def test_parts_presign(client, session_id, settings):
    uid = start(client, session_id).json()["uploadId"]
    chk = [b64_sha256(p) for p in PARTS_DATA]
    r = parts_req(client, session_id, uid, [
        {"partNumber": 1, "sizeBytes": PART, "checksumSha256": chk[0]},
        {"partNumber": 3, "sizeBytes": 1000, "checksumSha256": chk[2]},
    ])
    assert r.status_code == 200, r.text
    parts = r.json()["parts"]
    assert [p["partNumber"] for p in parts] == [1, 3]
    for p, c in zip(parts, [chk[0], chk[2]]):
        u = urlsplit(p["url"])
        q = parse_qs(u.query)
        assert u.path == "/" + s3_key(session_id, "imu", REL)
        assert q["uploadId"] == [uid] and q["partNumber"] == [str(p["partNumber"])]
        assert int(q["X-Amz-Expires"][0]) <= settings.presigned_url_ttl_seconds
        signed = set(q["X-Amz-SignedHeaders"][0].split(";"))
        assert {k.lower() for k in p["headers"]} == signed - {"host"}
        assert p["headers"]["x-amz-checksum-sha256"] == c
        assert p["expiresInSeconds"] == settings.presigned_url_ttl_seconds


def test_parts_presign_validation(client, session_id):
    uid = start(client, session_id).json()["uploadId"]
    good = b64_sha256(PARTS_DATA[0])

    def call(parts, upload_id=uid):
        return parts_req(client, session_id, upload_id, parts)

    # wrong size for a full part, wrong size for the last part
    r = call([{"partNumber": 1, "sizeBytes": PART - 1, "checksumSha256": good}])
    assert r.status_code == 400 and r.json()["error"]["details"]["expectedSizeBytes"] == PART
    r = call([{"partNumber": 3, "sizeBytes": PART, "checksumSha256": good}])
    assert r.status_code == 400 and r.json()["error"]["details"]["expectedSizeBytes"] == 1000
    # bad part numbers
    for n in (0, 4, -1):
        r = call([{"partNumber": n, "sizeBytes": PART, "checksumSha256": good}])
        assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"
    # duplicate part numbers
    assert call([{"partNumber": 1, "sizeBytes": PART, "checksumSha256": good}] * 2).status_code == 400
    # bad checksum
    for bad in ("nope", base64.b64encode(b"short").decode()):
        assert call([{"partNumber": 1, "sizeBytes": PART, "checksumSha256": bad}]).status_code == 400
    # wrong upload id
    r = call([{"partNumber": 1, "sizeBytes": PART, "checksumSha256": good}], upload_id="other")
    assert r.status_code == 409 and r.json()["error"]["code"] == "UPLOAD_CONFLICT"
    # empty / too many parts
    assert call([]).status_code == 400
    many = [{"partNumber": 1, "sizeBytes": PART, "checksumSha256": good}] * 101
    assert call(many).status_code == 400


def test_parts_without_upload_or_file(client, session_id):
    from tests.helpers import presign
    good = b64_sha256(b"x")
    r = parts_req(client, session_id, "u", [{"partNumber": 1, "sizeBytes": 1, "checksumSha256": good}])
    assert r.status_code == 404 and r.json()["error"]["code"] == "FILE_NOT_FOUND"
    presign(client, session_id, REL, size=TOTAL, sha=FILE_SHA)  # row without an upload
    r = parts_req(client, session_id, "u", [{"partNumber": 1, "sizeBytes": PART, "checksumSha256": good}])
    assert r.status_code == 404 and r.json()["error"]["code"] == "UPLOAD_NOT_FOUND"


def test_complete_then_verify_flow_and_composite(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    parts = upload_all(fake_s3, uid)
    r = complete(client, session_id, uid, parts)
    assert r.status_code == 200, r.text
    assert r.json() == {"relativePath": REL, "state": "UPLOADED", "sizeBytes": TOTAL}
    f = client.get(f"/v1/sessions/{session_id}").json()["files"][0]
    assert f["state"] == "UPLOADED" and f["uploadedAt"]

    r = verify(client, session_id, REL)
    assert r.status_code == 200, r.text
    assert r.json()["state"] == "VERIFIED"
    m = meta(client, session_id)
    assert (m["verifiedFiles"], m["verifiedBytes"]) == (1, TOTAL)


def test_composite_checksum_formula():
    digests = [hashlib.sha256(p).digest() for p in PARTS_DATA]
    expected = base64.b64encode(hashlib.sha256(b"".join(digests)).digest()).decode() + "-3"
    assert composite_checksum([b64_sha256(p) for p in PARTS_DATA]) == expected


def test_complete_is_idempotent(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    parts = upload_all(fake_s3, uid)
    assert complete(client, session_id, uid, parts).status_code == 200
    fake_s3.calls.clear()
    r = complete(client, session_id, uid, parts)
    assert r.status_code == 200 and r.json()["state"] == "UPLOADED"
    assert fake_s3.calls == []  # no S3 call at all

    # also after verification
    assert verify(client, session_id, REL).status_code == 200
    assert complete(client, session_id, uid, parts).status_code == 200


def test_complete_after_lost_response_uses_head_object_fallback(client, fake_s3, session_id):
    """S3 completed, our row was not updated (crash/timeout); retry gets NoSuchUpload."""
    uid = start(client, session_id).json()["uploadId"]
    parts = upload_all(fake_s3, uid)
    key = s3_key(session_id, "imu", REL)
    fake_s3.complete_multipart_upload(
        Bucket=fake_s3.bucket, Key=key, UploadId=uid,
        MultipartUpload={"Parts": [{"PartNumber": p["partNumber"], "ETag": p["etag"],
                                    "ChecksumSHA256": p["checksumSha256"]} for p in parts]},
    )
    assert uid not in fake_s3.uploads
    r = complete(client, session_id, uid, parts)
    assert r.status_code == 200 and r.json()["state"] == "UPLOADED"
    assert verify(client, session_id, REL).status_code == 200  # composite expectation was persisted


def test_complete_no_such_upload_and_no_object(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    parts = upload_all(fake_s3, uid)
    del fake_s3.uploads[uid]
    r = complete(client, session_id, uid, parts)
    assert r.status_code == 404 and r.json()["error"]["code"] == "UPLOAD_NOT_FOUND"


def test_complete_no_such_upload_object_wrong_size(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    parts = upload_all(fake_s3, uid)
    del fake_s3.uploads[uid]
    fake_s3.simulate_put(s3_key(session_id, "imu", REL), b"tiny")
    r = complete(client, session_id, uid, parts)
    assert r.status_code == 404 and r.json()["error"]["code"] == "UPLOAD_NOT_FOUND"


def test_complete_validation(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    parts = upload_all(fake_s3, uid)
    # missing part
    r = complete(client, session_id, uid, parts[:2])
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"
    # out of order
    r = complete(client, session_id, uid, [parts[1], parts[0], parts[2]])
    assert r.status_code == 400
    # duplicated
    r = complete(client, session_id, uid, [parts[0], parts[0], parts[2]])
    assert r.status_code == 400
    # wrong upload id
    r = complete(client, session_id, "other", parts)
    assert r.status_code == 409 and r.json()["error"]["code"] == "UPLOAD_CONFLICT"
    # tampered checksum -> S3 rejects -> 409
    bad = [dict(p) for p in parts]
    bad[1]["checksumSha256"] = b64_sha256(b"tampered")
    r = complete(client, session_id, uid, bad)
    assert r.status_code == 409 and r.json()["error"]["code"] == "UPLOAD_CONFLICT"
    # the real parts still complete afterwards
    assert complete(client, session_id, uid, parts).status_code == 200


def test_start_after_uploaded_is_conflict(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    assert complete(client, session_id, uid, upload_all(fake_s3, uid)).status_code == 200
    r = start(client, session_id)
    assert r.status_code == 409 and r.json()["error"]["details"]["reason"] == "ALREADY_UPLOADED"
    assert verify(client, session_id, REL).status_code == 200
    r = start(client, session_id)
    assert r.status_code == 409 and r.json()["error"]["details"]["reason"] == "ALREADY_VERIFIED"


def test_multipart_checksum_mismatch_detected_at_verify(client, fake_s3, session_id):
    """Composite expectation is persisted; a different object under the same key is rejected."""
    uid = start(client, session_id).json()["uploadId"]
    assert complete(client, session_id, uid, upload_all(fake_s3, uid)).status_code == 200
    obj = fake_s3.objects[s3_key(session_id, "imu", REL)]
    obj["ChecksumSHA256"] = composite_checksum([b64_sha256(b"zzz")] * 3)
    r = verify(client, session_id, REL)
    assert r.status_code == 409 and r.json()["error"]["details"]["reason"] == "CHECKSUM_MISMATCH"


def test_multipart_verify_tolerates_full_object_checksum_type(client, fake_s3, session_id):
    uid = start(client, session_id).json()["uploadId"]
    assert complete(client, session_id, uid, upload_all(fake_s3, uid)).status_code == 200
    obj = fake_s3.objects[s3_key(session_id, "imu", REL)]
    obj["ChecksumSHA256"] = base64.b64encode(bytes.fromhex(FILE_SHA)).decode()
    obj["ChecksumType"] = "FULL_OBJECT"
    assert verify(client, session_id, REL).status_code == 200
