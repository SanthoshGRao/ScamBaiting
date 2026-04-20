"""
Deception Routes — API endpoints for the Multimodal Deception Engine.

POST /api/v2/deception/generate  — Generate a fake artifact
GET  /api/v2/deception/status    — Kill switch & rate limit status
POST /api/v2/deception/kill      — Emergency disable
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException

from app.models.deception_models import DeceptionRequest, DeceptionResponse
from app.deception.engine import DeceptionEngine
from app.safety.content_policy import ContentPolicy
from app.safety.kill_switch import KillSwitch
from app.safety.audit_logger import AuditLogger
from app.safety.watermark import SteganographicWatermarker
from app.security.auth import get_current_user

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v2/deception", tags=["deception"], dependencies=[Depends(get_current_user)])

# Singletons
_engine = DeceptionEngine()
_policy = ContentPolicy()
_kill_switch = KillSwitch()
_audit = AuditLogger()
_watermarker = SteganographicWatermarker()


@router.post("/generate", response_model=DeceptionResponse)
async def generate_artifact(request: DeceptionRequest):
    """
    Generate a fake artifact for an active baiting session.

    Safety checks enforced:
    1. Kill switch must be enabled
    2. Rate limits must not be exceeded
    3. Content policy must allow the request
    4. Session must be valid (reactive-only enforcement)
    """
    # 1. Kill switch check
    if not _kill_switch.is_enabled():
        raise HTTPException(
            status_code=503,
            detail="Deception engine is disabled (kill switch active)",
        )

    # 2. Rate limit check
    allowed, reason = _kill_switch.check_rate_limits(request.session_id)
    if not allowed:
        raise HTTPException(status_code=429, detail=reason)

    # 3. Content policy check
    policy_result = _policy.validate(
        request.artifact_type.value, request.context
    )
    if not policy_result.allowed:
        _audit.log_generation(
            session_id=request.session_id,
            artifact_type=request.artifact_type.value,
            artifact_id="BLOCKED",
            policy_result=policy_result,
        )
        raise HTTPException(
            status_code=403,
            detail=f"Content policy violation: {policy_result.violations}",
        )

    # 4. Generate artifact
    try:
        response = await _engine.generate(request)

        # Record for rate limiting
        _kill_switch.record_generation(request.session_id)

        # Audit log
        _audit.log_generation(
            session_id=request.session_id,
            artifact_type=request.artifact_type.value,
            artifact_id=response.artifact_id,
            content_bytes=response.data_base64[:100].encode(),
            policy_result=policy_result,
        )

        logger.info(
            "Artifact generated: %s (%s) for session %s",
            response.artifact_id, request.artifact_type.value,
            request.session_id,
        )

        return response

    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/status")
async def get_deception_status():
    """Get current deception engine status."""
    return {
        "kill_switch": _kill_switch.get_status().model_dump(),
        "audit_entries": _audit.get_count(),
    }


@router.post("/kill")
async def activate_kill_switch(reason: str = "Manual activation"):
    """Emergency disable the deception engine."""
    _kill_switch.disable(reason=reason)
    return {"status": "disabled", "reason": reason}


@router.post("/enable")
async def deactivate_kill_switch():
    """Re-enable the deception engine."""
    _kill_switch.enable()
    return {"status": "enabled"}
