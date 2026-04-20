"""
Deception Fallback — Graceful degradation when artifact generation fails.

3-stage fallback chain:
1. Text excuse + background retry
2. Retry with adjusted parameters
3. Downgrade to text-only baiting
"""

from __future__ import annotations

import logging
import random
from collections import defaultdict

from app.models.deception_models import FallbackResponse

logger = logging.getLogger(__name__)


# Human-like excuses for when image generation fails
TEXT_EXCUSES = [
    "image not sending idk why",
    "hold on my network is being so slow rn",
    "whatsapp keeps crashing when i try to send pic",
    "one sec let me try from my other phone",
    "ugh wifi just went out lemme switch to data",
    "ok sent but it says pending... do u see it?",
    "my storage is full hold on deleting some stuff",
    "sry camera app crashed let me retake",
]

FOLLOWUP_ON_SUCCESS = [
    "ok finally sent it lol",
    "there u go it went through now",
    "ok check now i resent it",
    "sent!! my phone was being so weird",
]


class DeceptionFallback:
    """Graceful degradation when artifact generation fails."""

    def __init__(self) -> None:
        self._failure_counts: dict[str, int] = defaultdict(int)
        self._session_modes: dict[str, str] = {}

    async def handle_failure(
        self, session_id: str, artifact_type: str, error: Exception
    ) -> FallbackResponse:
        """Handle generation failure with appropriate fallback."""
        self._failure_counts[session_id] += 1
        count = self._failure_counts[session_id]

        logger.warning(
            "Deception failure #%d for session %s (%s): %s",
            count, session_id, artifact_type, error,
        )

        if count >= 3:
            # Persistent failure — downgrade to text-only
            self._session_modes[session_id] = "text_only"
            logger.info(
                "Session %s downgraded to text-only after %d failures",
                session_id, count,
            )
            return FallbackResponse(
                action="text_only_mode",
                excuse_text=random.choice(TEXT_EXCUSES),
                retry_queued=False,
            )

        # Recoverable failure — send excuse, queue retry
        return FallbackResponse(
            action="text_excuse_with_retry",
            excuse_text=random.choice(TEXT_EXCUSES),
            retry_queued=True,
            followup_text=random.choice(FOLLOWUP_ON_SUCCESS),
        )

    def is_text_only(self, session_id: str) -> bool:
        """Check if session has been downgraded to text-only mode."""
        return self._session_modes.get(session_id) == "text_only"

    def reset_session(self, session_id: str) -> None:
        """Reset failure count for a session."""
        self._failure_counts.pop(session_id, None)
        self._session_modes.pop(session_id, None)

    def get_failure_count(self, session_id: str) -> int:
        """Get current failure count for a session."""
        return self._failure_counts.get(session_id, 0)
