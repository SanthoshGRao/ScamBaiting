"""
Strategy Agent — Adaptive baiting strategy selection using LLM reasoning.

Optimized:
- Reduced token usage
- Added randomness for realism
- Improved fallback logic
"""

from __future__ import annotations

import json
import logging
import random
from typing import Any

from app.models.analytics_models import StrategySelection, SuspicionResult
from app.agents.strategy.suspicion_detector import SuspicionDetector
from app.agents.strategy.stealth_optimizer import StealthOptimizer

logger = logging.getLogger(__name__)


# Strategy behavioral rules for prompt injection (one dense line each).
STRATEGY_RULES: dict[str, str] = {
    "CONFUSION": (
        "Garble 2 concrete details they said (name/amount/app/link); ask one dumb "
        "clarification; sound unsure—not rude."
    ),
    "DELAY": (
        "Give a specific real-life blocker (meeting/drive/kid/boss/low battery); "
        "commit to a vague time; no new lies that break FACTS."
    ),
    "FAKE_COMPLIANCE": (
        "Sound cooperative then cite one believable friction: pending SMS, wrong "
        "digit in OTP, paste fails, app spinner, 'invalid'—ask them to resend/rephrase."
    ),
    "AGGRESSION": (
        "Short skeptic pushback: why rush, prove identity, weird phrasing; no essay."
    ),
    "DERAILMENT": (
        "One unrelated half-sentence + tiny hook back; don't fully answer their last demand."
    ),
    "ESCALATION": (
        "Ask for official proof chain: receipt, company name, supervisor, ID—pick one, stay brief."
    ),
}


class StrategyAgent:
    """Selects optimal baiting strategy using LLM reasoning + suspicion detection."""

    STRATEGY_SELECTION_PROMPT = """
Pick best strategy to waste scammer time.

Stage: {stage}
Category: {category}
Messages: {msg_count}
Patience: {patience}
Current: {current_strategy}
Time: {time_wasted_min}
Suspicion: {suspicion_level}

Options: CONFUSION, DELAY, FAKE_COMPLIANCE, AGGRESSION, DERAILMENT, ESCALATION

Rules:
- early → CONFUSION/DELAY
- action requested → FAKE_COMPLIANCE
- low patience → AGGRESSION
- high patience → DERAILMENT
- high suspicion → CONFUSION

Return JSON:
{{"strategy": "...", "switch_confidence": 0-1}}
"""

    def __init__(self, llm_provider: Any = None) -> None:
        self._llm = llm_provider
        self._suspicion_detector = SuspicionDetector()
        self._stealth = StealthOptimizer()
        self._strategy_history: dict[str, list[str]] = {}

    async def select_strategy(
        self,
        session_id: str,
        history: list[dict],
        scam_category: str = "unknown",
        current_strategy: str | None = None,
    ) -> StrategySelection:
        """Select the optimal baiting strategy for the current conversation state."""

        # 1. Suspicion check FIRST
        suspicion = self._suspicion_detector.compute_suspicion_score(history)

        if suspicion.level == "HIGH" and current_strategy:
            emergency = self._suspicion_detector.get_emergency_strategy(
                current_strategy
            )
            logger.warning(
                "EMERGENCY strategy switch: %s -> %s (%.2f)",
                current_strategy,
                emergency,
                suspicion.score,
            )
            self._record_strategy(session_id, emergency)
            return StrategySelection(
                strategy=emergency,
                reasoning=f"Emergency switch due to HIGH suspicion",
                switch_confidence=0.95,
            )

        # 2. Analyze state
        stage = self._classify_stage(history)
        patience = self._estimate_patience(history)
        time_wasted = self._calc_time_wasted(history)

        # 3. Strategy repetition check
        recent_strategies = self._strategy_history.get(session_id, [])
        if (
            current_strategy
            and len(recent_strategies) >= 3
            and all(s == current_strategy for s in recent_strategies[-3:])
        ):
            logger.info("Forcing strategy switch (repetition detected)")

        # 4. LLM or heuristic
        if self._llm:
            selection = await self._llm_select(
                stage,
                scam_category,
                len(history),
                patience,
                current_strategy or "NONE",
                time_wasted,
                suspicion.level,
            )
        else:
            selection = self._heuristic_select(
                stage, patience, current_strategy, suspicion.level
            )

        # 🔥 ADD RANDOMNESS (important)
        if random.random() < 0.15:
            selection.strategy = random.choice(list(STRATEGY_RULES.keys()))
            selection.reasoning = "random variation"

        self._record_strategy(session_id, selection.strategy)
        return selection

    async def _llm_select(
        self,
        stage: str,
        category: str,
        msg_count: int,
        patience: float,
        current: str,
        time_wasted: float,
        suspicion_level: str,
    ) -> StrategySelection:
        """Use LLM to select strategy."""

        prompt = self.STRATEGY_SELECTION_PROMPT.format(
            stage=stage,
            category=category,
            msg_count=msg_count,
            patience=round(patience, 2),
            current_strategy=current,
            time_wasted_min=round(time_wasted, 2),
            suspicion_level=suspicion_level,
        )

        try:
            response = await self._llm.generate_text(
                messages=[{"role": "user", "content": prompt}],
                temperature=0.2,
                max_tokens=80,  # ✅ reduced from 200
            )

            data = json.loads(response)

            return StrategySelection(
                strategy=data.get("strategy", "CONFUSION"),
                reasoning=data.get("reasoning", ""),
                switch_confidence=data.get("switch_confidence", 0.5),
            )

        except Exception as e:
            logger.warning("LLM strategy selection failed: %s", e)
            return self._heuristic_select(stage, patience, current, suspicion_level)

    def _heuristic_select(
        self,
        stage: str,
        patience: float,
        current: str | None,
        suspicion_level: str,
    ) -> StrategySelection:
        """Fallback heuristic strategy selection."""

        if suspicion_level == "HIGH":
            strategy = "CONFUSION"
            reason = "high suspicion"
        elif stage == "early":
            strategy = "CONFUSION"
            reason = "early stage"
        elif stage == "mid" and patience > 3:
            strategy = "FAKE_COMPLIANCE"
            reason = "scammer patient"
        elif stage == "mid":
            strategy = "DELAY"
            reason = "stalling"
        elif stage == "late" and patience <= 2:
            strategy = "AGGRESSION"
            reason = "low patience"
        elif stage == "late":
            strategy = "ESCALATION"
            reason = "late stage"
        elif patience > 4:
            strategy = "DERAILMENT"
            reason = "very patient scammer"
        else:
            strategy = "DELAY"
            reason = "default"

        # ✅ FIX: random fallback instead of fixed
        if strategy == current and strategy != "CONFUSION":
            alternatives = [s for s in STRATEGY_RULES if s != current]
            if alternatives:
                strategy = random.choice(alternatives)

        return StrategySelection(
            strategy=strategy,
            reasoning=reason,
            switch_confidence=0.7,
        )

    def get_strategy_rules(self, strategy: str) -> str:
        """Get behavior rules for strategy."""
        return STRATEGY_RULES.get(strategy, STRATEGY_RULES["CONFUSION"])

    @staticmethod
    def _classify_stage(history: list[dict]) -> str:
        msg_count = len(history)
        if msg_count <= 4:
            return "early"
        elif msg_count <= 12:
            return "mid"
        elif msg_count <= 24:
            return "late"
        return "exit"

    @staticmethod
    def _estimate_patience(history: list[dict]) -> float:
        scammer_msgs = [m for m in history if m.get("role") in ("user", "scammer")]
        if not scammer_msgs:
            return 5.0

        scores = []
        for msg in scammer_msgs[-5:]:
            text = msg.get("content", "").lower()
            score = 5.0

            if any(w in text for w in ["!!!", "???", "hurry", "now"]):
                score -= 2.0
            if text.isupper() and len(text) > 5:
                score -= 1.5
            if len(text) < 10:
                score -= 0.5
            if any(w in text for w in ["please", "sir"]):
                score += 1.0

            scores.append(max(1.0, min(5.0, score)))

        return sum(scores) / len(scores)

    @staticmethod
    def _calc_time_wasted(history: list[dict]) -> float:
        return len(history) * 1.5

    def _record_strategy(self, session_id: str, strategy: str) -> None:
        if session_id not in self._strategy_history:
            self._strategy_history[session_id] = []

        self._strategy_history[session_id].append(strategy)
        self._strategy_history[session_id] = self._strategy_history[session_id][-10:]