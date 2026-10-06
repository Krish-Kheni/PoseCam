"""The session/file catalog interface used by :class:`~app.services.session_service.SessionService`.

Two implementations exist:

* :class:`~app.services.dynamodb_service.DynamoDBService` -- the real store (``CATALOG_BACKEND=dynamodb``,
  the default, and the only one that is safe on Lambda).
* :class:`~app.services.local_catalog.LocalCatalogStore` -- a single-file SQLite store for local development
  without a DynamoDB table (``CATALOG_BACKEND=local``).

Both must behave identically; the whole API test-suite runs against each of them.
"""

from __future__ import annotations

import enum
from typing import Any, Protocol


class CreateFileResult(enum.Enum):
    CREATED = "created"
    EXISTS = "exists"
    NO_SESSION = "no_session"


class VerifyTransition(enum.Enum):
    TRANSITIONED = "transitioned"
    ALREADY_VERIFIED = "already_verified"
    CONFLICT = "conflict"


class CatalogStore(Protocol):
    """Single-table catalog: one META row per session and one FILE# row per file. All writes idempotent."""

    def get_session(self, session_id: str) -> dict[str, Any] | None: ...

    def get_file(self, session_id: str, relative_path: str) -> dict[str, Any] | None: ...

    def list_files(self, session_id: str) -> list[dict[str, Any]]: ...

    def put_session_if_absent(self, item: dict[str, Any]) -> bool: ...

    def promote_to_uploading(self, session_id: str, now: str) -> None: ...

    def complete_session(
        self, session_id: str, recording_status: str, now: str
    ) -> dict[str, Any] | None: ...

    def create_file(self, session_id: str, file_item: dict[str, Any]) -> CreateFileResult: ...

    def retarget_file(
        self, session_id: str, old: dict[str, Any], new_size: int, new_sha256: str, now: str
    ) -> bool: ...

    def set_multipart_upload(
        self,
        session_id: str,
        relative_path: str,
        upload_id: str,
        part_count: int,
        part_size: int,
        expected_old_upload_id: str | None,
        now: str,
    ) -> bool: ...

    def mark_uploaded(
        self, session_id: str, relative_path: str, now: str, expected_composite_checksum: str | None
    ) -> bool: ...

    def mark_verified(self, session_id: str, file_row: dict[str, Any], now: str) -> VerifyTransition: ...

    def all_items(self) -> list[dict[str, Any]]:
        """Every stored row (debugging and tests only: a full scan)."""
        ...
