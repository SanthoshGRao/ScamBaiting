"""
Kill Switch — Emergency disable for the deception engine.

Checks environment variable and runtime state to determine if
artifact generation should be allowed. Includes per-session
and daily rate limits.
"""

from __future__ import annotations

import logging
import os
from collections import defaultdict
from datetime import datetime, timezone

from app.models.safety_models import KillSwitchStatus

logger = logging.getLogger(__name__)


class KillSwitch:
    """Emergency disable mechanism for the deception engine."""

    # Rate limits
    MAX_ARTIFACTS_PER_SESSION = 20
    MAX_ARTIFACTS_PER_DAY = 100

    def __init__(self) -> None:
        self._disabled = False
        self._disabled_reason = ""
        self._disabled_at: datetime | None = None
        self._disabled_by = ""
        self._session_counts: dict[str, int] = defaultdict(int)
        self._daily_count = 0
        self._daily_reset_date: str = ""

    def is_enabled(self) -> bool:
        """Check if the deception engine is allowed to generate artifacts."""
        # Check environment variable first
        env_flag = os.environ.get("DECEPTION_ENGINE_ENABLED", "true").lower()
        if env_flag in ("false", "0", "no", "off"):
            return False

        # Check runtime kill switch
        if self._disabled:
            return False

        return True

    def check_rate_limits(self, session_id: str) -> tuple[bool, str]:
        """
        Check if generation is within rate limits.

        Returns:
            (allowed, reason) tuple.
        """
        # Reset daily counter if new day
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        if today != self._daily_reset_date:
            self._daily_count = 0
            self._daily_reset_date = today

        # Check session limit
        if self._session_counts[session_id] >= self.MAX_ARTIFACTS_PER_SESSION:
            return False, f"Session limit reached ({self.MAX_ARTIFACTS_PER_SESSION} artifacts)"

        # Check daily limit
        if self._daily_count >= self.MAX_ARTIFACTS_PER_DAY:
            return False, f"Daily limit reached ({self.MAX_ARTIFACTS_PER_DAY} artifacts)"

        return True, ""

    def record_generation(self, session_id: str) -> None:
        """Record that an artifact was generated (for rate limiting)."""
        self._session_counts[session_id] += 1
        self._daily_count += 1

    def disable(self, reason: str = "Manual kill switch", by: str = "admin") -> None:
        """Disable the deception engine."""
        self._disabled = True
        self._disabled_reason = reason
        self._disabled_at = datetime.now(timezone.utc)
        self._disabled_by = by
        logger.critical(
            "KILL SWITCH ACTIVATED: reason='%s' by='%s'", reason, by,
        )

    def enable(self) -> None:
        """Re-enable the deception engine."""
        self._disabled = False
        self._disabled_reason = ""
        self._disabled_at = None
        self._disabled_by = ""
        logger.info("Kill switch deactivated — deception engine re-enabled")

    def get_status(self) -> KillSwitchStatus:
        """Get current kill switch status."""
        return KillSwitchStatus(
            enabled=self.is_enabled(),
            reason=self._disabled_reason,
            disabled_at=self._disabled_at,
            disabled_by=self._disabled_by,
        )
