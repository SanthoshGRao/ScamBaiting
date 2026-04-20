"""
Detection Helpers — Utility functions for DetectionAgent pipeline.

Extracted from DetectionAgent to maintain 300-line file limit.
Contains: risk classification, action mapping, reasoning builders.
"""

from __future__ import annotations

from app.config.detection_settings import DetectionSettings
from app.models.detection_models import (
    RecommendedAction,
    RiskLevel,
    RuleVerdict,
)


def classify_risk(confidence: float, settings: DetectionSettings) -> RiskLevel:
    """Map confidence score to risk level using configured thresholds."""
    if confidence >= settings.high_risk_threshold:
        return RiskLevel.HIGH
    elif confidence >= settings.medium_risk_threshold:
        return RiskLevel.MEDIUM
    elif confidence >= settings.low_risk_threshold:
        return RiskLevel.LOW
    return RiskLevel.SAFE


def map_action(risk: RiskLevel) -> RecommendedAction:
    """Map risk level to recommended system action."""
    return {
        RiskLevel.HIGH: RecommendedAction.WARN_USER,
        RiskLevel.MEDIUM: RecommendedAction.WARN_USER,
        RiskLevel.LOW: RecommendedAction.LOG_ONLY,
        RiskLevel.SAFE: RecommendedAction.NONE,
    }.get(risk, RecommendedAction.NONE)


def build_rule_reasoning(verdict: RuleVerdict) -> str:
    """Build technical reasoning string from matched rules."""
    if not verdict.matched_rules:
        return "No rules matched"
    rule_names = [r.rule for r in verdict.matched_rules[:5]]
    return f"Matched rules: {', '.join(rule_names)}"


def build_user_explanation(verdict: RuleVerdict, risk: RiskLevel) -> str:
    """Build user-facing explanation from rule verdict and risk level."""
    if risk == RiskLevel.SAFE:
        return "This message appears to be safe."
    if risk == RiskLevel.LOW:
        return (
            "This message has some characteristics of known scams. "
            "Stay cautious."
        )

    categories = verdict.category_hints[:2] if verdict.category_hints else []
    cat_text = " and ".join(c.replace("_", " ") for c in categories)

    if risk == RiskLevel.HIGH:
        return (
            f"⚠️ This message is very likely a scam ({cat_text}). "
            "Do not respond or click any links."
        )
    return f"This message shows signs of {cat_text}. Verify before taking action."
