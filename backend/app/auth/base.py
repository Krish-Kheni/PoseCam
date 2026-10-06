"""Authentication abstraction.

All authentication/authorisation decisions in the backend go through ONE
FastAPI dependency (``app.auth.get_principal``). Endpoints just declare
``principal: Principal = Depends(get_principal)`` and never branch on the auth
mode. To add real auth, implement :class:`AuthVerifier` and register it in
``app.auth.build_verifier`` for ``AUTH_MODE=device``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from starlette.requests import Request


@dataclass(frozen=True)
class Principal:
    """The caller, as established by the active :class:`AuthVerifier`.

    ``device_id`` is an *observability label* unless ``authenticated`` is True.
    """

    device_id: str | None
    subject: str | None
    authenticated: bool


class AuthVerifier(Protocol):
    """Strategy interface. Must raise ``ApiError`` (401/403/501) to reject a request."""

    def verify(self, request: Request) -> Principal: ...
