"""Service container + FastAPI dependencies (override these in tests)."""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache

from fastapi import Depends

from app.config import Settings, get_settings
from app.services.catalog import CatalogStore
from app.services.dynamodb_service import DynamoDBService, build_dynamodb_client
from app.services.local_catalog import LocalCatalogStore
from app.services.s3_service import S3Service, build_s3_client
from app.services.session_service import SessionService


@dataclass(frozen=True)
class Services:
    settings: Settings
    sessions: SessionService


@lru_cache(maxsize=4)
def _build_services(settings: Settings) -> Services:
    # boto3 clients are created once per Lambda execution environment and reused.
    db: CatalogStore
    if settings.catalog_backend == "local":
        db = LocalCatalogStore(settings.local_catalog_path)
    else:
        db = DynamoDBService(build_dynamodb_client(settings), settings.dynamodb_table)
    s3 = S3Service(build_s3_client(settings), settings.s3_bucket, settings.presigned_url_ttl_seconds)
    return Services(settings=settings, sessions=SessionService(db, s3, settings))


def get_services(settings: Settings = Depends(get_settings)) -> Services:
    return _build_services(settings)


def get_session_service(services: Services = Depends(get_services)) -> SessionService:
    return services.sessions
