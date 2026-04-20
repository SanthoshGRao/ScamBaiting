"""
Suspicion Detector — Monitors scammer messages for suspicion signals.

Detects when a scammer suspects they are talking to a bot/AI
and triggers emergency strategy switches to maintain stealth.
"""

from __future__ import annotations

import logging
from datetime import datetime

from app.models.analytics_models import SuspicionResult

logger = logging.getLogger(__name__)


class SuspicionDetector:
    """Monitors scammer messages for signs they suspect AI/bot interaction."""

    # Direct suspicion keywords (high weight: +0.40)
    DIRECT_SUSPICION = [
        "are you real", "are you a bot", "is this a bot",
        "are you human", "this is fake", "you're a bot",
        "stop wasting my time", "you are wasting time",
        "i know this is fake", "are you ai", "is this ai",
        "stop playing games", "this is automated",
        "you're not a real person", "prove you're real",
        "you are a robot", "talking to a machine",
        "this is a scam", "you are scamming me",
    ]

    # Indirect suspicion signals (medium weight: +0.20)
    INDIRECT_SIGNALS = [
        "why do you keep saying the same thing",
        "you said that already", "you sound weird",
        "something is off", "are you okay",
        "why are you talking like that",
        "you don't make sense",
        "you're not listening",
        "are you even reading",
        "hello are you there",
        "you keep repeating",
    ]

    # Emergency strategy switch mapping
    EMERGENCY_SWITCH_MAP = {
        "FAKE_COMPLIANCE": "CONFUSION",
        "AGGRESSION": "DERAILMENT",
        "CONFUSION": "DELAY",
        "DELAY": "DERAILMENT",
        "ESCALATION": "CONFUSION",
        "DERAILMENT": "DELAY",
    }

    def compute_suspicion_score(
        self,
        messages: list[dict],
        window_size: int = 6,
    ) -> SuspicionResult:
        """
        Analyze recent scammer messages for suspicion signals.

        Args:
            messages: List of message dicts with 'role', 'content', 'timestamp'.
            window_size: Number of recent messages to analyze.
        """
        recent = [
            m for m in messages[-window_size:]
            if m.get("role") in ("user", "scammer")
        ]

        score = 0.0
        triggers: list[str] = []

        for msg in recent:
            text = msg.get("content", "").lower()

            # Direct suspicion keywords (high impact)
            for keyword in self.DIRECT_SUSPICION:
                if keyword in text:
                    score += 0.40
                    triggers.append(f"direct: '{keyword}'")
                    break  # Only one trigger per message

            # Indirect signals (medium impact)
            for signal in self.INDIRECT_SIGNALS:
                if signal in text:
                    score += 0.20
                    triggers.append(f"indirect: '{signal}'")
                    break

            # Tone shift detection: ALL CAPS aggression
            if text.isupper() and len(text) > 15:
                score += 0.15
                triggers.append("tone: ALL CAPS aggression")

            # Very short terse messages = frustration
            if len(text.split()) <= 2 and any(
                w in text for w in ["what", "hello", "answer", "reply"]
            ):
                score += 0.10
                triggers.append(f"terse: '{text}'")

        # Frequency drop analysis
        timestamps = [
            m.get("timestamp") for m in recent
            if m.get("timestamp") and m.get("role") in ("user", "scammer")
        ]
        if len(timestamps) >= 2:
            avg_gap = self._avg_gap_seconds(timestamps)
            if avg_gap > 300:  # >5 min between responses
                score += 0.15
                triggers.append(
                    f"frequency_drop: avg {avg_gap:.0f}s between msgs"
                )

        score = min(1.0, score)
        level = self._classify(score)
        action = self._recommend(score)

        if level != "LOW":
            logger.info(
                "Suspicion detected: score=%.2f level=%s triggers=%s",
                score, level, triggers,
            )

        return SuspicionResult(
            score=round(score, 3),
            level=level,
            triggers=triggers,
            recommended_action=action,
        )

    def get_emergency_strategy(self, current_strategy: str) -> str:
        """Get the emergency fallback strategy when suspicion is HIGH."""
        return self.EMERGENCY_SWITCH_MAP.get(current_strategy, "CONFUSION")

    @staticmethod
    def _classify(score: float) -> str:
        if score >= 0.60:
            return "HIGH"
        if score >= 0.30:
            return "MEDIUM"
        return "LOW"

    @staticmethod
    def _recommend(score: float) -> str:
        if score >= 0.60:
            return "EMERGENCY_SWITCH"
        if score >= 0.30:
            return "SOFT_PIVOT"
        return "CONTINUE"

    @staticmethod
    def _avg_gap_seconds(timestamps: list) -> float:
        """Calculate average gap between timestamps."""
        gaps = []
        for i in range(1, len(timestamps)):
            t1 = timestamps[i - 1]
            t2 = timestamps[i]
            if isinstance(t1, str):
                t1 = datetime.fromisoformat(t1)
            if isinstance(t2, str):
                t2 = datetime.fromisoformat(t2)
            if isinstance(t1, datetime) and isinstance(t2, datetime):
                gaps.append(abs((t2 - t1).total_seconds()))
        return sum(gaps) / len(gaps) if gaps else 0.0
