"""Recordings are filed under a pipe (white / black): the folder every file of the session goes to."""

import pytest

from tests.conftest import session_body
from tests.helpers import meta, presign, verify

SID = "capture-20260916T143052-a3f9c1"
OTHER = "capture-20260916T143053-b00001"
M = b'{"format_version": "posecam-5"}'
POSES = b"frame_index,timestamp_ns\n0,1\n"
CHUNK = b"PK-not-really-a-zip-but-bytes-are-bytes"


def _put_and_verify(client, fake_s3, sid, rel, key, data):
    r = presign(client, sid, rel, data)
    assert r.status_code == 200, r.text
    fake_s3.simulate_put(key, data)
    assert verify(client, sid, rel).status_code == 200


@pytest.mark.parametrize("pipe", ["white", "black"])
def test_files_are_filed_under_the_chosen_pipe_folder(client, fake_s3, pipe):
    assert client.post("/v1/sessions", json=session_body(SID, pipe=pipe)).status_code == 200

    _put_and_verify(client, fake_s3, SID, "manifest.json", f"sessions/{pipe}-pipe/{SID}/metadata/manifest.json", M)
    _put_and_verify(client, fake_s3, SID, "frames-00000.zip", f"sessions/{pipe}-pipe/{SID}/frames/frames-00000.zip", CHUNK)

    files = [{"relativePath": "manifest.json", "sizeBytes": len(M)}, {"relativePath": "frames-00000.zip", "sizeBytes": len(CHUNK)}]
    r = client.post(f"/v1/sessions/{SID}/complete", json={"recordingStatus": "complete", "files": files})
    assert r.status_code == 200 and r.json()["session"]["cloudStatus"] == "SYNCED"


def test_the_two_pipes_are_separate_folders(client, fake_s3):
    client.post("/v1/sessions", json=session_body(SID, pipe="white"))
    client.post("/v1/sessions", json=session_body(OTHER, pipe="black"))

    assert presign(client, SID, "poses.csv", POSES).status_code == 200
    assert presign(client, OTHER, "poses.csv", POSES).status_code == 200
    # An object in the other pipe's folder does not satisfy this session's verify.
    fake_s3.simulate_put(f"sessions/black-pipe/{SID}/tables/poses.csv", POSES)
    assert verify(client, SID, "poses.csv").status_code == 409
    fake_s3.simulate_put(f"sessions/white-pipe/{SID}/tables/poses.csv", POSES)
    assert verify(client, SID, "poses.csv").status_code == 200


def test_presigned_urls_point_into_the_pipe_folder(client):
    client.post("/v1/sessions", json=session_body(SID, pipe="black"))
    r = presign(client, SID, "manifest.json", M)
    assert f"/sessions/black-pipe/{SID}/metadata/manifest.json" in r.json()["url"]


@pytest.mark.parametrize("bad", ["green", "White", "white-pipe", "", "../x", None])
def test_a_missing_or_unknown_pipe_is_refused(client, bad):
    body = session_body(SID)
    if bad is None:
        del body["pipe"]
    else:
        body["pipe"] = bad
    r = client.post("/v1/sessions", json=body)
    assert r.status_code == 400 and r.json()["error"]["code"] == "INVALID_REQUEST"


def test_recreating_a_session_never_moves_it_to_another_pipe(client, fake_s3):
    client.post("/v1/sessions", json=session_body(SID, pipe="white"))
    r = client.post("/v1/sessions", json=session_body(SID, pipe="black"))  # a retried create with another choice
    assert r.status_code == 200 and r.json()["created"] is False
    _put_and_verify(client, fake_s3, SID, "manifest.json", f"sessions/white-pipe/{SID}/metadata/manifest.json", M)


def test_a_file_of_an_unknown_session_is_not_found(client):
    r = presign(client, SID, "manifest.json", M)
    assert r.status_code == 404 and r.json()["error"]["code"] == "SESSION_NOT_FOUND"


def test_a_full_session_with_both_small_and_large_files(client, fake_s3):
    client.post("/v1/sessions", json=session_body(SID, pipe="black"))
    r = presign(client, SID, "imu.csv", size=300 * 1024 * 1024, sha="a" * 64)  # over the small cap: imu.csv is a large file
    assert r.status_code == 200 and r.json()["mode"] == "MULTIPART"
    r = presign(client, SID, "poses.csv", size=300 * 1024 * 1024, sha="a" * 64)
    assert r.status_code == 413 and r.json()["error"]["code"] == "FILE_TOO_LARGE"
    assert meta(client, SID)["cloudStatus"] in ("CREATED", "UPLOADING")
