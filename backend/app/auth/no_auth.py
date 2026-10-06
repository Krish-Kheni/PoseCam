"""AUTH_MODE=none: no authentication (TEMPORARY).

TODO(security): ``X-Device-Id`` is a client-chosen string. It is recorded for
logging/observability ONLY. It is NOT authentication and must never be used
for an authorisation decision. Anyone who can reach the API can call every
endpoint. Replace with ``AUTH_MODE=device`` (see ``device_auth.py``) before
exposing this to untrusted users.
"""

from __future__ import annotations

import re

from starlette.requests import Request

from app.auth.base import Principal

DEVICE_ID_HEADER = "x-device-id"
_DEVICE_ID_RE = re.compile(r"[\x20-\x7E]{1,128}", re.ASCII)


def sanitize_device_id(value: str | None) -> str | None:
    """Accept printable ASCII up to 128 chars; anything else is dropped (never trusted)."""
    if value is None:
        return None
    value = value.strip()
    return value if _DEVICE_ID_RE.fullmatch(value) else None


class NoAuthVerifier:
    def verify(self, request: Request) -> Principal:
        return Principal(
            device_id=sanitize_device_id(request.headers.get(DEVICE_ID_HEADER)),
            subject=None,
            authenticated=False,
        )
