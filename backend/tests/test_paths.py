import pytest

from app.config import MiB, Settings
from app.errors import ApiError
from app.services import paths

SID = "capture-20260916T143052-a3f9c1"
STEM = "2026-09-16-14_30_52-a3f9c1-s1"


# --- session ids ---------------------------------------------------------------------
@pytest.mark.parametrize("raw", [SID, "capture-20991231T235959-000000", "capture-20260101T000000-ffffff"])
def test_valid_session_ids_are_returned_unchanged(raw):
    assert paths.normalize_session_id(raw) == raw


@pytest.mark.parametrize(
    "raw",
    [
        "",
        "capture-20260916T143052-A3F9C1",  # suffix must be lowercase hex
        "capture-20260916T143052-a3f9c",  # 5 hex
        "capture-20260916T143052-a3f9c1x",
        "capture-2026091T143052-a3f9c1",
        "capture-20260916T14305-a3f9c1",
        "capture-20260916T143052-a3f9c1\n",
        "capture-٢٠٢٦٠٩١٦T143052-a3f9c1",  # non-ASCII digits
        "../capture-20260916T143052-a3f9c1",
        "capture-20260916T143052-a3f9c1/",
        "123e4567-e89b-42d3-a456-426614174000",  # a UUID is not a PoseCam id
        "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        "my-notes",
        None,
        123,
    ],
)
def test_invalid_session_ids(raw):
    with pytest.raises(ApiError) as e:
        paths.normalize_session_id(raw)
    assert e.value.status_code == 400 and e.value.code == "INVALID_SESSION_ID"


# --- pipes -----------------------------------------------------------------------------
def test_pipe_folders():
    assert paths.pipe_folder("white") == "white-pipe"
    assert paths.pipe_folder("black") == "black-pipe"


@pytest.mark.parametrize("bad", ["", "green", "White", "BLACK", "white-pipe", "../x", None])
def test_unknown_pipes_are_refused(bad):
    with pytest.raises(ApiError) as e:
        paths.pipe_folder(bad)  # type: ignore[arg-type]
    assert e.value.code == "INVALID_REQUEST"


# --- allowed relative paths --------------------------------------------------------------
@pytest.mark.parametrize(
    "rel,subdir,name,size_class,ctype",
    [
        ("manifest.json", "metadata", "manifest.json", "small", "application/json"),
        ("device.json", "metadata", "device.json", "small", "application/json"),
        ("intrinsics.json", "metadata", "intrinsics.json", "small", "application/json"),
        ("poses.csv", "tables", "poses.csv", "small", "text/csv"),
        ("frame_metadata.csv", "tables", "frame_metadata.csv", "small", "text/csv"),
        ("imu.csv", "imu", "imu.csv", "large", "text/csv"),
        ("frames-00000.zip", "frames", "frames-00000.zip", "small", "application/zip"),
        ("frames-00042.zip", "frames", "frames-00042.zip", "small", "application/zip"),
        ("frames-123456.zip", "frames", "frames-123456.zip", "small", "application/zip"),
        (f"export/{STEM}/RGB_{STEM}.mp4", f"export/{STEM}", f"RGB_{STEM}.mp4", "large", "video/mp4"),
        (f"export/{STEM}/AR_Pose_{STEM}.txt", f"export/{STEM}", f"AR_Pose_{STEM}.txt", "small", "text/plain"),
        (f"export/{STEM}/posecam_export.json", f"export/{STEM}", "posecam_export.json", "small", "application/json"),
    ],
)
def test_allowed_paths(rel, subdir, name, size_class, ctype):
    cp = paths.classify_relative_path(rel)
    assert (cp.relative_path, cp.subdir, cp.name, cp.size_class, cp.content_type) == (rel, subdir, name, size_class, ctype)


@pytest.mark.parametrize("pipe", ["white", "black"])
def test_s3_key_is_built_only_from_validated_parts_inside_the_pipe_folder(pipe):
    cp = paths.classify_relative_path("frames-00003.zip")
    assert cp.s3_key(SID, pipe) == f"sessions/{pipe}-pipe/{SID}/frames/frames-00003.zip"
    cp = paths.classify_relative_path(f"export/{STEM}/posecam_export.json")
    assert cp.s3_key(SID, pipe) == f"sessions/{pipe}-pipe/{SID}/export/{STEM}/posecam_export.json"


def test_building_a_key_without_a_valid_pipe_fails_instead_of_guessing_a_folder():
    cp = paths.classify_relative_path("manifest.json")
    with pytest.raises(ApiError):
        cp.s3_key(SID, "green")


# --- rejected relative paths ---------------------------------------------------------------
@pytest.mark.parametrize(
    "rel",
    [
        # traversal
        "../manifest.json",
        "manifest.json/..",
        "imu.csv/../manifest.json",
        "a/../manifest.json",
        "..",
        ".",
        "..\\manifest.json",
        "%2e%2e/manifest.json",
        "..%2fmanifest.json",
        "manifest.json%00",
        # absolute
        "/manifest.json",
        "/etc/passwd",
        "//manifest.json",
        # URL-like
        "s3://bucket/key",
        "http://evil/manifest.json",
        "file:///etc/passwd",
        "C:manifest.json",
        "s3:manifest.json",
        "C:\\manifest.json",
        # NUL / control chars
        "manifest.json\x00",
        "man\x00ifest.json",
        "manifest.json\n",
        "manifest.json\r",
        "\tmanifest.json",
        "manifest.json\x7f",
        # empty segments / dot segments
        "",
        "export//x",
        "export/",
        "./manifest.json",
        "manifest.json/",
        # not on the allow-list
        "other.json",
        "Manifest.json",
        "MANIFEST.JSON",
        "manifest.json ",
        " manifest.json",
        "sessions/x/manifest.json",
        "metadata/manifest.json",
        "poses.csv.bak",
        "poses.csv.tmp",
        "Poses.csv",
        "scratch.tmp",
        "trajectory.png",
        "notes.txt",
        "frames/000000_1.jpg",  # individual JPEGs are never uploaded
        "frames-0000.zip",  # too few digits
        "frames-1234567.zip",  # too many digits
        "frames-00000.ZIP",
        "frames-00000.zip.tmp",
        "frames-0000a.zip",
        "frames-٠٠٠٠٠.zip",  # non-ASCII digits
        "imu/imu.csv",
        "imu.csv/",
        "imu.CSV",
        f"export/{STEM}/RGB_other-stem.mp4",  # the stem in the name must match the folder
        f"export/{STEM}/notes.txt",
        f"export/{STEM}/../manifest.json",
        f"export/{STEM}/RGB_{STEM}.mp4/extra",
        f"export/{STEM}/RGB_{STEM}.MP4",
        "export/2026-09-16-14_30_52-A3F9C1-s1/RGB_2026-09-16-14_30_52-A3F9C1-s1.mp4",
        "export/RGB_x.mp4",
        # length
        "frames-" + "0" * 200 + ".zip",
        "a" * 201,
    ],
)
def test_rejected_paths(rel):
    with pytest.raises(ApiError) as e:
        paths.classify_relative_path(rel)
    assert e.value.status_code == 400 and e.value.code == "INVALID_PATH"


def test_non_string_path_rejected():
    with pytest.raises(ApiError):
        paths.classify_relative_path(None)  # type: ignore[arg-type]


# --- sizes and checksums ------------------------------------------------------------------------
def _settings():
    return Settings("us-east-1", "b", "t", max_small_file_bytes=10 * MiB, max_large_file_bytes=20 * MiB)


def test_size_limits():
    small = paths.classify_relative_path("manifest.json")
    large = paths.classify_relative_path("imu.csv")
    paths.check_size(10 * MiB, small, _settings())
    paths.check_size(0, small, _settings())
    paths.check_size(20 * MiB, large, _settings())
    with pytest.raises(ApiError) as e:
        paths.check_size(10 * MiB + 1, small, _settings())
    assert e.value.status_code == 413 and e.value.code == "FILE_TOO_LARGE"
    with pytest.raises(ApiError) as e:
        paths.check_size(20 * MiB + 1, large, _settings())
    assert e.value.status_code == 413
    with pytest.raises(ApiError) as e:
        paths.check_size(-1, small, _settings())
    assert e.value.code == "INVALID_REQUEST"


def test_a_long_imu_recording_fits_the_large_class_but_not_the_small_one():
    # ~170 MB/h: a 2 h take would be ~340 MB, over the 256 MiB small cap, so imu.csv must be a large file.
    settings = Settings("us-east-1", "b", "t")
    paths.check_size(340 * MiB, paths.classify_relative_path("imu.csv"), settings)
    with pytest.raises(ApiError):
        paths.check_size(340 * MiB, paths.classify_relative_path("poses.csv"), settings)


def test_sha256_normalisation():
    assert paths.normalize_sha256("A" * 64) == "a" * 64
    for bad in ["", "a" * 63, "a" * 65, "g" * 64, None, "a" * 63 + "\n"]:
        with pytest.raises(ApiError) as e:
            paths.normalize_sha256(bad)
        assert e.value.code == "INVALID_REQUEST"
