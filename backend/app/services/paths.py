"""Validation and classification of client-supplied identifiers. SECURITY CRITICAL.

The client never supplies bucket names or S3 keys. It supplies a ``sessionId``, a ``pipe`` and a
``relativePath``; this module decides whether those are acceptable and maps them onto the one and only
S3 key layout::

    sessions/<pipe>-pipe/<sessionId>/<subdir>/<name>

Everything is allow-list based. The defensive "reject obviously hostile input" checks run first (so that
error paths never depend on regex subtleties), then the exact, case-sensitive allow-list regexes decide.
"""

from __future__ import annotations

import base64
import binascii
import re
from dataclasses import dataclass
from typing import Literal

from app import errors
from app.config import Settings

SizeClass = Literal["small", "large"]

MAX_RELATIVE_PATH_LENGTH = 200

# --- pipes --------------------------------------------------------------------
# The collector files each recording under a pipe when the take ends; the pipe is the cloud folder it goes to.
PIPES = ("white", "black")


def pipe_folder(pipe: str) -> str:
    """``white`` -> ``white-pipe``."""
    if pipe not in PIPES:
        raise errors.invalid_request(f"pipe must be one of {list(PIPES)}")
    return f"{pipe}-pipe"


# --- session id ---------------------------------------------------------------
# capture-YYYYMMDDTHHMMSS-xxxxxx: the app's local time stamp plus 6 lowercase hex characters. ASCII digits only.
_SESSION_RE = re.compile(r"capture-[0-9]{8}T[0-9]{6}-[0-9a-f]{6}", re.ASCII)


def normalize_session_id(raw: object) -> str:
    """Return the session id (already canonical: lowercase). Raises ``INVALID_SESSION_ID`` otherwise."""
    if isinstance(raw, str) and _SESSION_RE.fullmatch(raw):
        return raw
    raise errors.invalid_session_id()


# --- relative path allow-list ---------------------------------------------------
# NOTE: always use ``[0-9]`` and re.ASCII: ``\d`` would match non-ASCII digits.
_METADATA_NAMES = frozenset({"manifest.json", "device.json", "intrinsics.json"})
_TABLE_NAMES = frozenset({"poses.csv", "frame_metadata.csv"})
_IMU_NAME = "imu.csv"
# 1,000 JPEGs per chunk: frames-00000.zip holds frame indices 0..999, frames-00001.zip 1000..1999, ...
_CHUNK_RE = re.compile(r"frames-[0-9]{5,6}\.zip", re.ASCII)
# yyyy-MM-dd-HH_mm_ss-<6 hex session suffix>-s<segment>
_STEM = r"[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}_[0-9]{2}_[0-9]{2}-[0-9a-f]{6}-s[0-9]{1,3}"
_EXPORT_RE = re.compile(
    rf"export/(?P<stem>{_STEM})/"
    r"(?P<name>RGB_(?P=stem)\.mp4|AR_Pose_(?P=stem)\.txt|posecam_export\.json)",
    re.ASCII,
)
# A scheme prefix such as "s3:", "http:", "file:" (also catches "C:").
_SCHEME_RE = re.compile(r"[A-Za-z][A-Za-z0-9+.\-]*:")

_CONTENT_TYPES = {
    ".json": "application/json",
    ".csv": "text/csv",
    ".zip": "application/zip",
    ".txt": "text/plain",
    ".mp4": "video/mp4",
}


@dataclass(frozen=True)
class ClassifiedPath:
    relative_path: str
    subdir: str
    name: str
    size_class: SizeClass
    content_type: str

    def s3_key(self, session_id: str, pipe: str) -> str:
        return build_s3_key(session_id, self, pipe)


def build_s3_key(session_id: str, cp: ClassifiedPath, pipe: str) -> str:
    """The only place an S3 key is ever built. ``session_id`` must be normalised and ``pipe`` valid."""
    return f"sessions/{pipe_folder(pipe)}/{session_id}/{cp.subdir}/{cp.name}"


def _content_type_for(name: str) -> str:
    return _CONTENT_TYPES["." + name.rsplit(".", 1)[-1]]


def _reject_hostile(path: str) -> None:
    if path == "" or len(path) > MAX_RELATIVE_PATH_LENGTH:
        raise errors.invalid_path()
    # NUL / C0 / DEL / C1 control characters (includes \n, \r, \t).
    if any(ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F for c in path):
        raise errors.invalid_path()
    if "\\" in path:  # Windows separators
        raise errors.invalid_path()
    if "%" in path:  # percent-encoded traversal (%2e%2e, %2f, ...)
        raise errors.invalid_path()
    if path.startswith("/"):  # absolute
        raise errors.invalid_path()
    if "://" in path or _SCHEME_RE.match(path):  # URL-like
        raise errors.invalid_path()
    if ".." in path:  # traversal (also rejects "a..b", deliberately strict)
        raise errors.invalid_path()
    segments = path.split("/")
    if any(seg in ("", ".", "..") for seg in segments):  # empty or dot segments
        raise errors.invalid_path()


def classify_relative_path(raw: object) -> ClassifiedPath:
    """Validate ``raw`` and classify it. Raises ``INVALID_PATH`` if not allowed."""
    if not isinstance(raw, str):
        raise errors.invalid_path()
    _reject_hostile(raw)

    if raw in _METADATA_NAMES:
        return ClassifiedPath(raw, "metadata", raw, "small", _content_type_for(raw))
    if raw in _TABLE_NAMES:
        return ClassifiedPath(raw, "tables", raw, "small", _content_type_for(raw))
    if raw == _IMU_NAME:
        # ~170 MB/h: a long take would hit the small-file cap, so it is a large file.
        return ClassifiedPath(raw, "imu", raw, "large", _content_type_for(raw))
    if _CHUNK_RE.fullmatch(raw):
        return ClassifiedPath(raw, "frames", raw, "small", _content_type_for(raw))
    m = _EXPORT_RE.fullmatch(raw)
    if m:
        name = m.group("name")
        size_class: SizeClass = "large" if name.endswith(".mp4") else "small"
        return ClassifiedPath(raw, f"export/{m.group('stem')}", name, size_class, _content_type_for(name))
    raise errors.invalid_path()


# --- sizes / checksums ----------------------------------------------------------
def check_size(size_bytes: int, cp: ClassifiedPath, settings: Settings) -> None:
    if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes < 0:
        raise errors.invalid_request("sizeBytes must be an integer >= 0")
    limit = settings.max_small_file_bytes if cp.size_class == "small" else settings.max_large_file_bytes
    if size_bytes > limit:
        raise errors.file_too_large(
            f"File exceeds the {cp.size_class}-file limit of {limit} bytes",
            {"sizeBytes": size_bytes, "maxBytes": limit, "sizeClass": cp.size_class},
        )


_SHA256_HEX_RE = re.compile(r"[0-9a-fA-F]{64}", re.ASCII)


def normalize_sha256(raw: object) -> str:
    """64 hex chars (any case) -> lowercase hex."""
    if not isinstance(raw, str) or not _SHA256_HEX_RE.fullmatch(raw):
        raise errors.invalid_request("sha256 must be 64 hexadecimal characters")
    return raw.lower()


def sha256_hex_to_b64(sha256_hex: str) -> str:
    return base64.b64encode(bytes.fromhex(sha256_hex)).decode("ascii")


def normalize_checksum_b64(raw: object, field: str = "checksumSha256") -> str:
    """Validate a base64-encoded SHA-256 digest (32 bytes) and return it canonically."""
    if not isinstance(raw, str):
        raise errors.invalid_request(f"{field} must be a base64 string")
    try:
        decoded = base64.b64decode(raw, validate=True)
    except (binascii.Error, ValueError):
        raise errors.invalid_request(f"{field} must be valid base64") from None
    if len(decoded) != 32:
        raise errors.invalid_request(f"{field} must be the base64 of a 32-byte SHA-256 digest")
    return base64.b64encode(decoded).decode("ascii")
