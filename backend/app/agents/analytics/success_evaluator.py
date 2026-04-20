"""
Success Evaluator — Determines if a baiting session achieved its objectives.

A session is successful if ANY of:
- Time wasted > 10 minutes
- Scammer abandoned conversation
- Scammer showed frustration/anger

Grades sessions S/A/B/C/F with quality metrics.
"""

from __future__ import annotations

import logging

from app.models.analytics_models import ConversationAnalytics, SessionSuccess

logger = logging.getLogger(__name__)


class SuccessEvaluator:
    """Evaluates whether a baiting session achieved its objectives."""

    # Minimum thresholds
    MIN_TIME_WASTED_MINUTES = 10.0
    ABANDONMENT_SILENCE_SECONDS = 600  # 10 min
    FRUSTRATION_PATIENCE_THRESHOLD = 2.0

    def evaluate(self, session: ConversationAnalytics) -> SessionSuccess:
        """Evaluate session success based on multiple criteria."""

        conditions = {
            "time_wasted": session.time_wasted_minutes >= self.MIN_TIME_WASTED_MINUTES,
            "scammer_abandoned": self._detect_abandonment(session),
            "scammer_frustrated": self._detect_frustration(session),
        }

        is_successful = any(conditions.values())

        # Quality metrics (don't affect success, measure quality)
        quality = {
            "stealth_maintained": session.suspicion_score < 0.3,
            "strategy_diversity": len(set(session.strategies_used)) >= 2,
            "deception_accepted": session.deception_success_rate > 0.5,
        }

        grade = self._compute_grade(conditions, quality)

        result = SessionSuccess(
            successful=is_successful,
            conditions_met=conditions,
            quality_metrics=quality,
            grade=grade,
        )

        logger.info(
            "Session %s evaluated: success=%s grade=%s conditions=%s",
            session.session_id, is_successful, grade, conditions,
        )

        return result

    def _detect_abandonment(self, session: ConversationAnalytics) -> bool:
        """Scammer stopped responding for >10 min after our last message."""
        if not session.last_message_role:
            return False
        return (
            session.last_message_role == "assistant"
            and session.silence_duration_seconds > self.ABANDONMENT_SILENCE_SECONDS
        )

    def _detect_frustration(self, session: ConversationAnalytics) -> bool:
        """Scammer showed anger signals in recent messages."""
        if not session.patience_trajectory or len(session.patience_trajectory) < 2:
            return False
        recent = session.patience_trajectory[-3:]
        return min(recent) < self.FRUSTRATION_PATIENCE_THRESHOLD

    @staticmethod
    def _compute_grade(conditions: dict[str, bool], quality: dict[str, bool]) -> str:
        """S/A/B/C/F grading system."""
        cond_score = sum(1 for v in conditions.values() if v) * 2
        qual_score = sum(1 for v in quality.values() if v)
        total = cond_score + qual_score

        if total >= 8:
            return "S"  # Legendary
        if total >= 6:
            return "A"  # Excellent
        if total >= 4:
            return "B"  # Good
        if total >= 2:
            return "C"  # Acceptable
        return "F"      # Failed
