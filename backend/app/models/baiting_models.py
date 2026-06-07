"""
Baiting Models — Data contracts for the AI auto-baiting pipeline.
"""

from __future__ import annotations

from typing import List, Optional
from pydantic import BaseModel, Field

class ChatMessage(BaseModel):
    """A single message in the baiting conversation history."""
    role: str = Field(..., description="'user' for scammer, 'assistant' for AI")
    content: str = Field(...)

class OfflineAnalyticsSyncDto(BaseModel):
    detected_intent: str
    selected_persona: str
    selected_state: str
    timestamp: int

class BaitingRequest(BaseModel):
    """Request to generate a reply to a scammer."""
    sender_id: str = Field(..., description="Unique ID for the scammer")
    session_id: str = Field(default="default_session", description="Unique session ID")
    persona: str = Field(default="busy_professional", description="Persona to adopt")
    current_strategy: str = Field(default="CONFUSION", description="Current baiting strategy active")
    scam_category: str = Field(default="unknown", description="Category of scam detected")
    goal: str = Field(default="waste_time", description="Goal: waste_time or extract_information")
    history: List[ChatMessage] = Field(..., description="Conversation history including latest message")
    offline_analytics: List[OfflineAnalyticsSyncDto] = Field(default_factory=list, description="Offline analytics events to sync")

class BaitingResponse(BaseModel):
    """Generated response to send to the scammer."""
    reply_text: str = Field(..., description="The AI's generated reply text")
    reply_parts: List[str] = Field(default_factory=list, description="Optional split reply parts to send sequentially")
    response_delay_seconds: int = Field(default=3, description="Recommended delay before first part (legacy; use part_delay_seconds when set)")
    part_delay_seconds: Optional[List[int]] = Field(
        default=None,
        description="Per-bubble pause in seconds before each part; same length as reply_parts when set",
    )
    should_terminate: bool = Field(default=False, description="True if AI thinks the scammer gave up")
    processing_time_ms: float = 0.0
    strategy_used: str = Field(default="CONFUSION", description="The strategy deployed")
    persona_used: str = Field(default="busy_professional")
    goal: str = Field(default="waste_time")
    stealth_typing_speed_ms: int = Field(default=55, description="Randomized ms per char for typing simulation")
    suspicion_detected: bool = Field(default=False, description="True if scammer is highly suspicious")
    session_strategy: str = Field(
        default="",
        description="Strategy chosen for this turn (persist client-side even if reply was a stall)",
    )
    tracking_url: str | None = Field(
        default=None,
        description="Tracking link URL included in this reply (if any)",
    )
    image_base64: str | None = Field(
        default=None,
        description="Base64-encoded JPEG of AI-generated fake screenshot (payment proof, error, etc.)",
    )
    image_context: str | None = Field(
        default=None,
        description="Context type: payment_proof, error_screen, otp_screen, receipt",
    )
