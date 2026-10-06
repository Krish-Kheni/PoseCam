"""Pydantic models for the session endpoints. JSON field names are camelCase on purpose."""

from __future__ import annotations

from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

CloudStatus = Literal["CREATED", "UPLOADING", "SYNCED"]
FileState = Literal["PRESIGNED", "UPLOADING", "UPLOADED", "VERIFIED"]

# Strict: JSON numbers only (no "123" strings, no booleans, no floats).
SizeBytes = Annotated[int, Field(strict=True, ge=0)]


class ApiModel(BaseModel):
    # Unknown fields are ignored so that newer clients keep working.
    model_config = ConfigDict(extra="ignore")


class CreateSessionRequest(ApiModel):
    sessionId: str = Field(max_length=64)
    deviceId: str | None = Field(default=None, max_length=128)
    createdAt: str = Field(max_length=64)
    recordingStatus: str = Field(min_length=1, max_length=64)
    appVersion: str | None = Field(default=None, max_length=64)
    # Which pipe folder the recording is filed under ("white" or "black"). Required; the value is checked in the service.
    pipe: str | None = Field(default=None, max_length=16)

    @field_validator("createdAt")
    @classmethod
    def _iso8601(cls, value: str) -> str:
        try:
            datetime.fromisoformat(value)
        except ValueError:
            raise ValueError("createdAt must be an ISO-8601 timestamp") from None
        return value


class SessionView(ApiModel):
    sessionId: str
    deviceId: str
    createdAt: str
    recordingStatus: str
    cloudStatus: CloudStatus
    totalFiles: int
    verifiedFiles: int
    totalBytes: int
    verifiedBytes: int
    completedAt: str | None = None


class FileView(ApiModel):
    relativePath: str
    sizeBytes: int
    sha256: str
    state: FileState
    uploadedAt: str | None = None
    verifiedAt: str | None = None


class UploadConfig(ApiModel):
    multipartThresholdBytes: int
    partSizeBytes: int
    presignedUrlTtlSeconds: int


class CreateSessionResponse(ApiModel):
    session: SessionView
    created: bool
    config: UploadConfig


class GetSessionResponse(ApiModel):
    session: SessionView
    files: list[FileView]


class CompleteFileEntry(ApiModel):
    relativePath: str = Field(max_length=512)
    sizeBytes: SizeBytes


class CompleteSessionRequest(ApiModel):
    recordingStatus: str = Field(min_length=1, max_length=64)
    files: list[CompleteFileEntry] = Field(min_length=1, max_length=5000)


class CompleteSessionResponse(ApiModel):
    session: SessionView
