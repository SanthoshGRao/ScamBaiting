from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.db.models import ScammerMessage, SenderProfile, User
from app.db.session import get_db
from app.models.scammer_models import (
    ActivateAIRequest,
    ScammerHistoryItem,
    ScammerHistoryResponse,
    ScammerRegisterRequest,
)
from app.security.auth import get_current_user

router = APIRouter(prefix="/api/v1/scammer", tags=["scammer"], dependencies=[Depends(get_current_user)])
public_router = APIRouter(prefix="/scammer", tags=["scammer"], dependencies=[Depends(get_current_user)])


@router.post("/register")
async def register_scammer(
    payload: ScammerRegisterRequest,
    db: Session = Depends(get_db),
    _: User = Depends(get_current_user),
) -> dict[str, str]:
    profile = db.query(SenderProfile).filter(SenderProfile.sender_id == payload.phone_number).first()
    if not profile:
        profile = SenderProfile(
            sender_id=payload.phone_number,
            risk_level=payload.risk_level,
            is_active=payload.is_active,
            last_message=payload.last_message,
            risk_score=0.95 if payload.risk_level.upper() == "HIGH" else 0.5,
        )
        db.add(profile)
    else:
        profile.last_message = payload.last_message
        profile.risk_level = payload.risk_level
        profile.is_active = payload.is_active
    if payload.last_message:
        db.add(ScammerMessage(sender_id=payload.phone_number, role="user", content=payload.last_message))
    db.commit()
    return {"status": "registered"}


@router.post("/{scammer_id}/activate_ai")
async def activate_ai(
    scammer_id: str,
    payload: ActivateAIRequest,
    db: Session = Depends(get_db),
    _: User = Depends(get_current_user),
) -> dict[str, str]:
    profile = db.query(SenderProfile).filter(SenderProfile.sender_id == scammer_id).first()
    if not profile:
        raise HTTPException(status_code=404, detail="Scammer not found")
    profile.ai_enabled = payload.enabled
    db.commit()
    return {"status": "updated"}


@router.get("/{scammer_id}/history", response_model=ScammerHistoryResponse)
async def get_history(
    scammer_id: str,
    db: Session = Depends(get_db),
    _: User = Depends(get_current_user),
) -> ScammerHistoryResponse:
    profile = db.query(SenderProfile).filter(SenderProfile.sender_id == scammer_id).first()
    if not profile:
        raise HTTPException(status_code=404, detail="Scammer not found")
    rows = (
        db.query(ScammerMessage)
        .filter(ScammerMessage.sender_id == scammer_id)
        .order_by(ScammerMessage.created_at.asc())
        .all()
    )
    return ScammerHistoryResponse(
        phone_number=scammer_id,
        ai_enabled=profile.ai_enabled,
        history=[
            ScammerHistoryItem(role=row.role, content=row.content, timestamp=row.created_at)
            for row in rows
        ],
    )


@router.post("/_compat/register", include_in_schema=False)
async def register_scammer_compat(
    payload: ScammerRegisterRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> dict[str, str]:
    return await register_scammer(payload, db, user)


@public_router.post("/register")
async def register_scammer_public(
    payload: ScammerRegisterRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> dict[str, str]:
    return await register_scammer(payload, db, user)


@router.post("/_compat/{scammer_id}/activate_ai", include_in_schema=False)
async def activate_ai_compat(
    scammer_id: str,
    payload: ActivateAIRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> dict[str, str]:
    return await activate_ai(scammer_id, payload, db, user)


@public_router.post("/{scammer_id}/activate_ai")
async def activate_ai_public(
    scammer_id: str,
    payload: ActivateAIRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> dict[str, str]:
    return await activate_ai(scammer_id, payload, db, user)


@router.get("/_compat/{scammer_id}/history", include_in_schema=False)
async def get_history_compat(
    scammer_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> ScammerHistoryResponse:
    return await get_history(scammer_id, db, user)


@public_router.get("/{scammer_id}/history", response_model=ScammerHistoryResponse)
async def get_history_public(
    scammer_id: str,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
) -> ScammerHistoryResponse:
    return await get_history(scammer_id, db, user)
