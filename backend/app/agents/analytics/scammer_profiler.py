"""
Scammer Profiler — Builds persistent behavioral profiles across sessions.

Tracks sophistication, aggression, vocabulary, response patterns,
and strategy effectiveness per scammer identity.
"""

from __future__ import annotations

import hashlib
import logging
from collections import defaultdict
from datetime import datetime, timezone

from app.models.analytics_models import ScammerProfile

logger = logging.getLogger(__name__)


class ScammerProfiler:
    """Builds and updates persistent scammer profiles."""

    def __init__(self) -> None:
        self._profiles: dict[str, ScammerProfile] = {}

    def get_or_create(self, scammer_id: str) -> ScammerProfile:
        """Get existing profile or create a new one."""
        hashed = self._hash_id(scammer_id)
        if hashed not in self._profiles:
            self._profiles[hashed] = ScammerProfile(scammer_id=hashed)
            logger.info("New scammer profile created: %s", hashed[:8])
        return self._profiles[hashed]

    def update_from_session(
        self,
        scammer_id: str,
        messages: list[dict],
        scam_category: str = "unknown",
        strategies_used: list[str] | None = None,
        time_wasted_seconds: int = 0,
    ) -> ScammerProfile:
        """Update profile with data from a completed session."""
        profile = self.get_or_create(scammer_id)
        now = datetime.now(timezone.utc)

        profile.last_seen = now
        profile.total_sessions += 1
        profile.total_time_wasted_seconds += time_wasted_seconds

        # Extract scammer messages
        scammer_msgs = [
            m.get("content", "")
            for m in messages
            if m.get("role") in ("user", "scammer")
        ]
        profile.total_messages_received += len(scammer_msgs)

        if scammer_msgs:
            # Average message length
            lengths = [len(m) for m in scammer_msgs]
            profile.avg_message_length = sum(lengths) / len(lengths)

            # Vocabulary richness
            all_words = " ".join(scammer_msgs).lower().split()
            if all_words:
                profile.vocabulary_richness = round(
                    len(set(all_words)) / len(all_words), 3
                )

            # Urgency tendency
            urgency_words = {"urgent", "now", "immediately", "hurry", "quick", "fast", "asap"}
            urgency_count = sum(
                1 for w in all_words if w in urgency_words
            )
            profile.urgency_tendency = round(
                min(1.0, urgency_count / max(1, len(scammer_msgs))), 3
            )

            # Aggression tendency
            aggression_indicators = 0
            for msg in scammer_msgs:
                if msg.isupper() and len(msg) > 10:
                    aggression_indicators += 1
                if any(w in msg.lower() for w in ["stupid", "idiot", "fool", "waste"]):
                    aggression_indicators += 1
            profile.aggression_tendency = round(
                min(1.0, aggression_indicators / max(1, len(scammer_msgs))), 3
            )

        # Scam type tracking
        if scam_category != "unknown":
            if scam_category not in profile.scam_types_seen:
                profile.scam_types_seen.append(scam_category)
            profile.primary_scam_type = scam_category

        # Sophistication scoring
        profile.sophistication_score = self._score_sophistication(profile)

        # Strategy effectiveness
        if strategies_used and time_wasted_seconds > 0:
            for strat in set(strategies_used):
                current = profile.strategy_effectiveness.get(strat, 0.0)
                # Running average
                profile.strategy_effectiveness[strat] = round(
                    (current + time_wasted_seconds / 60.0) / 2, 1
                )

            # Find most effective
            if profile.strategy_effectiveness:
                profile.most_effective_strategy = max(
                    profile.strategy_effectiveness,
                    key=profile.strategy_effectiveness.get,  # type: ignore[arg-type]
                )

        logger.info(
            "Profile updated: %s sessions=%d time_wasted=%ds sophistication=%.2f",
            profile.scammer_id[:8], profile.total_sessions,
            profile.total_time_wasted_seconds, profile.sophistication_score,
        )

        return profile

    def get_profile(self, scammer_id: str) -> ScammerProfile | None:
        """Retrieve a profile by scammer ID."""
        hashed = self._hash_id(scammer_id)
        return self._profiles.get(hashed)

    def get_all_profiles(self) -> list[ScammerProfile]:
        """Return all tracked scammer profiles."""
        return list(self._profiles.values())

    @staticmethod
    def _score_sophistication(profile: ScammerProfile) -> float:
        """Score scammer sophistication from behavioral signals."""
        score = 0.5  # Start neutral

        # Higher vocabulary richness = more sophisticated
        if profile.vocabulary_richness > 0.6:
            score += 0.15
        elif profile.vocabulary_richness < 0.3:
            score -= 0.15

        # Longer messages = potentially more sophisticated
        if profile.avg_message_length > 100:
            score += 0.1
        elif profile.avg_message_length < 30:
            score -= 0.1

        # Low aggression = more patient/professional
        if profile.aggression_tendency < 0.1:
            score += 0.15
        elif profile.aggression_tendency > 0.5:
            score -= 0.1

        # Multiple scam types = organized operation
        if len(profile.scam_types_seen) > 2:
            score += 0.1

        return round(min(1.0, max(0.0, score)), 3)

    @staticmethod
    def _hash_id(scammer_id: str) -> str:
        """Hash scammer identity for privacy."""
        return hashlib.sha256(scammer_id.encode()).hexdigest()[:16]
