"""SQLite catalog for LOCAL DEVELOPMENT without DynamoDB (``CATALOG_BACKEND=local``).

Same single-table model and the same conditional/idempotent semantics as
:class:`~app.services.dynamodb_service.DynamoDBService`, kept in one SQLite file (or ``:memory:``).

NOT for production: the data lives on the machine running the API, so it is invalid on Lambda (the
config refuses it there) and it does not scale beyond a single process. Use DynamoDB for real deployments.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from typing import Any

from app.services.catalog import CreateFileResult, VerifyTransition

META_SK = "META"
FILE_SK_PREFIX = "FILE#"

_MULTIPART_FIELDS = (
    "multipartUploadId", "partCount", "partSizeBytes", "expectedCompositeChecksum", "uploadedAt", "verifiedAt",
)


def _pk(session_id: str) -> str:
    return f"SESSION#{session_id}"


class LocalCatalogStore:
    def __init__(self, path: str = "local-catalog.db") -> None:
        self._lock = threading.RLock()
        # One shared connection; every public method holds the lock, so access is serialised.
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.execute(
            "CREATE TABLE IF NOT EXISTS items (pk TEXT NOT NULL, sk TEXT NOT NULL, body TEXT NOT NULL, "
            "PRIMARY KEY (pk, sk))"
        )

    # -- primitives (callers hold the lock) ----------------------------------------------------
    def _get(self, pk: str, sk: str) -> dict[str, Any] | None:
        row = self._conn.execute("SELECT body FROM items WHERE pk = ? AND sk = ?", (pk, sk)).fetchone()
        return json.loads(row[0]) if row else None

    def _put(self, item: dict[str, Any]) -> None:
        self._conn.execute(
            "INSERT OR REPLACE INTO items (pk, sk, body) VALUES (?, ?, ?)",
            (item["PK"], item["SK"], json.dumps(item)),
        )

    class _Tx:
        """BEGIN IMMEDIATE ... COMMIT/ROLLBACK, so multi-row changes are atomic like a DynamoDB transaction."""

        def __init__(self, store: "LocalCatalogStore") -> None:
            self._store = store

        def __enter__(self) -> None:
            self._store._lock.acquire()
            self._store._conn.execute("BEGIN IMMEDIATE")

        def __exit__(self, exc_type, exc, tb) -> None:
            self._store._conn.execute("ROLLBACK" if exc_type else "COMMIT")
            self._store._lock.release()

    def _tx(self) -> "_Tx":
        return self._Tx(self)

    # -- reads ----------------------------------------------------------------------------------
    def get_session(self, session_id: str) -> dict[str, Any] | None:
        with self._lock:
            return self._get(_pk(session_id), META_SK)

    def get_file(self, session_id: str, relative_path: str) -> dict[str, Any] | None:
        with self._lock:
            return self._get(_pk(session_id), f"{FILE_SK_PREFIX}{relative_path}")

    def list_files(self, session_id: str) -> list[dict[str, Any]]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT body FROM items WHERE pk = ? AND sk >= ? AND sk < ? ORDER BY sk",
                (_pk(session_id), FILE_SK_PREFIX, FILE_SK_PREFIX[:-1] + chr(ord(FILE_SK_PREFIX[-1]) + 1)),
            ).fetchall()
        return [json.loads(r[0]) for r in rows]

    def all_items(self) -> list[dict[str, Any]]:
        with self._lock:
            return [json.loads(r[0]) for r in self._conn.execute("SELECT body FROM items ORDER BY pk, sk")]

    # -- session writes -------------------------------------------------------------------------
    def put_session_if_absent(self, item: dict[str, Any]) -> bool:
        with self._tx():
            if self._get(item["PK"], item["SK"]) is not None:
                return False
            self._put(item)
            return True

    def promote_to_uploading(self, session_id: str, now: str) -> None:
        with self._tx():
            meta = self._get(_pk(session_id), META_SK)
            if meta is not None and meta.get("cloudStatus") == "CREATED":
                meta.update(cloudStatus="UPLOADING", updatedAt=now)
                self._put(meta)

    def complete_session(self, session_id: str, recording_status: str, now: str) -> dict[str, Any] | None:
        with self._tx():
            meta = self._get(_pk(session_id), META_SK)
            if meta is None or meta.get("cloudStatus") == "SYNCED":
                return None
            meta.update(
                recordingStatus=recording_status,
                cloudStatus="SYNCED",
                completedAt=meta.get("completedAt") or now,
                updatedAt=now,
            )
            self._put(meta)
            return meta

    # -- file writes ----------------------------------------------------------------------------
    def create_file(self, session_id: str, file_item: dict[str, Any]) -> CreateFileResult:
        with self._tx():
            if self._get(file_item["PK"], file_item["SK"]) is not None:
                return CreateFileResult.EXISTS
            meta = self._get(_pk(session_id), META_SK)
            if meta is None:
                return CreateFileResult.NO_SESSION
            self._put(file_item)
            meta["totalFiles"] = int(meta.get("totalFiles", 0)) + 1
            meta["totalBytes"] = int(meta.get("totalBytes", 0)) + int(file_item["sizeBytes"])
            meta["updatedAt"] = file_item["updatedAt"]
            self._put(meta)
            return CreateFileResult.CREATED

    def retarget_file(self, session_id: str, old: dict[str, Any], new_size: int, new_sha256: str, now: str) -> bool:
        with self._tx():
            row = self._get(_pk(session_id), f"{FILE_SK_PREFIX}{old['relativePath']}")
            meta = self._get(_pk(session_id), META_SK)
            if (
                row is None or meta is None
                or row["sizeBytes"] != old["sizeBytes"] or row["sha256"] != old["sha256"]
                or row["state"] != old["state"]
            ):
                return False
            old_size = int(old["sizeBytes"])
            row.update(sizeBytes=new_size, sha256=new_sha256, state="PRESIGNED", updatedAt=now)
            for field in _MULTIPART_FIELDS:
                row.pop(field, None)
            self._put(row)
            meta["totalBytes"] = int(meta.get("totalBytes", 0)) + (new_size - old_size)
            if old["state"] == "VERIFIED":
                meta["verifiedFiles"] = int(meta.get("verifiedFiles", 0)) - 1
                meta["verifiedBytes"] = int(meta.get("verifiedBytes", 0)) - old_size
            meta["updatedAt"] = now
            self._put(meta)
            return True

    def set_multipart_upload(
        self, session_id: str, relative_path: str, upload_id: str, part_count: int, part_size: int,
        expected_old_upload_id: str | None, now: str,
    ) -> bool:
        with self._tx():
            row = self._get(_pk(session_id), f"{FILE_SK_PREFIX}{relative_path}")
            if row is None or row.get("state") not in ("PRESIGNED", "UPLOADING"):
                return False
            current = row.get("multipartUploadId")
            if expected_old_upload_id is None and current is not None:
                return False
            if expected_old_upload_id is not None and current != expected_old_upload_id:
                return False
            row.update(
                multipartUploadId=upload_id, partCount=part_count, partSizeBytes=part_size,
                state="UPLOADING", updatedAt=now,
            )
            self._put(row)
            return True

    def mark_uploaded(
        self, session_id: str, relative_path: str, now: str, expected_composite_checksum: str | None
    ) -> bool:
        with self._tx():
            row = self._get(_pk(session_id), f"{FILE_SK_PREFIX}{relative_path}")
            if row is None or row.get("state") not in ("PRESIGNED", "UPLOADING"):
                return False
            row.update(state="UPLOADED", uploadedAt=now, updatedAt=now)
            if expected_composite_checksum:
                row["expectedCompositeChecksum"] = expected_composite_checksum
            self._put(row)
            return True

    def mark_verified(self, session_id: str, file_row: dict[str, Any], now: str) -> VerifyTransition:
        size = int(file_row["sizeBytes"])
        with self._tx():
            row = self._get(_pk(session_id), f"{FILE_SK_PREFIX}{file_row['relativePath']}")
            meta = self._get(_pk(session_id), META_SK)
            if row is not None and meta is not None and row["state"] != "VERIFIED" \
                    and row["sizeBytes"] == size and row["sha256"] == file_row["sha256"]:
                row.update(state="VERIFIED", verifiedAt=now, updatedAt=now)
                self._put(row)
                meta["verifiedFiles"] = int(meta.get("verifiedFiles", 0)) + 1
                meta["verifiedBytes"] = int(meta.get("verifiedBytes", 0)) + size
                meta["updatedAt"] = now
                self._put(meta)
                return VerifyTransition.TRANSITIONED
            if row is not None and row["state"] == "VERIFIED" and row["sizeBytes"] == size \
                    and row["sha256"] == file_row["sha256"]:
                return VerifyTransition.ALREADY_VERIFIED
            return VerifyTransition.CONFLICT
