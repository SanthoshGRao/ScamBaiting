"""Detection API routes with JWT auth, retries, and DB persistence."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from tenacity import retry, stop_after_attempt, wait_exponential

from app.agents.detection.detection_agent import DetectionAgent
from app.agents.detection.input_normalizer import InputNormalizer
from app.agents.detection.pii_sanitizer import PIISanitizer
from app.config.detection_settings import DetectionSettings
from app.db.models import User
from app.db.session import get_db
from app.models.detection_models import (
    DetectionRequest,
    DetectionResponse,
    FeedbackRequest,
)
from app.providers.llm_classifier import create_llm_provider, LLMSettings
from app.services.detection_service import DetectionService
from app.security.auth import get_current_user
from app.security.rate_limit import rate_limiter
from app.services.persistence_service import PersistenceService

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/detect",
    tags=["detection"],
)

def build_detection_agent(db: Session) -> DetectionAgent:
    settings = DetectionSettings()
    normalizer = InputNormalizer(default_language=settings.default_language)
    sanitizer = PIISanitizer()
    persistence = PersistenceService(db)
    try:
        llm_settings = LLMSettings()
        groq_settings = LLMSettings(llm_model=llm_settings.detection_groq_model)
        openai_settings = LLMSettings(llm_model=llm_settings.detection_openai_fallback_model)
        primary = create_llm_provider(provider="groq", settings=groq_settings)
        backup = create_llm_provider(provider="openai", settings=openai_settings)
        llm_provider = DetectionService(
            primary_provider=primary,
            backup_provider=backup,
            fallback_threshold=0.55,
            fallback_min_chars=20,
        )
    except Exception as exc:
        logger.warning("LLM provider init failed: %s — running rule-only", exc)
        llm_provider = None
    return DetectionAgent(
        settings=settings,
        normalizer=normalizer,
        llm_classifier=llm_provider,
        pii_sanitizer=sanitizer,
        persistence=persistence,
    )


@retry(wait=wait_exponential(multiplier=0.5, min=0.5, max=4), stop=stop_after_attempt(3), reraise=True)
async def _detect(agent: DetectionAgent, request: DetectionRequest, full: bool) -> DetectionResponse:
    return await (agent.detect_full(request) if full else agent.detect(request))


@router.post(
    "",
    response_model=DetectionResponse,
    summary="Quick scam detection",
    description=(
        "Analyzes text for scam indicators. Returns immediate rule-based "
        "verdict. LLM analysis runs in background if enabled."
    ),
)
async def detect_scam(
    request: DetectionRequest,
    db: Session = Depends(get_db),
    _: User = Depends(get_current_user),
) -> DetectionResponse:
    rate_limiter.check(f"user:{_.id}:detect")
    agent = build_detection_agent(db)
    try:
        return await _detect(agent, request, full=False)
    except Exception as exc:
        logger.error("Detection failed: %s", exc)
        raise HTTPException(status_code=500, detail="Detection failed")


@router.post(
    "/full",
    response_model=DetectionResponse,
    summary="Full scam detection (waits for LLM)",
    description=(
        "Analyzes text with full pipeline including LLM classification. "
        "Waits for LLM result before responding. Higher latency (~2s)."
    ),
)
async def detect_scam_full(
    request: DetectionRequest,
    db: Session = Depends(get_db),
    _: User = Depends(get_current_user),
) -> DetectionResponse:
    rate_limiter.check(f"user:{_.id}:detect_full")
    agent = build_detection_agent(db)
    try:
        return await _detect(agent, request, full=True)
    except Exception as exc:
        logger.error("Full detection failed: %s", exc)
        raise HTTPException(status_code=500, detail="Detection failed")


@router.get(
    "/health",
    summary="Health check",
    description="Check if the detection service and LLM provider are operational.",
)
async def health_check() -> dict[str, Any]:
    return {
        "status": "healthy",
        "detection_service": True,
    }


@router.post("/feedback")
async def submit_feedback(
    payload: FeedbackRequest,
    db: Session = Depends(get_db),
    _: User = Depends(get_current_user),
) -> dict[str, str]:
    service = PersistenceService(db)
    service.save_feedback(payload.message_id, payload.label, payload.notes)
    return {"status": "ok"}
