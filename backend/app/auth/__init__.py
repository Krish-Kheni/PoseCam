"""Centralised authentication: one dependency, chosen by ``AUTH_MODE``."""

from __future__ import annotations

from fastapi import Depends, Request

from app.auth.base import AuthVerifier, Principal
from app.auth.device_auth import NotImplementedDeviceAuthVerifier
from app.auth.no_auth import NoAuthVerifier
from app.config import ConfigError, Settings, get_settings
from app.logging_utils import principal_var

__all__ = ["AuthVerifier", "Principal", "build_verifier", "get_principal", "get_auth_verifier"]


def build_verifier(auth_mode: str) -> AuthVerifier:
    if auth_mode == "none":
        return NoAuthVerifier()
    if auth_mode == "device":
        return NotImplementedDeviceAuthVerifier()
    # Unknown mode: fail closed.
    raise ConfigError(f"Unsupported AUTH_MODE {auth_mode!r}")


def get_auth_verifier(settings: Settings = Depends(get_settings)) -> AuthVerifier:
    return build_verifier(settings.auth_mode)


async def get_principal(
    request: Request, verifier: AuthVerifier = Depends(get_auth_verifier)
) -> Principal:
    """THE authentication dependency. Every /v1 endpoint depends on this."""
    principal = verifier.verify(request)
    # Exposed to the audit-logging middleware and to service-level audit logs. (This is an
    # ``async`` dependency on purpose: it runs in the request task, so the context variable
    # is visible to the threadpool-run endpoint that follows.)
    request.state.principal = principal
    principal_var.set(
        {"deviceId": principal.device_id, "subject": principal.subject, "authenticated": principal.authenticated}
    )
    return principal
