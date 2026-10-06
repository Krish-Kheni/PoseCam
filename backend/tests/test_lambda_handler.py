"""End-to-end through the Mangum Lambda handler with an API Gateway HTTP API (v2) event."""

import json
from tests.helpers import new_session_id

import boto3
import pytest
from moto import mock_aws

from app.config import get_settings
from app.deps import _build_services


def event(method, path, body=None, headers=None):
    return {
        "version": "2.0",
        "routeKey": "$default",
        "rawPath": path,
        "rawQueryString": "",
        "headers": {"content-type": "application/json", **(headers or {})},
        "requestContext": {
            "http": {"method": method, "path": path, "protocol": "HTTP/1.1", "sourceIp": "203.0.113.9", "userAgent": "okhttp"},
            "requestId": "apigw-req-1", "stage": "$default",
        },
        "body": json.dumps(body) if body is not None else None,
        "isBase64Encoded": False,
    }


@pytest.fixture
def lambda_env(monkeypatch):
    for k, v in {"AWS_REGION": "us-east-1", "S3_BUCKET": "lambda-bucket", "DYNAMODB_TABLE": "lambda-table",
                 "AUTH_MODE": "none"}.items():
        monkeypatch.setenv(k, v)
    get_settings.cache_clear()
    _build_services.cache_clear()
    with mock_aws():
        boto3.client("dynamodb", region_name="us-east-1").create_table(
            TableName="lambda-table",
            AttributeDefinitions=[{"AttributeName": "PK", "AttributeType": "S"}, {"AttributeName": "SK", "AttributeType": "S"}],
            KeySchema=[{"AttributeName": "PK", "KeyType": "HASH"}, {"AttributeName": "SK", "KeyType": "RANGE"}],
            BillingMode="PAY_PER_REQUEST",
        )
        yield
    get_settings.cache_clear()
    _build_services.cache_clear()


def test_handler_create_and_presign(lambda_env):
    from app.main import handler

    sid = new_session_id()
    resp = handler(event("POST", "/v1/sessions", {
        "sessionId": sid, "deviceId": "d", "createdAt": "2026-10-01T10:00:00Z",
        "recordingStatus": "RECORDING", "appVersion": None, "pipe": "white"}), None)
    assert resp["statusCode"] == 200, resp
    assert json.loads(resp["body"])["created"] is True
    assert resp["headers"]["x-request-id"]

    resp = handler(event("POST", f"/v1/sessions/{sid}/uploads/presign",
                         {"relativePath": "manifest.json", "sizeBytes": 3, "sha256": "a" * 64}), None)
    assert resp["statusCode"] == 200, resp
    body = json.loads(resp["body"])
    assert body["mode"] == "SINGLE" and body["url"].startswith("https://lambda-bucket.s3.amazonaws.com/sessions/")

    resp = handler(event("GET", "/v1/sessions/nope"), None)
    assert resp["statusCode"] == 400
    assert json.loads(resp["body"])["error"]["code"] == "INVALID_SESSION_ID"
