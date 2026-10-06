"""Structured (JSON) audit logging. Presigned URLs and secrets must never be logged."""

from __future__ import annotations

import json
import logging
from contextvars import ContextVar
from typing import Any

request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)
principal_var: ContextVar[dict[str, Any] | None] = ContextVar("principal", default=None)

_audit_logger = logging.getLogger("posecam.audit")

_FORBIDDEN_KEYS = {"url", "presignedurl", "authorization", "x-amz-signature"}


def configure_logging() -> None:
    """INFO for our loggers; add a stdout handler when running outside Lambda."""
    root = logging.getLogger()
    if not root.handlers:
        logging.basicConfig(level=logging.INFO, format="%(message)s")
    logging.getLogger("posecam").setLevel(logging.INFO)


def audit(event: str, level: int = logging.INFO, **fields: Any) -> None:
    """Emit one JSON log line describing a security/audit relevant event."""
    safe = {k: v for k, v in fields.items() if k.lower() not in _FORBIDDEN_KEYS}
    record = {
        "event": event,
        "requestId": request_id_var.get(),
        "principal": principal_var.get(),
        **safe,
    }
    _audit_logger.log(level, json.dumps(record, default=str, separators=(",", ":")))
