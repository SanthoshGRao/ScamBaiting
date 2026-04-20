"""
Analytics Models — Data contracts for Intelligence & Analytics modules.

Scammer profiling, conversation analytics, severity scoring,
and session success evaluation.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, Field


# --- Scammer Profile ---

class ScammerProfile(BaseModel):
    """Persistent profile built over multiple interactions with a scammer."""
    scammer_id: str = Field(..., description="Hashed phone/username")
    first_seen: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc)
    )
    last_seen: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc)
    )
    total_sessions: int = 0
    total_messages_received: int = 0
    total_time_wasted_seconds: int = 0

    # Behavioral fingerprint
    avg_message_length: float = 0.0
    avg_response_time_seconds: float = 0.0
    vocabulary_richness: float = 0.0
    urgency_tendency: float = 0.0
    aggression_tendency: float = 0.0

    # Scam characteristics
    primary_scam_type: str = "unknown"
    scam_types_seen: list[str] = Field(default_factory=list)
    sophistication_score: float = 0.5

    # Strategy effectiveness
    most_effective_strategy: str = ""
    strategy_effectiveness: dict[str, float] = Field(default_factory=dict)


# --- Conversation Analytics ---

class ConversationAnalytics(BaseModel):
    """Per-session analytics computed on every message."""
    session_id: str

    # Evolution tracking
    sentiment_trajectory: list[float] = Field(default_factory=list)
    patience_trajectory: list[float] = Field(default_factory=list)
    topic_shifts: list[str] = Field(default_factory=list)

    # Engagement metrics
    total_messages: int = 0
    ai_messages: int = 0
    scammer_messages: int = 0
    total_duration_seconds: int = 0
    avg_scammer_response_time: float = 0.0
    last_message_role: str = ""
    silence_duration_seconds: int = 0

    # Effectiveness
    time_wasted_minutes: float = 0.0
    estimated_cost_to_scammer: float = 0.0
    deception_success_rate: float = 0.0

    # Severity
    severity_score: float = 0.0
    severity_factors: dict[str, float] = Field(default_factory=dict)

    # Strategy tracking
    strategies_used: list[str] = Field(default_factory=list)

    # Suspicion
    suspicion_score: float = 0.0


# --- Severity Scoring ---

class SeverityResult(BaseModel):
    """Result of scam severity analysis."""
    score: float = Field(default=0.0, ge=0.0, le=1.0)
    level: str = "MINIMAL"
    factors: dict[str, float] = Field(default_factory=dict)
    recommended_response: str = "LOG_ONLY"


# --- Session Success ---

class SessionSuccess(BaseModel):
    """Evaluation of whether a baiting session was successful."""
    successful: bool = False
    conditions_met: dict[str, bool] = Field(default_factory=dict)
    quality_metrics: dict[str, bool] = Field(default_factory=dict)
    grade: str = "F"


# --- Global KPIs ---

class GlobalKPIs(BaseModel):
    """System-wide key performance indicators."""
    total_scammer_hours_wasted: float = 0.0
    session_success_rate: float = 0.0
    avg_time_per_session_minutes: float = 0.0
    total_cost_inflicted: float = 0.0
    scammers_frustrated_count: int = 0
    stealth_success_rate: float = 0.0
    total_sessions: int = 0
    active_sessions: int = 0


# --- Strategy Selection ---

class StrategySelection(BaseModel):
    """Result of the adaptive strategy selection."""
    strategy: str = "CONFUSION"
    reasoning: str = ""
    switch_confidence: float = 0.0
    suggested_persona: str | None = None


class SuspicionResult(BaseModel):
    """Result of scammer suspicion analysis."""
    score: float = Field(default=0.0, ge=0.0, le=1.0)
    level: str = "LOW"
    triggers: list[str] = Field(default_factory=list)
    recommended_action: str = "CONTINUE"
