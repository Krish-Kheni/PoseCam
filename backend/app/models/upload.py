"""Pydantic models for the upload endpoints. JSON field names are camelCase on purpose."""

from __future__ import annotations

from typing import Literal

from pydantic import Field

from app.models.session import ApiModel, SizeBytes

MAX_PARTS_PER_PRESIGN_CALL = 100

PresignMode = Literal["SINGLE", "MULTIPART", "ALREADY_VERIFIED"]


class FileDescriptor(ApiModel):
    relativePath: str = Field(max_length=512)
    sizeBytes: SizeBytes
    sha256: str = Field(max_length=128)


class PresignRequest(FileDescriptor):
    pass


class PresignResponse(ApiModel):
    relativePath: str
    mode: PresignMode
    url: str | None
    method: Literal["PUT"] = "PUT"
    headers: dict[str, str]
    expiresInSeconds: int
    partSizeBytes: int | None


class MultipartStartRequest(FileDescriptor):
    pass


class UploadedPart(ApiModel):
    partNumber: int
    sizeBytes: int
    etag: str
    checksumSha256: str | None


class MultipartStartResponse(ApiModel):
    relativePath: str
    uploadId: str
    partSizeBytes: int
    partCount: int
    resumed: bool
    uploadedParts: list[UploadedPart]


class PartRequest(ApiModel):
    partNumber: int = Field(strict=True)
    sizeBytes: SizeBytes
    checksumSha256: str = Field(max_length=128)


class MultipartPartsRequest(ApiModel):
    relativePath: str = Field(max_length=512)
    uploadId: str = Field(min_length=1, max_length=2048)
    parts: list[PartRequest] = Field(min_length=1, max_length=MAX_PARTS_PER_PRESIGN_CALL)


class PartUrl(ApiModel):
    partNumber: int
    url: str
    headers: dict[str, str]
    expiresInSeconds: int


class MultipartPartsResponse(ApiModel):
    parts: list[PartUrl]


class CompletePart(ApiModel):
    partNumber: int = Field(strict=True)
    etag: str = Field(min_length=1, max_length=256)
    checksumSha256: str = Field(max_length=128)


class MultipartCompleteRequest(ApiModel):
    relativePath: str = Field(max_length=512)
    uploadId: str = Field(min_length=1, max_length=2048)
    parts: list[CompletePart] = Field(min_length=1, max_length=10000)


class MultipartCompleteResponse(ApiModel):
    relativePath: str
    state: Literal["UPLOADED"] = "UPLOADED"
    sizeBytes: int


class VerifyRequest(ApiModel):
    relativePath: str = Field(max_length=512)


class VerifyResponse(ApiModel):
    relativePath: str
    state: Literal["VERIFIED"] = "VERIFIED"
    sizeBytes: int
    verifiedAt: str
