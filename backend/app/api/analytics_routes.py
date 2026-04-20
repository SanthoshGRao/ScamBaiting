"""
Analytics Routes — API endpoints for intelligence & analytics.

GET  /api/v2/analytics/session/{id}       — Session analytics
GET  /api/v2/analytics/session/{id}/success — Session success evaluation
GET  /api/v2/analytics/scammer/{id}        — Scammer profile
GET  /api/v2/analytics/summary             — Global analytics summary
GET  /api/v2/analytics/kpis                — Global KPIs
POST /api/v2/analytics/severity            — Severity scoring
POST /api/v2/analytics/update              — Update session analytics
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.models.analytics_models import (
    ConversationAnalytics,
    GlobalKPIs,
    ScammerProfile,
    SessionSuccess,
    SeverityResult,
)
from app.agents.analytics.severity_scorer import SeverityScorer
from app.agents.analytics.success_evaluator import SuccessEvaluator
from app.agents.analytics.scammer_profiler import ScammerProfiler
from app.agents.analytics.conversation_tracker import ConversationTracker
from app.db.models import User
from app.db.session import get_db
from app.security.auth import get_current_user
from app.services.persistence_service import PersistenceService

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v2/analytics", tags=["analytics"], dependencies=[Depends(get_current_user)])

# Singletons
_severity_scorer = SeverityScorer()
_success_evaluator = SuccessEvaluator()
_profiler = ScammerProfiler()
_tracker = ConversationTracker()


class SeverityRequest(BaseModel):
    category: str = "unknown"
    features: dict[str, float] = Field(default_factory=dict)
    matched_rules: list[str] = Field(default_factory=list)


class UpdateAnalyticsRequest(BaseModel):
    session_id: str
    messages: list[dict] = Field(default_factory=list)
    strategies_used: list[str] = Field(default_factory=list)


class UpdateProfileRequest(BaseModel):
    scammer_id: str
    messages: list[dict] = Field(default_factory=list)
    scam_category: str = "unknown"
    strategies_used: list[str] = Field(default_factory=list)
    time_wasted_seconds: int = 0


@router.get("/session/{session_id}", response_model=ConversationAnalytics)
async def get_session_analytics(session_id: str):
    """Get analytics for a specific baiting session."""
    analytics = _tracker.get_analytics(session_id)
    if not analytics:
        raise HTTPException(404, f"No analytics found for session {session_id}")
    return analytics


@router.get("/session/{session_id}/success", response_model=SessionSuccess)
async def get_session_success(session_id: str):
    """Evaluate if a baiting session was successful."""
    analytics = _tracker.get_analytics(session_id)
    if not analytics:
        raise HTTPException(404, f"No analytics found for session {session_id}")
    return _success_evaluator.evaluate(analytics)


@router.get("/scammer/{scammer_id}", response_model=ScammerProfile)
async def get_scammer_profile(scammer_id: str):
    """Get behavioral profile for a scammer."""
    profile = _profiler.get_profile(scammer_id)
    if not profile:
        raise HTTPException(404, f"No profile found for scammer")
    return profile


@router.get("/summary")
async def get_analytics_summary():
    """Get global analytics summary."""
    sessions = _tracker.get_all_sessions()
    profiles = _profiler.get_all_profiles()

    total_time_wasted = sum(s.time_wasted_minutes for s in sessions)
    total_cost = sum(s.estimated_cost_to_scammer for s in sessions)

    return {
        "total_sessions": len(sessions),
        "total_scammers_tracked": len(profiles),
        "total_time_wasted_minutes": round(total_time_wasted, 1),
        "total_cost_inflicted": round(total_cost, 2),
        "total_messages": sum(s.total_messages for s in sessions),
    }


@router.get("/kpis", response_model=GlobalKPIs)
async def get_kpis():
    """Get global key performance indicators."""
    sessions = _tracker.get_all_sessions()

    if not sessions:
        return GlobalKPIs()

    total_time_min = sum(s.time_wasted_minutes for s in sessions)
    total_cost = sum(s.estimated_cost_to_scammer for s in sessions)

    # Evaluate success for each session
    successful_count = sum(
        1 for s in sessions
        if _success_evaluator.evaluate(s).successful
    )

    # Frustrated count
    frustrated_count = sum(
        1 for s in sessions
        if s.patience_trajectory and min(s.patience_trajectory[-3:]) < 2.0
    )

    # Stealth success
    stealth_count = sum(
        1 for s in sessions if s.suspicion_score < 0.3
    )

    active_count = sum(
        1 for s in sessions if s.silence_duration_seconds < 300
    )

    return GlobalKPIs(
        total_scammer_hours_wasted=round(total_time_min / 60.0, 1),
        session_success_rate=round(
            (successful_count / len(sessions)) * 100, 1
        ) if sessions else 0,
        avg_time_per_session_minutes=round(
            total_time_min / len(sessions), 1
        ) if sessions else 0,
        total_cost_inflicted=round(total_cost, 2),
        scammers_frustrated_count=frustrated_count,
        stealth_success_rate=round(
            (stealth_count / len(sessions)) * 100, 1
        ) if sessions else 0,
        total_sessions=len(sessions),
        active_sessions=active_count,
    )


@router.post("/severity", response_model=SeverityResult)
async def compute_severity(request: SeverityRequest):
    """Compute scam severity score."""
    return _severity_scorer.score(
        category=request.category,
        features=request.features,
        matched_rules=request.matched_rules,
    )


@router.post("/update", response_model=ConversationAnalytics)
async def update_analytics(request: UpdateAnalyticsRequest):
    """Update analytics for a session with new messages."""
    analytics = _tracker.update(
        session_id=request.session_id,
        messages=request.messages,
        strategies_used=request.strategies_used,
    )
    return analytics


@router.post("/profile/update", response_model=ScammerProfile)
async def update_scammer_profile(request: UpdateProfileRequest):
    """Update scammer profile with session data."""
    profile = _profiler.update_from_session(
        scammer_id=request.scammer_id,
        messages=request.messages,
        scam_category=request.scam_category,
        strategies_used=request.strategies_used,
        time_wasted_seconds=request.time_wasted_seconds,
    )
    return profile


@router.get("/db/summary")
async def get_database_summary(
    db: Session = Depends(get_db),
    _: User = Depends(get_current_user),
):
    service = PersistenceService(db)
    return service.analytics_summary()
