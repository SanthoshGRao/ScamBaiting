"""
InputNormalizer — Text preprocessing module for DetectionAgent.

Cleans, normalizes, and enriches raw message text before analysis.
Handles: Unicode normalization, whitespace cleanup, URL extraction,
language detection, and leetspeak decoding.

Location: Backend (Python) — also mirrored in Android (Kotlin)
Max: 300 lines | Async-first | Production-ready
"""

from __future__ import annotations

import logging
import re
import time
import unicodedata
from typing import Any

from app.models.detection_models import (
    InputSource,
    NormalizedInput,
    RawMessageInput,
)

logger = logging.getLogger(__name__)

# --- Constants ---

# URL regex pattern (RFC 3986 compliant, practical subset)
_URL_PATTERN = re.compile(
    r"https?://[^\s<>\"')\]]+|"    # http/https URLs
    r"www\.[^\s<>\"')\]]+|"         # www. prefixed
    r"bit\.ly/[^\s<>\"')\]]+|"     # bit.ly shortener
    r"tinyurl\.com/[^\s<>\"')\]]+", # tinyurl shortener
    re.IGNORECASE,
)

# Devanagari Unicode block range
_DEVANAGARI_PATTERN = re.compile(r"[\u0900-\u097F]")

# Leetspeak mapping (common substitutions)
_LEETSPEAK_MAP: dict[str, str] = {
    "0": "o", "1": "i", "3": "e", "4": "a", "5": "s",
    "7": "t", "8": "b", "@": "a", "$": "s", "!": "i",
    "|": "l", "(": "c", "+": "t",
}

# Homoglyph → ASCII mapping (common Unicode confusables)
_HOMOGLYPH_MAP: dict[str, str] = {
    "\u0410": "A", "\u0412": "B", "\u0421": "C", "\u0415": "E",  # Cyrillic
    "\u041d": "H", "\u041a": "K", "\u041c": "M", "\u041e": "O",
    "\u0420": "P", "\u0422": "T", "\u0425": "X",
    "\u0430": "a", "\u0435": "e", "\u043e": "o", "\u0440": "p",
    "\u0441": "c", "\u0443": "y", "\u0445": "x",
    "\u200b": "",  # Zero-width space
    "\u200c": "",  # Zero-width non-joiner
    "\u200d": "",  # Zero-width joiner
    "\ufeff": "",  # BOM
    "\u00a0": " ",  # Non-breaking space
}

# Whitespace normalization
_MULTI_SPACE = re.compile(r"\s+")
_MULTI_NEWLINE = re.compile(r"\n{3,}")


class InputNormalizer:
    """
    Preprocesses raw message text for the detection pipeline.

    Operations (in order):
    1. Unicode normalization (NFKC)
    2. Homoglyph replacement
    3. Zero-width character removal
    4. Whitespace normalization
    5. URL extraction (preserved in text, flagged in metadata)
    6. Leetspeak decoding
    7. Language detection
    """

    def __init__(self, default_language: str = "en") -> None:
        self._default_language = default_language
        self._langdetect_available = self._check_langdetect()

    @staticmethod
    def _check_langdetect() -> bool:
        """Check if langdetect library is available."""
        try:
            import langdetect  # noqa: F401
            return True
        except ImportError:
            logger.warning(
                "langdetect not installed — falling back to heuristic detection"
            )
            return False

    async def normalize(self, raw_input: RawMessageInput) -> NormalizedInput:
        """
        Main entry point — normalize a raw message input.

        Returns NormalizedInput with cleaned text, extracted URLs,
        detected language, and normalization metadata.

        Fallback: If any step fails, continues with best-effort result.
        """
        start_time = time.monotonic()
        applied_steps: list[str] = []
        text = raw_input.text

        # Step 1: Unicode NFKC normalization
        text, did_apply = self._normalize_unicode(text)
        if did_apply:
            applied_steps.append("unicode_nfkc")

        # Step 2: Homoglyph replacement
        text, did_apply = self._replace_homoglyphs(text)
        if did_apply:
            applied_steps.append("homoglyph_replacement")

        # Step 3: Whitespace normalization
        text, did_apply = self._normalize_whitespace(text)
        if did_apply:
            applied_steps.append("whitespace_normalization")

        # Step 4: Extract URLs (preserve in text, collect in metadata)
        urls = self._extract_urls(text)
        if urls:
            applied_steps.append("url_extraction")

        # Step 5: Create leetspeak-decoded copy for analysis
        decoded_text = self._decode_leetspeak(text)
        if decoded_text != text:
            applied_steps.append("leetspeak_decoding")
            # Use decoded text as primary (better for keyword matching)
            # but preserve original for LLM context
            analysis_text = decoded_text
        else:
            analysis_text = text

        # Step 6: Detect language
        language = await self._detect_language(analysis_text)
        applied_steps.append(f"language_detected:{language}")

        # Build result
        elapsed_ms = (time.monotonic() - start_time) * 1000

        result = NormalizedInput(
            text=analysis_text,
            original_text=raw_input.text,
            source=raw_input.source,
            sender=raw_input.sender,
            language=language,
            urls=urls,
            metadata={
                **raw_input.metadata,
                "normalization_time_ms": round(elapsed_ms, 2),
                "original_length": len(raw_input.text),
                "normalized_length": len(analysis_text),
                "url_count": len(urls),
            },
            normalization_applied=applied_steps,
        )

        logger.info(
            "Normalized message %s: %d→%d chars, %d URLs, lang=%s, %.1fms",
            result.message_id,
            len(raw_input.text),
            len(analysis_text),
            len(urls),
            language,
            elapsed_ms,
        )

        return result

    # --- Private Methods ---

    @staticmethod
    def _normalize_unicode(text: str) -> tuple[str, bool]:
        """Apply NFKC Unicode normalization to standardize characters."""
        normalized = unicodedata.normalize("NFKC", text)
        return normalized, normalized != text

    @staticmethod
    def _replace_homoglyphs(text: str) -> tuple[str, bool]:
        """Replace known homoglyph characters with ASCII equivalents."""
        original = text
        for glyph, replacement in _HOMOGLYPH_MAP.items():
            text = text.replace(glyph, replacement)
        return text, text != original

    @staticmethod
    def _normalize_whitespace(text: str) -> tuple[str, bool]:
        """Collapse multiple spaces/newlines, strip edges."""
        original = text
        text = _MULTI_SPACE.sub(" ", text)
        text = _MULTI_NEWLINE.sub("\n\n", text)
        text = text.strip()
        return text, text != original

    @staticmethod
    def _extract_urls(text: str) -> list[str]:
        """Extract all URLs from text without modifying the text."""
        return _URL_PATTERN.findall(text)

    @staticmethod
    def _decode_leetspeak(text: str) -> str:
        """
        Decode common leetspeak substitutions.

        Only applies to words that look like leetspeak (contain digits
        mixed with letters). Leaves pure numbers unchanged.
        """
        words = text.split()
        decoded_words: list[str] = []

        for word in words:
            # Only decode if word has both letters and digit/symbol substitutes
            has_alpha = any(c.isalpha() for c in word)
            has_leet = any(c in _LEETSPEAK_MAP for c in word)

            if has_alpha and has_leet:
                decoded = "".join(_LEETSPEAK_MAP.get(c, c) for c in word)
                decoded_words.append(decoded)
            else:
                decoded_words.append(word)

        return " ".join(decoded_words)

    async def _detect_language(self, text: str) -> str:
        """
        Detect language of the text.

        Priority:
        1. langdetect library (if available)
        2. Devanagari heuristic (for Hindi)
        3. Default fallback ("en")

        Hinglish is treated as "en" (LLM handles nuance).
        """
        # Heuristic: Check for Devanagari script (Hindi)
        devanagari_chars = len(_DEVANAGARI_PATTERN.findall(text))
        total_alpha = sum(1 for c in text if c.isalpha())

        if total_alpha > 0 and devanagari_chars / total_alpha > 0.3:
            return "hi"

        # Use langdetect if available
        if self._langdetect_available:
            try:
                from langdetect import DetectorFactory, detect

                DetectorFactory.seed = 0  # Deterministic results
                detected = detect(text)

                # Map to our supported set
                if detected == "hi":
                    return "hi"
                elif detected in ("en", "fr", "de", "es"):
                    return detected
                else:
                    # Unknown → treat as English (LLM handles it)
                    return self._default_language

            except Exception:
                logger.debug("langdetect failed, using fallback")

        return self._default_language

    # --- Utility Methods ---

    @staticmethod
    def truncate_text(text: str, max_length: int = 5000) -> str:
        """Truncate text to max length, preserving word boundaries."""
        if len(text) <= max_length:
            return text
        truncated = text[:max_length].rsplit(" ", 1)[0]
        return truncated + "..."

    @staticmethod
    def count_suspicious_indicators(text: str) -> dict[str, Any]:
        """
        Quick heuristic scan for suspicious message characteristics.

        Returns indicator counts (useful for pre-filtering).
        """
        lower = text.lower()
        return {
            "has_urls": bool(_URL_PATTERN.search(text)),
            "url_count": len(_URL_PATTERN.findall(text)),
            "has_urgency": any(
                w in lower
                for w in ["urgent", "immediately", "now", "hurry", "fast", "asap"]
            ),
            "has_money_terms": any(
                w in lower
                for w in ["money", "rupees", "lakh", "crore", "upi", "transfer",
                           "₹", "rs", "payment", "fee"]
            ),
            "has_threats": any(
                w in lower
                for w in ["blocked", "suspended", "arrested", "legal action",
                           "police", "court"]
            ),
            "excessive_caps": sum(1 for c in text if c.isupper()) > len(text) * 0.5
                              and len(text) > 20,
            "char_count": len(text),
        }
