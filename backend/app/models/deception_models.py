"""
Deception Models — Data contracts for the Multimodal Deception Engine.

Defines artifact types, generation requests, realism scoring,
fallback handling, and safety metadata.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field


class ArtifactType(str, Enum):
    """Types of fake artifacts the engine can generate."""
    BANK_SCREENSHOT = "bank_screenshot"
    OTP_SCREEN = "otp_screen"
    RECEIPT = "receipt"
    GENERIC_ID = "generic_id"
    EMAIL = "email"
    FREEFORM_IMAGE = "freeform_image"
    CHAT_SCREENSHOT = "chat_screenshot"


class GenerationMethod(str, Enum):
    """How the artifact was generated."""
    TEMPLATE = "template"
    AI_GENERATED = "ai_generated"
    HYBRID = "hybrid"


# --- Request / Response ---

class DeceptionRequest(BaseModel):
    """API request to generate a fake artifact."""
    session_id: str = Field(..., description="Active baiting session ID")
    artifact_type: ArtifactType = Field(..., description="Type of fake to generate")
    context: str = Field(
        default="", description="Conversation context for the generation"
    )
    parameters: dict[str, Any] = Field(
        default_factory=dict,
        description="Type-specific parameters (bank, amount, recipient, etc.)"
    )


class SafetyFlags(BaseModel):
    """Safety metadata embedded in every generated artifact."""
    contains_pii: bool = False
    watermarked: bool = True
    forensically_traceable: bool = True


class DeceptionResponse(BaseModel):
    """API response with the generated artifact."""
    artifact_id: str = Field(
        default_factory=lambda: f"DEC-{uuid.uuid4().hex[:12]}"
    )
    content_type: str = "image/png"
    data_base64: str = Field(..., description="Base64-encoded artifact content")
    watermark_id: str = Field(default="")
    expiry_seconds: int = Field(default=3600)
    safety_flags: SafetyFlags = Field(default_factory=SafetyFlags)
    generation_method: GenerationMethod = GenerationMethod.TEMPLATE
    processing_time_ms: float = 0.0


# --- Realism Scoring ---

class RealismResult(BaseModel):
    """Result of artifact realism quality gate."""
    score: float = Field(default=0.0, ge=0.0, le=1.0)
    factors: dict[str, float] = Field(default_factory=dict)
    passed: bool = False
    recommendation: str = "regenerate"


# --- Fallback ---

class FallbackResponse(BaseModel):
    """Response when artifact generation fails."""
    action: str = Field(
        ..., description="text_excuse_with_retry | text_only_mode"
    )
    excuse_text: str = Field(..., description="Human-like excuse to send")
    retry_queued: bool = False
    retry_task_id: str | None = None
    followup_text: str | None = None
