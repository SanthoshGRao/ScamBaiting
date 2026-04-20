"""
PII Sanitizer — Strips personally identifiable information before LLM calls.

Type-tagged replacement preserves semantic context for LLM analysis
while protecting user privacy. Supports Indian-specific PII patterns.

Location: Backend (FastAPI)
Max: 300 lines | Async-first | Production-ready
"""

from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class SanitizationResult:
    """Result of PII sanitization with metadata."""
    sanitized_text: str
    original_length: int
    sanitized_length: int
    pii_found: dict[str, int] = field(default_factory=dict)
    total_pii_count: int = 0
    processing_time_ms: float = 0.0


# --- PII Patterns ---
# Order matters: more specific patterns first to avoid partial matches

_PII_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    # Aadhaar: 12 digits with optional spaces/hyphens (e.g., 1234 5678 9012)
    ("AADHAAR", re.compile(
        r"\b[2-9]\d{3}[\s-]?\d{4}[\s-]?\d{4}\b"
    )),

    # PAN Card: ABCDE1234F
    ("PAN", re.compile(
        r"\b[A-Z]{5}\d{4}[A-Z]\b"
    )),

    # Credit/Debit Card: 13-19 digits with optional spaces/hyphens
    ("CARD", re.compile(
        r"\b(?:\d{4}[\s-]?){3,4}\d{1,4}\b"
    )),

    # UPI ID: username@provider (e.g., user@ybl, user@paytm)
    ("UPI", re.compile(
        r"\b[a-zA-Z0-9._-]+@(?:ybl|paytm|oksbi|okaxis|okicici|okhdfcbank|"
        r"upi|apl|axisb|ibl|sbi|icici|hdfcbank|bankofbaroda|"
        r"kotak|indus|federal|rbl|citi|sc|hsbc)\b",
        re.IGNORECASE,
    )),

    # Indian Phone: +91 followed by 10 digits, various formats
    ("PHONE", re.compile(
        r"(?:\+91[\s-]?)?(?:\(?\d{2,5}\)?[\s-]?)?\d{5}[\s-]?\d{5}\b"
    )),

    # International Phone: + followed by country code and number
    ("PHONE", re.compile(
        r"\+\d{1,3}[\s-]?\(?\d{1,4}\)?[\s-]?\d{3,4}[\s-]?\d{3,4}\b"
    )),

    # Email Address
    ("EMAIL", re.compile(
        r"\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}\b"
    )),

    # OTP Codes: 4-8 digit codes often preceded by context words
    ("OTP", re.compile(
        r"(?:OTP|otp|code|Code|PIN|pin|CVV|cvv)[\s:]*(\d{4,8})\b"
    )),

    # Bank Account Number: 9-18 digit numbers
    ("BANK_ACCOUNT", re.compile(
        r"(?:a/?c|account|acct)[\s.:]*(?:no\.?|number|num)?[\s.:]*(\d{9,18})\b",
        re.IGNORECASE,
    )),

    # IFSC Code: 4 letters + 0 + 6 alphanumeric
    ("IFSC", re.compile(
        r"\b[A-Z]{4}0[A-Z0-9]{6}\b"
    )),

    # IP Address (IPv4)
    ("IP", re.compile(
        r"\b(?:25[0-5]|2[0-4]\d|[01]?\d\d?)(?:\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)){3}\b"
    )),
]

# Standalone OTP pattern (digits that look like OTPs without context)
_STANDALONE_OTP = re.compile(r"\b\d{6}\b")


class PIISanitizer:
    """
    Strips PII from text using type-tagged replacement.

    Replaces detected PII with semantic tags like [PHONE], [EMAIL],
    [AADHAAR] so the LLM retains context about what was removed
    while user privacy is protected.

    Thread-safe, stateless — can be shared across requests.
    """

    def __init__(self, *, strict_mode: bool = False) -> None:
        """
        Args:
            strict_mode: If True, also strips standalone 6-digit numbers
                        as potential OTPs. May cause false positives.
        """
        self._strict_mode = strict_mode
        self._patterns = _PII_PATTERNS.copy()

        logger.info(
            "PIISanitizer initialized: %d patterns, strict=%s",
            len(self._patterns),
            self._strict_mode,
        )

    async def sanitize(self, text: str) -> str:
        """
        Sanitize text by replacing PII with type tags.

        Args:
            text: Raw message text to sanitize.

        Returns:
            Sanitized text with PII replaced by [TYPE] tags.
        """
        result = await self.sanitize_detailed(text)
        return result.sanitized_text

    async def sanitize_detailed(self, text: str) -> SanitizationResult:
        """
        Sanitize text and return detailed result with PII statistics.

        Returns:
            SanitizationResult with sanitized text and metadata.
        """
        start_time = time.monotonic()
        pii_counts: dict[str, int] = {}
        sanitized = text

        # Apply each pattern in order
        for pii_type, pattern in self._patterns:
            sanitized, count = self._apply_pattern(sanitized, pii_type, pattern)
            if count > 0:
                pii_counts[pii_type] = pii_counts.get(pii_type, 0) + count

        # Strict mode: catch standalone 6-digit OTPs
        if self._strict_mode:
            sanitized, count = self._apply_pattern(
                sanitized, "OTP", _STANDALONE_OTP
            )
            if count > 0:
                pii_counts["OTP"] = pii_counts.get("OTP", 0) + count

        total = sum(pii_counts.values())
        elapsed_ms = (time.monotonic() - start_time) * 1000

        if total > 0:
            logger.info(
                "PII sanitized: %d items removed (%s), %.1fms",
                total,
                ", ".join(f"{k}={v}" for k, v in pii_counts.items()),
                elapsed_ms,
            )

        return SanitizationResult(
            sanitized_text=sanitized,
            original_length=len(text),
            sanitized_length=len(sanitized),
            pii_found=pii_counts,
            total_pii_count=total,
            processing_time_ms=round(elapsed_ms, 2),
        )

    @staticmethod
    def _apply_pattern(
        text: str, pii_type: str, pattern: re.Pattern[str]
    ) -> tuple[str, int]:
        """
        Replace all matches of a pattern with the type tag.

        Handles patterns with capture groups (replaces only the group).
        """
        tag = f"[{pii_type}]"

        if pattern.groups > 0:
            # Pattern has capture group — replace only the captured portion
            count = 0

            def _replace_group(match: re.Match[str]) -> str:
                nonlocal count
                count += 1
                full = match.group(0)
                captured = match.group(1)
                return full.replace(captured, tag)

            result = pattern.sub(_replace_group, text)
            return result, count
        else:
            # No capture group — replace entire match
            result, count = pattern.subn(tag, text)
            return result, count

    def get_pattern_count(self) -> int:
        """Return the number of active PII patterns."""
        return len(self._patterns)
