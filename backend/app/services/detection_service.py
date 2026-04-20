from __future__ import annotations

import logging

from app.models.detection_models import LLMVerdict
from app.providers.llm_classifier import BaseLLMProvider

logger = logging.getLogger(__name__)


class DetectionService:
    """
    Hybrid detection orchestration:
    - Primary: Groq (fast/cheap)
    - Fallback: OpenAI when confidence is too low
    """

    def __init__(
        self,
        primary_provider: BaseLLMProvider,
        backup_provider: BaseLLMProvider | None = None,
        fallback_threshold: float = 0.55,
        fallback_min_chars: int = 20,
    ) -> None:
        self._primary = primary_provider
        self._backup = backup_provider
        self._fallback_threshold = fallback_threshold
        self._fallback_min_chars = fallback_min_chars

    async def classify(
        self,
        text: str,
        language: str = "en",
        rule_hints: list[str] | None = None,
        matched_rules: list[str] | None = None,
        urls: list[str] | None = None,
    ) -> LLMVerdict:
        primary = await self._primary.classify(
            text=text,
            language=language,
            rule_hints=rule_hints,
            matched_rules=matched_rules,
            urls=urls,
        )
        should_fallback = (
            primary.confidence < self._fallback_threshold
            and len(text.strip()) > self._fallback_min_chars
            and self._backup is not None
        )
        if not should_fallback:
            return primary

        logger.info(
            "Groq confidence %.2f below %.2f and len>%d; running OpenAI fallback",
            primary.confidence,
            self._fallback_threshold,
            self._fallback_min_chars,
        )
        backup = await self._backup.classify(
            text=text,
            language=language,
            rule_hints=rule_hints,
            matched_rules=matched_rules,
            urls=urls,
        )
        return backup if backup.confidence >= primary.confidence else primary

    async def health_check(self) -> bool:
        return await self._primary.health_check()
