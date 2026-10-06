"""DynamoDB data access (single table). All writes are conditional/idempotent.

Table layout (PK, SK both strings)::

    PK = SESSION#<sessionId>  SK = META                    session catalog row + counters
    PK = SESSION#<sessionId>  SK = FILE#<relativePath>     one row per file

The low-level client is used everywhere (needed for ``transact_write_items``);
(de)serialisation goes through boto3's TypeSerializer/TypeDeserializer and all
numbers are converted back to ``int`` on the way out (no Decimal leaks).
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any

import boto3
from botocore.client import BaseClient
from botocore.config import Config
from botocore.exceptions import ClientError
from boto3.dynamodb.types import TypeDeserializer, TypeSerializer

from app.config import Settings
from app.services.catalog import CreateFileResult, VerifyTransition  # noqa: F401  (re-exported)

META_SK = "META"
FILE_SK_PREFIX = "FILE#"

_ser = TypeSerializer()
_deser = TypeDeserializer()


def session_pk(session_id: str) -> str:
    return f"SESSION#{session_id}"


def file_sk(relative_path: str) -> str:
    return f"{FILE_SK_PREFIX}{relative_path}"


def to_ddb(values: dict[str, Any]) -> dict[str, Any]:
    """Python dict -> DynamoDB attribute map (``None`` values are dropped)."""
    return {k: _ser.serialize(v) for k, v in values.items() if v is not None}


def from_ddb(item: dict[str, Any]) -> dict[str, Any]:
    """DynamoDB attribute map -> Python dict (Decimal -> int/float)."""
    return {k: _plain(_deser.deserialize(v)) for k, v in item.items()}


def _plain(value: Any) -> Any:
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    if isinstance(value, dict):
        return {k: _plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    return value


def build_dynamodb_client(settings: Settings) -> BaseClient:
    return boto3.client(
        "dynamodb",
        region_name=settings.aws_region,
        endpoint_url=settings.dynamodb_endpoint_url,
        config=Config(
            retries={"max_attempts": 3, "mode": "standard"}, connect_timeout=3, read_timeout=10
        ),
    )


def _is_condition_failure(exc: ClientError) -> bool:
    return exc.response.get("Error", {}).get("Code") == "ConditionalCheckFailedException"


def _cancellation_codes(exc: ClientError) -> list[str] | None:
    """Per-item codes of a canceled transaction, or None if this is not a cancellation."""
    if exc.response.get("Error", {}).get("Code") != "TransactionCanceledException":
        return None
    reasons = exc.response.get("CancellationReasons") or []
    return [str(r.get("Code", "None")) for r in reasons]


class DynamoDBService:
    def __init__(self, client: BaseClient, table: str) -> None:
        self._c = client
        self._t = table

    # -- keys ---------------------------------------------------------------------------
    def _meta_key(self, session_id: str) -> dict[str, Any]:
        return {"PK": {"S": session_pk(session_id)}, "SK": {"S": META_SK}}

    def _file_key(self, session_id: str, relative_path: str) -> dict[str, Any]:
        return {"PK": {"S": session_pk(session_id)}, "SK": {"S": file_sk(relative_path)}}

    # -- reads --------------------------------------------------------------------------
    def get_session(self, session_id: str) -> dict[str, Any] | None:
        resp = self._c.get_item(
            TableName=self._t, Key=self._meta_key(session_id), ConsistentRead=True
        )
        item = resp.get("Item")
        return from_ddb(item) if item else None

    def get_file(self, session_id: str, relative_path: str) -> dict[str, Any] | None:
        resp = self._c.get_item(
            TableName=self._t, Key=self._file_key(session_id, relative_path), ConsistentRead=True
        )
        item = resp.get("Item")
        return from_ddb(item) if item else None

    def list_files(self, session_id: str) -> list[dict[str, Any]]:
        """All FILE# rows of the session (paginates through LastEvaluatedKey)."""
        items: list[dict[str, Any]] = []
        kwargs: dict[str, Any] = {
            "TableName": self._t,
            "KeyConditionExpression": "PK = :pk AND begins_with(SK, :prefix)",
            "ExpressionAttributeValues": {
                ":pk": {"S": session_pk(session_id)},
                ":prefix": {"S": FILE_SK_PREFIX},
            },
            "ConsistentRead": True,
        }
        while True:
            resp = self._c.query(**kwargs)
            items.extend(from_ddb(i) for i in resp.get("Items", []))
            last = resp.get("LastEvaluatedKey")
            if not last:
                return items
            kwargs["ExclusiveStartKey"] = last

    def all_items(self) -> list[dict[str, Any]]:
        """Full table scan (debugging and tests only)."""
        items: list[dict[str, Any]] = []
        kwargs: dict[str, Any] = {"TableName": self._t, "ConsistentRead": True}
        while True:
            resp = self._c.scan(**kwargs)
            items.extend(from_ddb(i) for i in resp.get("Items", []))
            last = resp.get("LastEvaluatedKey")
            if not last:
                return items
            kwargs["ExclusiveStartKey"] = last

    # -- session writes -----------------------------------------------------------------
    def put_session_if_absent(self, item: dict[str, Any]) -> bool:
        """Conditional create. True if created, False if the META row already existed."""
        try:
            self._c.put_item(
                TableName=self._t,
                Item=to_ddb(item),
                ConditionExpression="attribute_not_exists(PK)",
            )
            return True
        except ClientError as exc:
            if _is_condition_failure(exc):
                return False
            raise

    def promote_to_uploading(self, session_id: str, now: str) -> None:
        """CREATED -> UPLOADING (never touches SYNCED). Idempotent, failure is harmless."""
        try:
            self._c.update_item(
                TableName=self._t,
                Key=self._meta_key(session_id),
                UpdateExpression="SET cloudStatus = :uploading, updatedAt = :now",
                ConditionExpression="cloudStatus = :created",
                ExpressionAttributeValues={
                    ":uploading": {"S": "UPLOADING"},
                    ":created": {"S": "CREATED"},
                    ":now": {"S": now},
                },
            )
        except ClientError as exc:
            if not _is_condition_failure(exc):
                raise

    def complete_session(
        self, session_id: str, recording_status: str, now: str
    ) -> dict[str, Any] | None:
        """Mark SYNCED (only if not already). Returns the new META item, None if already SYNCED."""
        try:
            resp = self._c.update_item(
                TableName=self._t,
                Key=self._meta_key(session_id),
                UpdateExpression=(
                    "SET recordingStatus = :rs, cloudStatus = :synced, "
                    "completedAt = if_not_exists(completedAt, :now), updatedAt = :now"
                ),
                ConditionExpression=(
                    "attribute_exists(PK) AND "
                    "(attribute_not_exists(cloudStatus) OR cloudStatus <> :synced)"
                ),
                ExpressionAttributeValues={
                    ":rs": {"S": recording_status},
                    ":synced": {"S": "SYNCED"},
                    ":now": {"S": now},
                },
                ReturnValues="ALL_NEW",
            )
            return from_ddb(resp["Attributes"])
        except ClientError as exc:
            if _is_condition_failure(exc):
                return None
            raise

    # -- file writes --------------------------------------------------------------------
    def create_file(self, session_id: str, file_item: dict[str, Any]) -> CreateFileResult:
        """Create a FILE row and bump META counters atomically; only the first creation counts."""
        size = int(file_item["sizeBytes"])
        items = [
            {
                "Put": {
                    "TableName": self._t,
                    "Item": to_ddb(file_item),
                    "ConditionExpression": "attribute_not_exists(PK)",
                }
            },
            {
                "Update": {
                    "TableName": self._t,
                    "Key": self._meta_key(session_id),
                    "UpdateExpression": (
                        "SET updatedAt = :now ADD totalFiles :one, totalBytes :size"
                    ),
                    # Update would otherwise create a phantom META row for an unknown session.
                    "ConditionExpression": "attribute_exists(PK)",
                    "ExpressionAttributeValues": {
                        ":now": {"S": file_item["updatedAt"]},
                        ":one": {"N": "1"},
                        ":size": {"N": str(size)},
                    },
                }
            },
        ]
        try:
            self._c.transact_write_items(TransactItems=items)
            return CreateFileResult.CREATED
        except ClientError as exc:
            codes = _cancellation_codes(exc)
            if codes is None:
                raise
            if codes and codes[0] == "ConditionalCheckFailed":
                return CreateFileResult.EXISTS
            if len(codes) > 1 and codes[1] == "ConditionalCheckFailed":
                return CreateFileResult.NO_SESSION
            # Reasons not reported (e.g. emulator): disambiguate by reading.
            if self.get_session(session_id) is None:
                return CreateFileResult.NO_SESSION
            return CreateFileResult.EXISTS

    def retarget_file(
        self,
        session_id: str,
        old: dict[str, Any],
        new_size: int,
        new_sha256: str,
        now: str,
    ) -> bool:
        """Re-register an existing row with different size/sha256 (a re-upload).

        Resets the row to PRESIGNED, drops multipart bookkeeping and adjusts META counters.
        Optimistic: fails (False) if the row changed since ``old`` was read.
        """
        old_size = int(old["sizeBytes"])
        was_verified = old["state"] == "VERIFIED"
        meta_values: dict[str, Any] = {
            ":now": {"S": now},
            ":delta": {"N": str(new_size - old_size)},
        }
        meta_expr = "SET updatedAt = :now ADD totalBytes :delta"
        if was_verified:
            meta_expr += ", verifiedFiles :minus_one, verifiedBytes :minus_old"
            meta_values[":minus_one"] = {"N": "-1"}
            meta_values[":minus_old"] = {"N": str(-old_size)}
        items = [
            {
                "Update": {
                    "TableName": self._t,
                    "Key": self._file_key(session_id, old["relativePath"]),
                    "UpdateExpression": (
                        "SET sizeBytes = :size, sha256 = :sha, #st = :presigned, updatedAt = :now "
                        "REMOVE multipartUploadId, partCount, partSizeBytes, "
                        "expectedCompositeChecksum, uploadedAt, verifiedAt"
                    ),
                    "ConditionExpression": (
                        "sizeBytes = :old_size AND sha256 = :old_sha AND #st = :old_state"
                    ),
                    "ExpressionAttributeNames": {"#st": "state"},
                    "ExpressionAttributeValues": {
                        ":size": {"N": str(new_size)},
                        ":sha": {"S": new_sha256},
                        ":presigned": {"S": "PRESIGNED"},
                        ":now": {"S": now},
                        ":old_size": {"N": str(old_size)},
                        ":old_sha": {"S": old["sha256"]},
                        ":old_state": {"S": old["state"]},
                    },
                }
            },
            {
                "Update": {
                    "TableName": self._t,
                    "Key": self._meta_key(session_id),
                    "UpdateExpression": meta_expr,
                    "ConditionExpression": "attribute_exists(PK)",
                    "ExpressionAttributeValues": meta_values,
                }
            },
        ]
        try:
            self._c.transact_write_items(TransactItems=items)
            return True
        except ClientError as exc:
            if _cancellation_codes(exc) is None:
                raise
            return False

    def set_multipart_upload(
        self,
        session_id: str,
        relative_path: str,
        upload_id: str,
        part_count: int,
        part_size: int,
        expected_old_upload_id: str | None,
        now: str,
    ) -> bool:
        """Persist the (single) live multipart upload id on the file row.

        Guard: succeeds only if the row has no upload id yet, or still has
        ``expected_old_upload_id``, and is not UPLOADED/VERIFIED.
        """
        values: dict[str, Any] = {
            ":uid": {"S": upload_id},
            ":pc": {"N": str(part_count)},
            ":ps": {"N": str(part_size)},
            ":uploading": {"S": "UPLOADING"},
            ":presigned": {"S": "PRESIGNED"},
            ":now": {"S": now},
        }
        if expected_old_upload_id is None:
            guard = "attribute_not_exists(multipartUploadId)"
        else:
            guard = "multipartUploadId = :old_uid"
            values[":old_uid"] = {"S": expected_old_upload_id}
        try:
            self._c.update_item(
                TableName=self._t,
                Key=self._file_key(session_id, relative_path),
                UpdateExpression=(
                    "SET multipartUploadId = :uid, partCount = :pc, partSizeBytes = :ps, "
                    "#st = :uploading, updatedAt = :now"
                ),
                ConditionExpression=f"{guard} AND (#st = :presigned OR #st = :uploading)",
                ExpressionAttributeNames={"#st": "state"},
                ExpressionAttributeValues=values,
            )
            return True
        except ClientError as exc:
            if _is_condition_failure(exc):
                return False
            raise

    def mark_uploaded(
        self,
        session_id: str,
        relative_path: str,
        now: str,
        expected_composite_checksum: str | None,
    ) -> bool:
        """PRESIGNED/UPLOADING -> UPLOADED. False if the row already moved on (idempotent)."""
        values: dict[str, Any] = {
            ":uploaded": {"S": "UPLOADED"},
            ":presigned": {"S": "PRESIGNED"},
            ":uploading": {"S": "UPLOADING"},
            ":now": {"S": now},
        }
        expr = "SET #st = :uploaded, uploadedAt = :now, updatedAt = :now"
        if expected_composite_checksum:
            expr += ", expectedCompositeChecksum = :cc"
            values[":cc"] = {"S": expected_composite_checksum}
        try:
            self._c.update_item(
                TableName=self._t,
                Key=self._file_key(session_id, relative_path),
                UpdateExpression=expr,
                ConditionExpression="#st = :presigned OR #st = :uploading",
                ExpressionAttributeNames={"#st": "state"},
                ExpressionAttributeValues=values,
            )
            return True
        except ClientError as exc:
            if _is_condition_failure(exc):
                return False
            raise

    def mark_verified(
        self, session_id: str, file_row: dict[str, Any], now: str
    ) -> VerifyTransition:
        """-> VERIFIED and META verified counters, in one transaction, exactly once."""
        size = int(file_row["sizeBytes"])
        items = [
            {
                "Update": {
                    "TableName": self._t,
                    "Key": self._file_key(session_id, file_row["relativePath"]),
                    "UpdateExpression": "SET #st = :verified, verifiedAt = :now, updatedAt = :now",
                    "ConditionExpression": (
                        "#st <> :verified AND sizeBytes = :size AND sha256 = :sha"
                    ),
                    "ExpressionAttributeNames": {"#st": "state"},
                    "ExpressionAttributeValues": {
                        ":verified": {"S": "VERIFIED"},
                        ":now": {"S": now},
                        ":size": {"N": str(size)},
                        ":sha": {"S": file_row["sha256"]},
                    },
                }
            },
            {
                "Update": {
                    "TableName": self._t,
                    "Key": self._meta_key(session_id),
                    "UpdateExpression": (
                        "SET updatedAt = :now ADD verifiedFiles :one, verifiedBytes :size"
                    ),
                    "ConditionExpression": "attribute_exists(PK)",
                    "ExpressionAttributeValues": {
                        ":now": {"S": now},
                        ":one": {"N": "1"},
                        ":size": {"N": str(size)},
                    },
                }
            },
        ]
        try:
            self._c.transact_write_items(TransactItems=items)
            return VerifyTransition.TRANSITIONED
        except ClientError as exc:
            if _cancellation_codes(exc) is None:
                raise
        # Condition failed: either already verified (fine) or the row changed under us.
        current = self.get_file(session_id, file_row["relativePath"])
        if (
            current
            and current["state"] == "VERIFIED"
            and current["sizeBytes"] == size
            and current["sha256"] == file_row["sha256"]
        ):
            return VerifyTransition.ALREADY_VERIFIED
        return VerifyTransition.CONFLICT
