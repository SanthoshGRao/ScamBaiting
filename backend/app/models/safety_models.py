"""
Safety Models — Data contracts for the Safety & Ethics layer.

Content policy validation, audit logging, kill switch status.
"""

from __future__ import annotations

from datetime import datetime, timezone

from pydantic import BaseModel, Field


class PolicyResult(BaseModel):
    """Result of content policy validation."""
    allowed: bool = True
    violations: list[str] = Field(default_factory=list)
    requires_review: bool = False


class AuditEntry(BaseModel):
    """Immutable log entry for generated artifacts."""
    timestamp: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc)
    )
    session_id: str = ""
    artifact_type: str = ""
    artifact_id: str = ""
    content_hash: str = ""
    user_id_hash: str = ""
    scammer_id_hash: str = ""
    policy_result: PolicyResult = Field(default_factory=PolicyResult)


class KillSwitchStatus(BaseModel):
    """Current state of the deception engine kill switch."""
    enabled: bool = True
    reason: str = ""
    disabled_at: datetime | None = None
    disabled_by: str = ""
