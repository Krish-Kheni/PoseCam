
from tests.fakes import b64_sha256
from tests.helpers import meta, presign, s3_key, sha_hex, verify, new_session_id

REL = "intrinsics.json"
DATA = b'{"ok":true}'
KEY = lambda sid: s3_key(sid, "metadata", REL)  # noqa: E731


def test_verify_ok(client, fake_s3, session_id):
    presign(client, session_id, REL, DATA)
    fake_s3.simulate_put(KEY(session_id), DATA)
    r = verify(client, session_id, REL)
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["state"] == "VERIFIED" and j["sizeBytes"] == len(DATA) and j["verifiedAt"]
    f = client.get(f"/v1/sessions/{session_id}").json()["files"][0]
    assert f["state"] == "VERIFIED" and f["verifiedAt"] == j["verifiedAt"]
    m = meta(client, session_id)
    assert (m["totalFiles"], m["verifiedFiles"], m["verifiedBytes"]) == (1, 1, len(DATA))


def test_verify_without_complete_call_after_single_put(client, fake_s3, session_id):
    # SINGLE uploads: state is still PRESIGNED when the client calls verify.
    assert presign(client, session_id, REL, DATA).status_code == 200
    state = client.get(f"/v1/sessions/{session_id}").json()["files"][0]["state"]
    assert state == "PRESIGNED"
    fake_s3.simulate_put(KEY(session_id), DATA)
    assert verify(client, session_id, REL).status_code == 200


def test_verify_missing_object(client, session_id):
    presign(client, session_id, REL, DATA)
    r = verify(client, session_id, REL)
    assert r.status_code == 409
    err = r.json()["error"]
    assert err["code"] == "VERIFICATION_FAILED" and err["details"]["reason"] == "OBJECT_MISSING"
    assert meta(client, session_id)["verifiedFiles"] == 0


def test_verify_size_mismatch(client, fake_s3, session_id):
    presign(client, session_id, REL, DATA)
    fake_s3.simulate_put(KEY(session_id), DATA + b"extra")
    r = verify(client, session_id, REL)
    assert r.status_code == 409 and r.json()["error"]["details"]["reason"] == "SIZE_MISMATCH"
    assert r.json()["error"]["details"]["actualSizeBytes"] == len(DATA) + 5


def test_verify_checksum_mismatch(client, fake_s3, session_id):
    presign(client, session_id, REL, DATA)
    fake_s3.simulate_put(KEY(session_id), b"X" * len(DATA))  # same size, different bytes
    r = verify(client, session_id, REL)
    assert r.status_code == 409 and r.json()["error"]["details"]["reason"] == "CHECKSUM_MISMATCH"


def test_verify_checksum_absent_is_mismatch(client, fake_s3, session_id):
    presign(client, session_id, REL, DATA)
    fake_s3.simulate_put(KEY(session_id), DATA)
    del fake_s3.objects[KEY(session_id)]["ChecksumSHA256"]
    r = verify(client, session_id, REL)
    assert r.status_code == 409 and r.json()["error"]["details"]["reason"] == "CHECKSUM_MISMATCH"


def test_double_verify_does_not_double_count(client, fake_s3, session_id):
    presign(client, session_id, REL, DATA)
    fake_s3.simulate_put(KEY(session_id), DATA)
    first = verify(client, session_id, REL).json()
    for _ in range(3):
        again = verify(client, session_id, REL)
        assert again.status_code == 200
        assert again.json()["verifiedAt"] == first["verifiedAt"]
    m = meta(client, session_id)
    assert (m["verifiedFiles"], m["verifiedBytes"]) == (1, len(DATA))


def test_verify_not_found_cases(client, session_id):
    r = verify(client, session_id, REL)
    assert r.status_code == 404 and r.json()["error"]["code"] == "FILE_NOT_FOUND"
    r = verify(client, new_session_id(), REL)
    assert r.status_code == 404 and r.json()["error"]["code"] == "SESSION_NOT_FOUND"
    r = verify(client, session_id, "../x")
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_PATH"
