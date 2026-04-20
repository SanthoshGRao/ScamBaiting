"""
Severity Scorer — Multi-factor severity scoring for detected scams.

Computes a composite severity score (0-1) based on financial risk,
data exposure, urgency pressure, sophistication, and vulnerability.
"""

from __future__ import annotations

import logging

from app.models.analytics_models import SeverityResult

logger = logging.getLogger(__name__)


# Category-based financial risk mappings
FINANCIAL_RISK_MAP: dict[str, float] = {
    "financial_fraud": 0.95,
    "investment_fraud": 0.90,
    "romance_scam": 0.80,
    "impersonation": 0.75,
    "phishing": 0.70,
    "tech_support": 0.65,
    "job_scam": 0.60,
    "lottery_scam": 0.85,
    "advance_fee": 0.80,
    "crypto_scam": 0.90,
}


class SeverityScorer:
    """Multi-factor severity scoring for detected scams."""

    WEIGHTS = {
        "financial_risk": 0.30,
        "personal_data_risk": 0.25,
        "urgency_pressure": 0.15,
        "sophistication": 0.15,
        "target_vulnerability": 0.15,
    }

    def score(
        self,
        category: str = "unknown",
        features: dict[str, float] | None = None,
        matched_rules: list[str] | None = None,
    ) -> SeverityResult:
        """
        Compute composite severity score.

        Args:
            category: Detected scam category.
            features: Feature scores from detection pipeline.
            matched_rules: List of triggered rule IDs.
        """
        features = features or {}
        matched_rules = matched_rules or []
        factors: dict[str, float] = {}

        # Financial risk based on category
        factors["financial_risk"] = FINANCIAL_RISK_MAP.get(category, 0.5)

        # Personal data risk from features
        factors["personal_data_risk"] = max(
            features.get("financial_indicator", 0),
            features.get("link_suspicion", 0),
            features.get("pii_request", 0),
        )

        # Urgency pressure
        factors["urgency_pressure"] = features.get("urgency_score", 0)

        # Sophistication (inverse of rule catch rate)
        rule_count = len(matched_rules)
        factors["sophistication"] = max(0.0, 1.0 - (rule_count * 0.2))

        # Target vulnerability (default moderate)
        factors["target_vulnerability"] = features.get("vulnerability", 0.5)

        # Composite score
        severity = sum(
            self.WEIGHTS[k] * factors.get(k, 0.0) for k in self.WEIGHTS
        )
        severity = round(min(1.0, max(0.0, severity)), 3)

        result = SeverityResult(
            score=severity,
            level=self._level(severity),
            factors=factors,
            recommended_response=self._recommend(severity),
        )

        logger.info(
            "Severity scored: %.3f (%s) for category=%s",
            severity, result.level, category,
        )

        return result

    @staticmethod
    def _level(score: float) -> str:
        if score >= 0.8:
            return "CRITICAL"
        if score >= 0.6:
            return "HIGH"
        if score >= 0.4:
            return "MEDIUM"
        if score >= 0.2:
            return "LOW"
        return "MINIMAL"

    @staticmethod
    def _recommend(score: float) -> str:
        if score >= 0.8:
            return "BLOCK_AND_BAIT"
        if score >= 0.6:
            return "ALERT_AND_OFFER_BAIT"
        if score >= 0.4:
            return "WARN_USER"
        return "LOG_ONLY"
