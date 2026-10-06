
from tests.helpers import meta, presign, s3_key, upload_single_and_verify, verify, new_session_id

M = b'{"m":1}'
S = b'{"s":2}'


def complete(client, sid, files, status="COMPLETED"):
    return client.post(f"/v1/sessions/{sid}/complete", json={"recordingStatus": status, "files": files})


def entries(*pairs):
    return [{"relativePath": p, "sizeBytes": n} for p, n in pairs]


def test_complete_ok_and_idempotent(client, fake_s3, session_id):
    upload_single_and_verify(client, fake_s3, session_id, "manifest.json", "metadata", "manifest.json", M)
    upload_single_and_verify(client, fake_s3, session_id, "intrinsics.json", "metadata", "intrinsics.json", S)
    files = entries(("manifest.json", len(M)), ("intrinsics.json", len(S)))

    r = complete(client, session_id, files)
    assert r.status_code == 200, r.text
    s = r.json()["session"]
    assert s["cloudStatus"] == "SYNCED" and s["completedAt"] and s["recordingStatus"] == "COMPLETED"
    assert (s["totalFiles"], s["verifiedFiles"]) == (2, 2)

    r2 = complete(client, session_id, files, status="SOMETHING_ELSE")
    assert r2.status_code == 200
    assert r2.json()["session"] == s  # unchanged, completedAt not rewritten
    assert meta(client, session_id)["completedAt"] == s["completedAt"]


def test_complete_incomplete_reports_missing_and_unverified(client, fake_s3, session_id):
    upload_single_and_verify(client, fake_s3, session_id, "manifest.json", "metadata", "manifest.json", M)
    presign(client, session_id, "intrinsics.json", S)  # registered, never verified
    files = entries(("manifest.json", len(M)), ("intrinsics.json", len(S)), ("poses.csv", 3))
    r = complete(client, session_id, files)
    assert r.status_code == 409
    err = r.json()["error"]
    assert err["code"] == "SESSION_INCOMPLETE" and err["retryable"] is False
    assert err["details"] == {"missing": ["poses.csv"], "unverified": ["intrinsics.json"]}
    assert meta(client, session_id)["cloudStatus"] == "UPLOADING"


def test_complete_size_mismatch_counts_as_unverified(client, fake_s3, session_id):
    upload_single_and_verify(client, fake_s3, session_id, "manifest.json", "metadata", "manifest.json", M)
    r = complete(client, session_id, entries(("manifest.json", len(M) + 1)))
    assert r.status_code == 409
    assert r.json()["error"]["details"] == {"missing": [], "unverified": ["manifest.json"]}


def test_extra_unverified_rows_do_not_block_completion(client, fake_s3, session_id):
    upload_single_and_verify(client, fake_s3, session_id, "manifest.json", "metadata", "manifest.json", M)
    presign(client, session_id, "export/2026-09-16-14_30_52-a3f9c1-s1/RGB_2026-09-16-14_30_52-a3f9c1-s1.mp4", b"video")  # late preview, not listed
    r = complete(client, session_id, entries(("manifest.json", len(M))))
    assert r.status_code == 200 and r.json()["session"]["cloudStatus"] == "SYNCED"


def test_complete_requires_files_and_valid_paths(client, session_id):
    r = complete(client, session_id, [])
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"
    r = complete(client, session_id, entries(("../evil", 1)))
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_PATH"


def test_complete_unknown_session(client):
    r = complete(client, new_session_id(), entries(("manifest.json", 1)))
    assert r.status_code == 404 and r.json()["error"]["code"] == "SESSION_NOT_FOUND"
