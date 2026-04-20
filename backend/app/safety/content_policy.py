"""
Content Policy — Pre-screens all deception requests against safety rules.

Blocks generation of real identity documents, content involving minors,
government seals, and requests targeting specific real individuals.
"""

from __future__ import annotations

import logging
import re

from app.models.safety_models import PolicyResult

logger = logging.getLogger(__name__)


class ContentPolicy:
    """Pre-screens all deception requests against safety rules."""

    BLOCKED_PATTERNS = [
        re.compile(r"real\s+(passport|driver.?s?\s+license|aadhaar|ssn)", re.I),
        re.compile(r"(child|minor|underage)", re.I),
        re.compile(r"(bomb|weapon|drug|narcotic)", re.I),
        re.compile(r"(government|official)\s+(seal|stamp|letterhead)", re.I),
        re.compile(r"(terrorism|extremis[mt])", re.I),
        re.compile(r"(sexual|explicit|nude)", re.I),
    ]

    ALLOWED_ARTIFACT_TYPES = {
        "bank_screenshot", "otp_screen", "receipt",
        "generic_id", "email", "freeform_image", "chat_screenshot",
    }

    # Known real institution exact names to block exact replicas
    BLOCKED_INSTITUTIONS = [
        "reserve bank of india", "federal reserve",
        "securities and exchange", "income tax department",
    ]

    def validate(self, artifact_type: str, context: str) -> PolicyResult:
        """
        Validate a deception request against content policy.

        Args:
            artifact_type: Type of artifact requested.
            context: Free-text context for the request.
        """
        violations: list[str] = []

        # Check artifact type
        if artifact_type not in self.ALLOWED_ARTIFACT_TYPES:
            violations.append(f"Blocked artifact type: {artifact_type}")

        # Check context for blocked patterns
        for pattern in self.BLOCKED_PATTERNS:
            if pattern.search(context):
                violations.append("Blocked content pattern detected")
                break

        # Check for government institution targeting
        context_lower = context.lower()
        for inst in self.BLOCKED_INSTITUTIONS:
            if inst in context_lower:
                violations.append(
                    f"Cannot generate exact replicas of {inst} documents"
                )
                break

        # Check for real-person targeting indicators
        if self._contains_real_identity(context):
            violations.append("May target a real individual")

        result = PolicyResult(
            allowed=len(violations) == 0,
            violations=violations,
            requires_review=len(violations) > 0,
        )

        if violations:
            logger.warning(
                "Content policy BLOCKED: %s violations=%s",
                artifact_type, violations,
            )
        else:
            logger.debug("Content policy ALLOWED: %s", artifact_type)

        return result

    @staticmethod
    def _contains_real_identity(context: str) -> bool:
        """Detect if context appears to target a real person."""
        # Check for patterns like "generate ID for John Smith at 123 Main St"
        real_identity_patterns = [
            re.compile(r"for\s+[A-Z][a-z]+\s+[A-Z][a-z]+\s+at\s+\d+", re.I),
            re.compile(r"(real\s+person|actual\s+name|their\s+real)", re.I),
            re.compile(r"address[:\s]+\d+\s+[A-Z]", re.I),
        ]
        return any(p.search(context) for p in real_identity_patterns)
