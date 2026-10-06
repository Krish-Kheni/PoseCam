from __future__ import annotations

import os

# Never talk to real AWS from tests.
os.environ.update(
    AWS_ACCESS_KEY_ID="testing",
    AWS_SECRET_ACCESS_KEY="testing",
    AWS_SESSION_TOKEN="testing",
    AWS_DEFAULT_REGION="us-east-1",
    AWS_EC2_METADATA_DISABLED="true",
)
for _name in ("S3_ENDPOINT_URL", "DYNAMODB_ENDPOINT_URL", "AUTH_MODE", "AWS_PROFILE"):
    os.environ.pop(_name, None)

import dataclasses  # noqa: E402
import uuid  # noqa: E402

import boto3  # noqa: E402
import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from moto import mock_aws  # noqa: E402

from app.config import MiB, Settings, get_settings  # noqa: E402
from app.deps import Services, get_services  # noqa: E402
from app.main import create_app  # noqa: E402
from app.services.dynamodb_service import DynamoDBService, build_dynamodb_client  # noqa: E402
from app.services.local_catalog import LocalCatalogStore  # noqa: E402
from app.services.s3_service import S3Service, build_s3_client  # noqa: E402
from app.services.session_service import SessionService  # noqa: E402

from tests.fakes import FakeS3Client  # noqa: E402
from tests.helpers import PIPE, new_session_id  # noqa: E402

BUCKET = "test-bucket"
TABLE = "test-table"
SHA_A = "a" * 64


@pytest.fixture
def settings() -> Settings:
    return Settings(
        aws_region="us-east-1",
        s3_bucket=BUCKET,
        dynamodb_table=TABLE,
        auth_mode="none",
        presigned_url_ttl_seconds=900,
        multipart_threshold_bytes=100 * MiB,
        multipart_part_size_bytes=5 * MiB,
        max_small_file_bytes=256 * MiB,
        max_large_file_bytes=4 * 1024 * MiB,
    )


@pytest.fixture
def ddb_raw(settings):
    """moto-backed DynamoDB with the same key schema as template.yaml."""
    with mock_aws():
        boto3.client("dynamodb", region_name="us-east-1").create_table(
            TableName=TABLE,
            AttributeDefinitions=[
                {"AttributeName": "PK", "AttributeType": "S"},
                {"AttributeName": "SK", "AttributeType": "S"},
            ],
            KeySchema=[
                {"AttributeName": "PK", "KeyType": "HASH"},
                {"AttributeName": "SK", "KeyType": "RANGE"},
            ],
            BillingMode="PAY_PER_REQUEST",
        )
        yield build_dynamodb_client(settings)


@pytest.fixture
def fake_s3(settings) -> FakeS3Client:
    return FakeS3Client(build_s3_client(settings), settings.s3_bucket)


@pytest.fixture(params=["dynamodb", "local"])
def db(request, settings, tmp_path):
    """The whole API suite runs against every catalog backend: they must behave identically."""
    if request.param == "local":
        return LocalCatalogStore(str(tmp_path / "catalog.db"))
    return DynamoDBService(request.getfixturevalue("ddb_raw"), settings.dynamodb_table)


def build_client(settings: Settings, db, fake_s3: FakeS3Client) -> TestClient:
    s3 = S3Service(fake_s3, settings.s3_bucket, settings.presigned_url_ttl_seconds)
    services = Services(settings=settings, sessions=SessionService(db, s3, settings))
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_services] = lambda: services
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def client(settings, db, fake_s3) -> TestClient:
    return build_client(settings, db, fake_s3)


@pytest.fixture
def device_auth_client(settings, db, fake_s3) -> TestClient:
    return build_client(dataclasses.replace(settings, auth_mode="device"), db, fake_s3)


@pytest.fixture
def session_id(client) -> str:
    sid = new_session_id()
    r = client.post("/v1/sessions", json=session_body(sid))
    assert r.status_code == 200, r.text
    return sid


def session_body(sid: str, **over) -> dict:
    body = {
        "sessionId": sid,
        "deviceId": "device-1",
        "createdAt": "2026-10-01T10:00:00Z",
        "recordingStatus": "RECORDING",
        "appVersion": "1.0.0",
        "pipe": PIPE,
    }
    body.update(over)
    return body
