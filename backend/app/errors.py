"""Application error type and the single JSON error envelope used by the API."""

from __future__ import annotations

from typing import Any


class ApiError(Exception):
    """An error that maps 1:1 onto the documented error response."""

    def __init__(
        self,
        status_code: int,
        code: str,
        message: str,
        *,
        retryable: bool = False,
        details: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(f"{code}: {message}")
        self.status_code = status_code
        self.code = code
        self.message = message
        self.retryable = retryable
        self.details = details

    def body(self) -> dict[str, Any]:
        return error_body(self.code, self.message, self.retryable, self.details)


def error_body(
    code: str, message: str, retryable: bool = False, details: dict[str, Any] | None = None
) -> dict[str, Any]:
    return {
        "error": {
            "code": code,
            "message": message,
            "retryable": retryable,
            "details": details,
        }
    }


# --- 400 -------------------------------------------------------------------
def invalid_session_id(message: str = "sessionId must look like capture-YYYYMMDDTHHMMSS-xxxxxx") -> ApiError:
    return ApiError(400, "INVALID_SESSION_ID", message)


def invalid_path(message: str = "relativePath is not an allowed path") -> ApiError:
    return ApiError(400, "INVALID_PATH", message)


def invalid_request(message: str, details: dict[str, Any] | None = None) -> ApiError:
    return ApiError(400, "INVALID_REQUEST", message, details=details)


def file_too_large(message: str, details: dict[str, Any] | None = None) -> ApiError:
    return ApiError(413, "FILE_TOO_LARGE", message, details=details)


# --- 404 -------------------------------------------------------------------
def session_not_found() -> ApiError:
    return ApiError(404, "SESSION_NOT_FOUND", "Session does not exist")


def file_not_found() -> ApiError:
    return ApiError(404, "FILE_NOT_FOUND", "No upload has been registered for this relativePath")


def upload_not_found(message: str = "No active multipart upload for this file") -> ApiError:
    return ApiError(404, "UPLOAD_NOT_FOUND", message)


# --- 409 -------------------------------------------------------------------
def verification_failed(reason: str, message: str, **details: Any) -> ApiError:
    return ApiError(409, "VERIFICATION_FAILED", message, details={"reason": reason, **details})


def session_incomplete(missing: list[str], unverified: list[str]) -> ApiError:
    return ApiError(
        409,
        "SESSION_INCOMPLETE",
        "Not every file of the session is verified in the cloud",
        details={"missing": missing, "unverified": unverified},
    )


def session_conflict(message: str, details: dict[str, Any] | None = None) -> ApiError:
    return ApiError(409, "SESSION_CONFLICT", message, details=details)


def upload_conflict(message: str, details: dict[str, Any] | None = None) -> ApiError:
    return ApiError(409, "UPLOAD_CONFLICT", message, details=details)


# --- 5xx -------------------------------------------------------------------
def upstream_error(message: str = "Upstream AWS service error", status: int = 503) -> ApiError:
    return ApiError(status, "UPSTREAM_ERROR", message, retryable=True)
