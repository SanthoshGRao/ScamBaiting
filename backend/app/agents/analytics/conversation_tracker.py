"""
Conversation Tracker — Tracks how baiting conversations evolve over time.

Computes sentiment trajectories, patience curves, engagement metrics,
and time-wasted calculations for each active session.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from app.models.analytics_models import ConversationAnalytics

logger = logging.getLogger(__name__)


class ConversationTracker:
    """Tracks how a baiting conversation evolves over time."""

    def __init__(self) -> None:
        self._sessions: dict[str, ConversationAnalytics] = {}

    def get_or_create(self, session_id: str) -> ConversationAnalytics:
        """Get or create analytics for a session."""
        if session_id not in self._sessions:
            self._sessions[session_id] = ConversationAnalytics(
                session_id=session_id,
            )
        return self._sessions[session_id]

    def update(
        self,
        session_id: str,
        messages: list[dict],
        strategies_used: list[str] | None = None,
    ) -> ConversationAnalytics:
        """Update analytics with the full message history."""
        analytics = self.get_or_create(session_id)

        scammer_msgs = [
            m for m in messages if m.get("role") in ("user", "scammer")
        ]
        ai_msgs = [
            m for m in messages if m.get("role") == "assistant"
        ]

        # Message counts
        analytics.total_messages = len(messages)
        analytics.scammer_messages = len(scammer_msgs)
        analytics.ai_messages = len(ai_msgs)

        # Patience trajectory
        analytics.patience_trajectory = self._estimate_patience_trajectory(
            scammer_msgs
        )

        # Sentiment trajectory (simplified)
        analytics.sentiment_trajectory = self._compute_sentiment_trajectory(
            scammer_msgs
        )

        # Duration
        analytics.total_duration_seconds = self._calc_duration(messages)

        # Time wasted
        analytics.time_wasted_minutes = round(
            analytics.total_duration_seconds / 60.0, 1
        )

        # Cost estimate ($3.50/hour average scam center rate)
        analytics.estimated_cost_to_scammer = round(
            (analytics.total_duration_seconds / 3600.0) * 3.50, 2
        )

        # Average response time
        analytics.avg_scammer_response_time = self._avg_response_time(
            messages
        )

        # Last message role
        if messages:
            analytics.last_message_role = messages[-1].get("role", "")

        # Silence tracking
        if messages and len(messages) >= 2:
            last_ts = messages[-1].get("timestamp")
            if last_ts:
                if isinstance(last_ts, str):
                    last_ts = datetime.fromisoformat(last_ts)
                now = datetime.now(timezone.utc)
                if isinstance(last_ts, datetime):
                    analytics.silence_duration_seconds = int(
                        (now - last_ts).total_seconds()
                    )

        # Strategy tracking
        if strategies_used:
            analytics.strategies_used = strategies_used

        self._sessions[session_id] = analytics
        return analytics

    def _estimate_patience_trajectory(
        self, scammer_msgs: list[dict]
    ) -> list[float]:
        """Estimate scammer patience over time."""
        scores: list[float] = []
        for msg in scammer_msgs:
            score = 5.0
            text = msg.get("content", "").lower()

            # Frustration indicators
            if any(w in text for w in ["!!!", "???", "come on", "now", "hurry"]):
                score -= 2.0
            if text.isupper() and len(text) > 5:
                score -= 1.5
            if len(text) < 10:
                score -= 0.5
            if any(w in text for w in ["please", "kindly", "sir", "madam"]):
                score += 1.0
            if any(w in text for w in ["stupid", "idiot", "fool", "useless"]):
                score -= 2.5
            if "?" in text and text.count("?") > 2:
                score -= 1.0

            scores.append(round(max(1.0, min(5.0, score)), 1))

        return scores

    def _compute_sentiment_trajectory(
        self, scammer_msgs: list[dict]
    ) -> list[float]:
        """Compute simplified sentiment for scammer messages.

        Returns values from -1.0 (very negative) to 1.0 (very positive).
        """
        scores: list[float] = []
        positive_words = {
            "thank", "thanks", "good", "great", "ok", "okay",
            "please", "sir", "madam", "kindly",
        }
        negative_words = {
            "idiot", "stupid", "fool", "waste", "scam", "fake",
            "useless", "liar", "cheat", "fraud", "block",
        }

        for msg in scammer_msgs:
            words = msg.get("content", "").lower().split()
            pos = sum(1 for w in words if w in positive_words)
            neg = sum(1 for w in words if w in negative_words)
            total = max(1, pos + neg)
            sentiment = round((pos - neg) / total, 2)
            scores.append(max(-1.0, min(1.0, sentiment)))

        return scores

    @staticmethod
    def _calc_duration(messages: list[dict]) -> int:
        """Calculate total duration in seconds from first to last message."""
        timestamps = []
        for m in messages:
            ts = m.get("timestamp")
            if ts:
                if isinstance(ts, str):
                    try:
                        ts = datetime.fromisoformat(ts)
                    except ValueError:
                        continue
                if isinstance(ts, datetime):
                    timestamps.append(ts)

        if len(timestamps) < 2:
            # Fallback: estimate ~90s per message
            return len(messages) * 90

        return int((max(timestamps) - min(timestamps)).total_seconds())

    @staticmethod
    def _avg_response_time(messages: list[dict]) -> float:
        """Calculate average scammer response time in seconds."""
        gaps: list[float] = []
        prev_ts = None
        prev_role = None

        for m in messages:
            ts = m.get("timestamp")
            role = m.get("role")
            if ts and isinstance(ts, str):
                try:
                    ts = datetime.fromisoformat(ts)
                except ValueError:
                    continue

            if (
                isinstance(ts, datetime)
                and prev_ts
                and role in ("user", "scammer")
                and prev_role == "assistant"
            ):
                gaps.append((ts - prev_ts).total_seconds())

            prev_ts = ts
            prev_role = role

        return round(sum(gaps) / len(gaps), 1) if gaps else 0.0

    def get_analytics(self, session_id: str) -> ConversationAnalytics | None:
        """Retrieve analytics for a session."""
        return self._sessions.get(session_id)

    def get_all_sessions(self) -> list[ConversationAnalytics]:
        """Return analytics for all tracked sessions."""
        return list(self._sessions.values())
