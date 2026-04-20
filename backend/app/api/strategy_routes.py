"""
Strategy Routes — API endpoints for adaptive strategy selection.

POST /api/v2/strategy/select     — Select optimal strategy for a session
GET  /api/v2/strategy/rules      — Get behavioral rules for a strategy
POST /api/v2/strategy/suspicion  — Analyze suspicion level
"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app.models.analytics_models import StrategySelection, SuspicionResult
from app.agents.strategy.strategy_agent import StrategyAgent, STRATEGY_RULES
from app.agents.strategy.suspicion_detector import SuspicionDetector
from app.agents.strategy.stealth_optimizer import StealthOptimizer
from app.security.auth import get_current_user

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v2/strategy", tags=["strategy"], dependencies=[Depends(get_current_user)])

# Singletons
_strategy_agent = StrategyAgent()
_suspicion_detector = SuspicionDetector()
_stealth = StealthOptimizer()


class StrategyRequest(BaseModel):
    session_id: str
    history: list[dict] = Field(default_factory=list)
    scam_category: str = "unknown"
    current_strategy: str | None = None


class StealthRequest(BaseModel):
    text: str
    aggression_level: float = 0.3


@router.post("/select", response_model=StrategySelection)
async def select_strategy(request: StrategyRequest):
    """Select the optimal baiting strategy for the current conversation."""
    try:
        selection = await _strategy_agent.select_strategy(
            session_id=request.session_id,
            history=request.history,
            scam_category=request.scam_category,
            current_strategy=request.current_strategy,
        )
        logger.info(
            "Strategy selected: %s (confidence=%.2f) for session %s",
            selection.strategy, selection.switch_confidence,
            request.session_id,
        )
        return selection
    except Exception as e:
        logger.error("Strategy selection failed: %s", e)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/rules/{strategy_name}")
async def get_strategy_rules(strategy_name: str):
    """Get behavioral rules for a specific strategy."""
    name = strategy_name.upper()
    if name not in STRATEGY_RULES:
        raise HTTPException(
            status_code=404,
            detail=f"Unknown strategy: {name}. Available: {list(STRATEGY_RULES.keys())}",
        )
    return {
        "strategy": name,
        "rules": STRATEGY_RULES[name],
    }


@router.get("/rules")
async def list_all_strategies():
    """List all available strategies and their rules."""
    return {
        "strategies": {k: v for k, v in STRATEGY_RULES.items()},
        "count": len(STRATEGY_RULES),
    }


@router.post("/suspicion", response_model=SuspicionResult)
async def analyze_suspicion(request: StrategyRequest):
    """Analyze scammer messages for suspicion signals."""
    result = _suspicion_detector.compute_suspicion_score(request.history)
    return result


@router.post("/stealth")
async def apply_stealth(request: StealthRequest):
    """Apply stealth optimizations to AI-generated text."""
    humanized = _stealth.humanize(request.text, request.aggression_level)
    typing_speed = _stealth.randomize_typing_speed()
    return {
        "original": request.text,
        "humanized": humanized,
        "typing_speed_ms_per_char": typing_speed,
    }
