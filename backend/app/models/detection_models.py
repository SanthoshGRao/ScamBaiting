"""
Detection Models — Pydantic v2 data contracts for DetectionAgent pipeline.

All input/output contracts between detection modules are defined here.
Ensures type safety, validation, and serialization consistency.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field, field_validator


# --- Enums ---

class InputSource(str, Enum):
    """Source of the incoming message."""
    SMS = "sms"
    EMAIL = "email"
    NOTIFICATION = "notification"
    MANUAL = "manual"


class RiskLevel(str, Enum):
    """Risk classification tiers."""
    SAFE = "safe"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class RecommendedAction(str, Enum):
    """Recommended system action based on risk."""
    NONE = "none"
    LOG_ONLY = "log_only"
    WARN_USER = "warn_user"
    BLOCK = "block"
    BAIT = "bait"


class DetectionMode(str, Enum):
    """Which detection pipeline produced the verdict."""
    RULE_ONLY = "rule_only"
    LLM_ONLY = "llm_only"
    HYBRID = "hybrid"


# --- Input Models ---

class RawMessageInput(BaseModel):
    """Raw message received from Android client or API caller."""
    text: str = Field(..., min_length=1, max_length=5000)
    source: InputSource = InputSource.MANUAL
    sender: str | None = None
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    metadata: dict[str, Any] = Field(default_factory=dict)

    @field_validator("text")
    @classmethod
    def text_not_empty(cls, v: str) -> str:
        stripped = v.strip()
        if not stripped:
            raise ValueError("Message text cannot be empty or whitespace-only")
        return v


class NormalizedInput(BaseModel):
    """Output of InputNormalizer — cleaned and enriched text."""
    message_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    text: str  # cleaned text
    original_text: str  # preserved for display
    source: InputSource
    sender: str | None = None
    language: str = "en"  # detected: "en", "hi", "hinglish"
    urls: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)
    normalization_applied: list[str] = Field(default_factory=list)


# --- Rule Engine Models ---

class MatchedRule(BaseModel):
    """A single rule that matched during rule-based detection."""
    rule: str  # e.g., "keyword:urgent", "pattern:shortened_url"
    weight: float = Field(ge=0.0, le=1.0)
    category: str | None = None


class RuleVerdict(BaseModel):
    """Output of the on-device RuleEngine."""
    is_suspicious: bool = False
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    matched_rules: list[MatchedRule] = Field(default_factory=list)
    category_hints: list[str] = Field(default_factory=list)


# --- LLM Models ---

class LLMVerdict(BaseModel):
    """Output of the LLM classifier."""
    is_scam: bool = False
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    category: str = "unknown"
    reasoning: str = ""
    explanation: str = ""  # user-facing
    features: dict[str, float] = Field(default_factory=dict)
    red_flags: list[str] = Field(default_factory=list)


class ExplainabilityBreakdown(BaseModel):
    rule_score: float = Field(default=0.0, ge=0.0, le=1.0)
    llm_score: float = Field(default=0.0, ge=0.0, le=1.0)
    sender_reputation: float = Field(default=0.5, ge=0.0, le=1.0)
    final_score: float = Field(default=0.0, ge=0.0, le=1.0)
    reasons: list[str] = Field(default_factory=list)


# --- Final Verdict ---

class DetectionVerdict(BaseModel):
    """Final merged detection result — returned to client."""
    message_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    is_scam: bool = False
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    category: str = "unknown"
    risk_level: RiskLevel = RiskLevel.SAFE
    reasoning: str = ""
    explanation: str = ""
    matched_rules: list[MatchedRule] = Field(default_factory=list)
    features: dict[str, float] = Field(default_factory=dict)
    red_flags: list[str] = Field(default_factory=list)
    sender_score: float = Field(default=0.5, ge=0.0, le=1.0)
    link_risk_score: float = Field(default=0.0, ge=0.0, le=1.0)
    domain_flags: list[str] = Field(default_factory=list)
    explainability: ExplainabilityBreakdown = Field(default_factory=ExplainabilityBreakdown)
    recommended_action: RecommendedAction = RecommendedAction.NONE
    language: str = "en"
    source: InputSource = InputSource.MANUAL
    detection_mode: DetectionMode = DetectionMode.RULE_ONLY

    @property
    def is_actionable(self) -> bool:
        """Whether this verdict should trigger a user-facing action."""
        return self.risk_level in (RiskLevel.MEDIUM, RiskLevel.HIGH)


# --- API Request/Response ---

class DetectionRequest(BaseModel):
    """API request body for /api/v1/detect endpoint."""
    text: str = Field(..., min_length=1, max_length=5000)
    source: InputSource = InputSource.MANUAL
    sender: str | None = None
    language_hint: str | None = None
    rule_verdict: RuleVerdict | None = None
    privacy_mode: bool = False


class DetectionResponse(BaseModel):
    """API response from /api/v1/detect endpoint."""
    verdict: DetectionVerdict
    processing_time_ms: float = 0.0
    llm_pending: bool = False  # True if LLM result will arrive async


class FeedbackRequest(BaseModel):
    message_id: str
    label: str = Field(..., pattern="^(safe|scam)$")
    notes: str = ""
