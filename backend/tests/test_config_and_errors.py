import dataclasses
from tests.helpers import new_session_id

import pytest
from botocore.exceptions import ClientError, EndpointConnectionError

from app.config import MiB, ConfigError, Settings
from tests.conftest import build_client, session_body

BASE = {"AWS_REGION": "eu-west-1", "S3_BUCKET": "b", "DYNAMODB_TABLE": "t"}


def test_settings_defaults():
    s = Settings.from_env(BASE)
    assert s.aws_region == "eu-west-1" and s.auth_mode == "none"
    assert s.presigned_url_ttl_seconds == 900
    assert s.multipart_threshold_bytes == 100 * MiB
    assert s.multipart_part_size_bytes == 16 * MiB
    assert s.max_small_file_bytes == 256 * MiB
    assert s.max_large_file_bytes == 4 * 1024 * MiB
    assert s.s3_endpoint_url is None and s.dynamodb_endpoint_url is None


def test_settings_region_fallback_and_overrides():
    env = {"AWS_DEFAULT_REGION": "ap-south-1", "S3_BUCKET": "b", "DYNAMODB_TABLE": "t",
           "AUTH_MODE": "DEVICE", "PRESIGNED_URL_TTL_SECONDS": "60",
           "S3_ENDPOINT_URL": "http://localhost:5000"}
    s = Settings.from_env(env)
    assert s.aws_region == "ap-south-1" and s.auth_mode == "device"
    assert s.presigned_url_ttl_seconds == 60 and s.s3_endpoint_url == "http://localhost:5000"


@pytest.mark.parametrize(
    "override",
    [
        {"S3_BUCKET": ""},
        {"DYNAMODB_TABLE": ""},
        {"AUTH_MODE": "bogus"},
        {"MULTIPART_PART_SIZE_BYTES": str(5 * MiB - 1)},
        {"MULTIPART_PART_SIZE_BYTES": "abc"},
        {"PRESIGNED_URL_TTL_SECONDS": "0"},
        {"PRESIGNED_URL_TTL_SECONDS": "86400"},
        {"MULTIPART_PART_SIZE_BYTES": str(5 * MiB), "MAX_LARGE_FILE_BYTES": str(5 * MiB * 10_001)},
    ],
)
def test_settings_validation(override):
    with pytest.raises(ConfigError):
        Settings.from_env({**BASE, **override})


def test_settings_requires_region():
    with pytest.raises(ConfigError):
        Settings.from_env({"S3_BUCKET": "b", "DYNAMODB_TABLE": "t"})


def test_misconfiguration_returns_500_not_a_crash(monkeypatch):
    from fastapi.testclient import TestClient
    from app.config import get_settings
    from app.main import create_app

    for name in ("S3_BUCKET", "DYNAMODB_TABLE"):
        monkeypatch.delenv(name, raising=False)
    get_settings.cache_clear()
    r = TestClient(create_app(), raise_server_exceptions=False).post(
        "/v1/sessions", json=session_body(new_session_id())
    )
    assert r.status_code == 500
    assert r.json()["error"]["code"] == "INTERNAL_ERROR" and r.json()["error"]["retryable"] is True
    get_settings.cache_clear()


def test_aws_throttling_maps_to_retryable_503(settings, db, fake_s3, monkeypatch):
    if not hasattr(db, "_c"):
        pytest.skip("patches the DynamoDB client; only meaningful for the DynamoDB backend")
    c = build_client(settings, db, fake_s3)

    def boom(**kw):
        raise ClientError({"Error": {"Code": "ProvisionedThroughputExceededException", "Message": "x"}}, "PutItem")

    monkeypatch.setattr(db._c, "put_item", boom)
    r = c.post("/v1/sessions", json=session_body(new_session_id()))
    assert r.status_code == 503
    err = r.json()["error"]
    assert err["code"] == "UPSTREAM_ERROR" and err["retryable"] is True
    assert r.headers["x-request-id"]


def test_unexpected_exception_is_500_retryable_without_leaking(settings, db, fake_s3, monkeypatch):
    if not hasattr(db, "_c"):
        pytest.skip("patches the DynamoDB client; only meaningful for the DynamoDB backend")
    c = build_client(settings, db, fake_s3)

    def boom(**kw):
        raise RuntimeError("secret internal detail")

    monkeypatch.setattr(db._c, "put_item", boom)
    r = c.post("/v1/sessions", json=session_body(new_session_id()))
    assert r.status_code == 500
    assert "secret" not in r.text
    assert r.json()["error"] == {
        "code": "INTERNAL_ERROR", "message": "Internal server error", "retryable": True, "details": None,
    }
    assert r.headers["x-request-id"]


def test_aws_connection_error_is_503(settings, db, fake_s3, monkeypatch):
    if not hasattr(db, "_c"):
        pytest.skip("patches the DynamoDB client; only meaningful for the DynamoDB backend")
    c = build_client(settings, db, fake_s3)

    def boom(**kw):
        raise EndpointConnectionError(endpoint_url="http://x")

    monkeypatch.setattr(db._c, "put_item", boom)
    r = c.post("/v1/sessions", json=session_body(new_session_id()))
    assert r.status_code == 503 and r.json()["error"]["retryable"] is True


def test_audit_log_never_contains_presigned_urls(client, session_id, caplog):
    import logging
    from tests.helpers import presign

    with caplog.at_level(logging.INFO):
        r = presign(client, session_id, "manifest.json", b"abc")
    url = r.json()["url"]
    joined = "\n".join(rec.getMessage() for rec in caplog.records)
    assert "presign" in joined
    assert "X-Amz-Signature" not in joined and url not in joined
    assert "http_request" in joined


def test_local_catalog_backend_needs_no_dynamodb_table():
    s = Settings.from_env({"AWS_REGION": "us-east-1", "S3_BUCKET": "b", "CATALOG_BACKEND": "local"})
    assert s.catalog_backend == "local" and s.dynamodb_table == "" and s.local_catalog_path == "local-catalog.db"


def test_dynamodb_backend_still_requires_a_table():
    with pytest.raises(ConfigError, match="DYNAMODB_TABLE"):
        Settings.from_env({"AWS_REGION": "us-east-1", "S3_BUCKET": "b"})


def test_unknown_catalog_backend_is_rejected():
    with pytest.raises(ConfigError, match="CATALOG_BACKEND"):
        Settings.from_env({"AWS_REGION": "us-east-1", "S3_BUCKET": "b", "CATALOG_BACKEND": "redis"})


def test_local_catalog_is_refused_on_lambda():
    with pytest.raises(ConfigError, match="Lambda"):
        Settings.from_env({
            "AWS_REGION": "us-east-1", "S3_BUCKET": "b", "CATALOG_BACKEND": "local",
            "AWS_LAMBDA_FUNCTION_NAME": "fn",
        })
