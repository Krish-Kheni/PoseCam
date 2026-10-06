"""Runtime configuration, read from environment variables.

No resource names are hard-coded: the bucket and table names are injected by
the SAM template (``!Ref``) or by the developer's shell for local runs.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from typing import Mapping

MiB = 1024 * 1024
GiB = 1024 * MiB

# S3 hard limits for multipart uploads.
S3_MIN_PART_SIZE = 5 * MiB
S3_MAX_PARTS = 10_000

AUTH_MODES = ("none", "device")

# Presigned URLs are meant to be short-lived. We refuse to configure anything
# longer than one hour.
MAX_PRESIGNED_TTL_SECONDS = 3600
CATALOG_BACKENDS = ("dynamodb", "local")


class ConfigError(RuntimeError):
    """Raised when the environment is missing or contains invalid settings."""


@dataclass(frozen=True)
class Settings:
    aws_region: str
    s3_bucket: str
    dynamodb_table: str = ""
    auth_mode: str = "none"
    presigned_url_ttl_seconds: int = 900
    multipart_threshold_bytes: int = 100 * MiB
    multipart_part_size_bytes: int = 16 * MiB
    max_small_file_bytes: int = 256 * MiB
    max_large_file_bytes: int = 4 * GiB
    s3_endpoint_url: str | None = None
    dynamodb_endpoint_url: str | None = None
    # "dynamodb" (default; required on Lambda) or "local" (SQLite file, development only).
    catalog_backend: str = "dynamodb"
    local_catalog_path: str = "local-catalog.db"

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Settings":
        env = os.environ if env is None else env

        def required(name: str) -> str:
            value = (env.get(name) or "").strip()
            if not value:
                raise ConfigError(f"Missing required environment variable {name}")
            return value

        def integer(name: str, default: int) -> int:
            raw = (env.get(name) or "").strip()
            if not raw:
                return default
            try:
                return int(raw)
            except ValueError as exc:
                raise ConfigError(f"{name} must be an integer, got {raw!r}") from exc

        def optional(name: str) -> str | None:
            value = (env.get(name) or "").strip()
            return value or None

        region = (env.get("AWS_REGION") or env.get("AWS_DEFAULT_REGION") or "").strip()
        if not region:
            raise ConfigError("Missing AWS_REGION (or AWS_DEFAULT_REGION)")

        auth_mode = (env.get("AUTH_MODE") or "none").strip().lower()
        if auth_mode not in AUTH_MODES:
            raise ConfigError(f"AUTH_MODE must be one of {AUTH_MODES}, got {auth_mode!r}")

        catalog_backend = (env.get("CATALOG_BACKEND") or "dynamodb").strip().lower()
        if catalog_backend not in CATALOG_BACKENDS:
            raise ConfigError(f"CATALOG_BACKEND must be one of {CATALOG_BACKENDS}, got {catalog_backend!r}")

        settings = cls(
            aws_region=region,
            s3_bucket=required("S3_BUCKET"),
            # Only the DynamoDB backend needs a table.
            dynamodb_table=required("DYNAMODB_TABLE") if catalog_backend == "dynamodb" else "",
            catalog_backend=catalog_backend,
            local_catalog_path=(env.get("LOCAL_CATALOG_PATH") or "local-catalog.db").strip(),
            auth_mode=auth_mode,
            presigned_url_ttl_seconds=integer("PRESIGNED_URL_TTL_SECONDS", 900),
            multipart_threshold_bytes=integer("MULTIPART_THRESHOLD_BYTES", 100 * MiB),
            multipart_part_size_bytes=integer("MULTIPART_PART_SIZE_BYTES", 16 * MiB),
            max_small_file_bytes=integer("MAX_SMALL_FILE_BYTES", 256 * MiB),
            max_large_file_bytes=integer("MAX_LARGE_FILE_BYTES", 4 * GiB),
            s3_endpoint_url=optional("S3_ENDPOINT_URL"),
            dynamodb_endpoint_url=optional("DYNAMODB_ENDPOINT_URL"),
        )
        settings.validate()
        if catalog_backend == "local" and env.get("AWS_LAMBDA_FUNCTION_NAME"):
            raise ConfigError(
                "CATALOG_BACKEND=local keeps data on the local disk and cannot be used on Lambda; use dynamodb"
            )
        return settings

    def validate(self) -> None:
        if not 1 <= self.presigned_url_ttl_seconds <= MAX_PRESIGNED_TTL_SECONDS:
            raise ConfigError(
                f"PRESIGNED_URL_TTL_SECONDS must be between 1 and {MAX_PRESIGNED_TTL_SECONDS}"
            )
        if self.multipart_part_size_bytes < S3_MIN_PART_SIZE:
            raise ConfigError(
                f"MULTIPART_PART_SIZE_BYTES must be >= {S3_MIN_PART_SIZE} (S3 minimum part size)"
            )
        if self.multipart_threshold_bytes < 1:
            raise ConfigError("MULTIPART_THRESHOLD_BYTES must be >= 1")
        if self.max_small_file_bytes < 0 or self.max_large_file_bytes < 0:
            raise ConfigError("MAX_*_FILE_BYTES must be >= 0")
        largest = max(self.max_small_file_bytes, self.max_large_file_bytes)
        if largest > self.multipart_part_size_bytes * S3_MAX_PARTS:
            raise ConfigError(
                "MULTIPART_PART_SIZE_BYTES is too small: the largest allowed file would need "
                f"more than {S3_MAX_PARTS} parts"
            )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Process-wide settings, read once per Lambda execution environment."""
    return Settings.from_env()
