"""FastAPI application + AWS Lambda entry point (``handler``)."""

from __future__ import annotations

import logging
import os
import time
import uuid
from typing import Any

from botocore.exceptions import BotoCoreError, ClientError
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from mangum import Mangum
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.api import sessions, uploads
from app.config import ConfigError
from app.errors import ApiError, error_body
from app.logging_utils import audit, configure_logging, principal_var, request_id_var

configure_logging()
logger = logging.getLogger("posecam.app")

REQUEST_ID_HEADER = "x-request-id"
_THROTTLE_CODES = {
    "ProvisionedThroughputExceededException",
    "ThrottlingException",
    "RequestLimitExceeded",
    "SlowDown",
    "ServiceUnavailable",
    "InternalServerError",
}


class RequestContextMiddleware:
    """Pure-ASGI middleware: request id, access/audit log, last-resort 500 handler."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        aws_ctx = scope.get("aws.context")
        request_id = getattr(aws_ctx, "aws_request_id", None) or uuid.uuid4().hex
        scope.setdefault("state", {})["request_id"] = request_id
        token = request_id_var.set(request_id)
        principal_token = principal_var.set(None)
        started = time.perf_counter()
        status = {"code": 500}
        response_started = {"v": False}

        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start":
                response_started["v"] = True
                status["code"] = message["status"]
                headers = list(message.get("headers", []))
                headers.append((REQUEST_ID_HEADER.encode(), request_id.encode()))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception:  # noqa: BLE001 - last resort; never leak internals
            logger.exception("unhandled error")
            if not response_started["v"]:
                response = JSONResponse(
                    error_body("INTERNAL_ERROR", "Internal server error", True), status_code=500
                )
                await response(scope, receive, send_wrapper)
        finally:
            principal = getattr(scope.get("state", {}).get("principal"), "__dict__", None)
            event = scope.get("aws.event") or {}
            source_ip = (
                (event.get("requestContext") or {}).get("http", {}).get("sourceIp")
                or (scope.get("client") or [None])[0]
            )
            route = scope.get("route")
            audit(
                "http_request",
                method=scope.get("method"),
                path=getattr(route, "path", None) or scope.get("path"),
                status=status["code"],
                durationMs=round((time.perf_counter() - started) * 1000, 1),
                sourceIp=source_ip,
                deviceId=(principal or {}).get("device_id"),
                authenticated=(principal or {}).get("authenticated"),
            )
            request_id_var.reset(token)
            principal_var.reset(principal_token)


def _api_error_response(exc: ApiError) -> JSONResponse:
    return JSONResponse(exc.body(), status_code=exc.status_code)


async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    if exc.status_code >= 500 and exc.status_code != 501:
        logger.error("api error %s: %s", exc.code, exc.message)
    return _api_error_response(exc)


async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    problems = [
        {
            "field": ".".join(str(p) for p in err.get("loc", ()) if p != "body"),
            "message": err.get("msg", "invalid"),
            "type": err.get("type", "invalid"),
        }
        for err in exc.errors()
    ]
    body = error_body("INVALID_REQUEST", "Request validation failed", False, {"errors": problems})
    return JSONResponse(body, status_code=400)


async def http_exception_handler(_: Request, exc: StarletteHTTPException) -> JSONResponse:
    code = {404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}.get(exc.status_code, "HTTP_ERROR")
    body = error_body(code, str(exc.detail), exc.status_code >= 500)
    return JSONResponse(body, status_code=exc.status_code, headers=getattr(exc, "headers", None))


async def aws_error_handler(_: Request, exc: Exception) -> JSONResponse:
    """boto3 failures -> retryable 5xx. Details stay in the logs, not in the response."""
    code = ""
    if isinstance(exc, ClientError):
        code = str(exc.response.get("Error", {}).get("Code", ""))
    logger.error("aws error code=%s type=%s", code, type(exc).__name__)
    status = 503 if (code in _THROTTLE_CODES or isinstance(exc, BotoCoreError)) else 502
    return JSONResponse(error_body("UPSTREAM_ERROR", "Upstream AWS service error", True), status_code=status)


async def config_error_handler(_: Request, exc: ConfigError) -> JSONResponse:
    logger.error("configuration error: %s", exc)
    return JSONResponse(
        error_body("INTERNAL_ERROR", "Server is misconfigured", True), status_code=500
    )


def create_app() -> FastAPI:
    docs_enabled = os.environ.get("ENABLE_API_DOCS", "").lower() in {"1", "true", "yes"}
    app = FastAPI(
        title="PoseCam Cloud Upload API",
        version="1.0.0",
        docs_url="/docs" if docs_enabled else None,
        redoc_url=None,
        openapi_url="/openapi.json" if docs_enabled else None,
    )
    app.add_middleware(RequestContextMiddleware)
    app.add_exception_handler(ApiError, api_error_handler)  # type: ignore[arg-type]
    app.add_exception_handler(RequestValidationError, validation_error_handler)  # type: ignore[arg-type]
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)  # type: ignore[arg-type]
    app.add_exception_handler(ClientError, aws_error_handler)
    app.add_exception_handler(BotoCoreError, aws_error_handler)
    app.add_exception_handler(ConfigError, config_error_handler)  # type: ignore[arg-type]
    app.include_router(sessions.router)
    app.include_router(uploads.router)

    @app.get("/v1/health", tags=["meta"])
    def health() -> dict[str, Any]:
        """Liveness probe. Unauthenticated, touches no AWS service."""
        return {"status": "ok"}

    return app


app = create_app()

# AWS Lambda entry point (template.yaml: Handler app.main.handler).
handler = Mangum(app, lifespan="off")
