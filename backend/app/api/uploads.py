"""Upload endpoints (all under /v1). Binary data never flows through these: only presigned URLs."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from app.auth import Principal, get_principal
from app.deps import get_session_service
from app.models.upload import (
    MultipartCompleteRequest,
    MultipartCompleteResponse,
    MultipartPartsRequest,
    MultipartPartsResponse,
    MultipartStartRequest,
    MultipartStartResponse,
    PresignRequest,
    PresignResponse,
    VerifyRequest,
    VerifyResponse,
)
from app.services.session_service import SessionService

router = APIRouter(
    prefix="/v1/sessions/{sessionId}/uploads",
    tags=["uploads"],
    dependencies=[Depends(get_principal)],
)


@router.post("/presign", response_model=PresignResponse)
def presign(
    sessionId: str,
    body: PresignRequest,
    principal: Principal = Depends(get_principal),
    service: SessionService = Depends(get_session_service),
) -> PresignResponse:
    return service.presign(sessionId, body, principal)


@router.post("/multipart/start", response_model=MultipartStartResponse)
def multipart_start(
    sessionId: str,
    body: MultipartStartRequest,
    principal: Principal = Depends(get_principal),
    service: SessionService = Depends(get_session_service),
) -> MultipartStartResponse:
    return service.multipart_start(sessionId, body, principal)


@router.post("/multipart/parts", response_model=MultipartPartsResponse)
def multipart_parts(
    sessionId: str,
    body: MultipartPartsRequest,
    principal: Principal = Depends(get_principal),
    service: SessionService = Depends(get_session_service),
) -> MultipartPartsResponse:
    return service.multipart_parts(sessionId, body, principal)


@router.post("/multipart/complete", response_model=MultipartCompleteResponse)
def multipart_complete(
    sessionId: str,
    body: MultipartCompleteRequest,
    principal: Principal = Depends(get_principal),
    service: SessionService = Depends(get_session_service),
) -> MultipartCompleteResponse:
    return service.multipart_complete(sessionId, body, principal)


@router.post("/verify", response_model=VerifyResponse)
def verify(
    sessionId: str,
    body: VerifyRequest,
    principal: Principal = Depends(get_principal),
    service: SessionService = Depends(get_session_service),
) -> VerifyResponse:
    return service.verify(sessionId, body, principal)
