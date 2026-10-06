"""Session catalog endpoints (all under /v1)."""

from __future__ import annotations

from fastapi import APIRouter, Depends

from app.auth import Principal, get_principal
from app.deps import get_session_service
from app.models.session import (
    CompleteSessionRequest,
    CompleteSessionResponse,
    CreateSessionRequest,
    CreateSessionResponse,
    GetSessionResponse,
)
from app.services.session_service import SessionService

# Authentication is applied at router level so no endpoint can forget it.
router = APIRouter(prefix="/v1", tags=["sessions"], dependencies=[Depends(get_principal)])


@router.post("/sessions", response_model=CreateSessionResponse)
def create_session(
    body: CreateSessionRequest,
    principal: Principal = Depends(get_principal),
    service: SessionService = Depends(get_session_service),
) -> CreateSessionResponse:
    return service.create_session(body, principal)


@router.get("/sessions/{sessionId}", response_model=GetSessionResponse)
def get_session(
    sessionId: str,
    service: SessionService = Depends(get_session_service),
) -> GetSessionResponse:
    return service.get_session(sessionId)


@router.post("/sessions/{sessionId}/complete", response_model=CompleteSessionResponse)
def complete_session(
    sessionId: str,
    body: CompleteSessionRequest,
    principal: Principal = Depends(get_principal),
    service: SessionService = Depends(get_session_service),
) -> CompleteSessionResponse:
    return service.complete_session(sessionId, body, principal)
