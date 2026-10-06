"""AUTH_MODE=device: placeholder that FAILS CLOSED.

TODO(auth): implement per-device authentication here (for example a device
registration flow that issues a signed token / SigV4-style request signature,
verified in ``verify`` and returned as ``Principal(authenticated=True, ...)``).
Until then every request is rejected with 501 so that setting AUTH_MODE=device
can never silently behave like AUTH_MODE=none.
"""

from __future__ import annotations

from starlette.requests import Request

from app.auth.base import Principal
from app.errors import ApiError


class NotImplementedDeviceAuthVerifier:
    def verify(self, request: Request) -> Principal:
        raise ApiError(
            501,
            "AUTH_NOT_IMPLEMENTED",
            "AUTH_MODE=device is not implemented yet; refusing the request (fail closed)",
        )
