"""
DetectionAgent — Main orchestrator for the scam detection pipeline.

Coordinates: InputNormalizer → PII Sanitizer → LLM Classifier →
Score Merger → Verdict Builder.

Implements hybrid async architecture:
- Rule verdict (from Android) → immediate response
- LLM classification → async background task
- Fallback chain on failures

Location: Backend (FastAPI)
Max: 300 lines | Async-first | Production-ready
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Any, Callable, Awaitable

from app.config.detection_settings import DetectionSettings
from app.models.detection_models import (
    DetectionMode,
    DetectionRequest,
    DetectionResponse,
    DetectionVerdict,
    LLMVerdict,
    NormalizedInput,
    RawMessageInput,
    RuleVerdict,
)
from app.agents.detection.input_normalizer import InputNormalizer
from app.agents.detection.detection_helpers import (
    classify_risk,
    map_action,
    build_rule_reasoning,
    build_user_explanation,
)
from app.agents.detection.link_analyzer import analyze_urls
from app.services.persistence_service import PersistenceService

logger = logging.getLogger(__name__)

# Type alias for async LLM callback
LLMCallbackType = Callable[[DetectionVerdict], Awaitable[None]]


class DetectionAgent:
    """
    Main orchestrator for scam detection.

    Hybrid async architecture:
    1. Normalize input text
    2. Accept rule verdict from Android client
    3. Build immediate verdict from rules
    4. Fire LLM classification in background (if not privacy mode)
    5. Merge scores and update verdict when LLM completes

    Fallback chain:
    - Normalizer fails → use raw text
    - LLM fails → rule-only verdict
    - Merger fails → best available result
    """

    def __init__(
        self,
        settings: DetectionSettings | None = None,
        normalizer: InputNormalizer | None = None,
        llm_classifier: Any | None = None,
        fallback_classifier: Any | None = None,
        pii_sanitizer: Any | None = None,
        persistence: PersistenceService | None = None,
    ) -> None:
        self._settings = settings or DetectionSettings()
        self._normalizer = normalizer or InputNormalizer(
            default_language=self._settings.default_language
        )
        self._llm_classifier = llm_classifier
        self._fallback_classifier = fallback_classifier
        self._pii_sanitizer = pii_sanitizer
        self._persistence = persistence
        self._llm_callback: LLMCallbackType | None = None
        # §1.3 fix: track background tasks to prevent GC and enable graceful shutdown
        self._pending_tasks: set[asyncio.Task] = set()

        logger.info(
            "DetectionAgent initialized: provider=%s, privacy=%s, threshold=%.2f, fallback=%s",
            self._settings.llm_provider,
            self._settings.privacy_mode,
            self._settings.detection_threshold,
            "yes" if self._fallback_classifier else "no",
        )

    def set_llm_callback(self, callback: LLMCallbackType) -> None:
        """Register callback invoked when async LLM verdict is ready."""
        self._llm_callback = callback

    # --- Public API ---

    async def detect(self, request: DetectionRequest) -> DetectionResponse:
        """
        Main detection entry point — returns immediate rule-based verdict
        and optionally fires LLM classification in the background.
        """
        start_time = time.monotonic()

        normalized = await self._normalize_input(request)
        rule_verdict = request.rule_verdict or RuleVerdict()
        verdict = self._build_rule_verdict(normalized, rule_verdict)
        verdict.sender_score = (
            self._persistence.get_sender_risk_score(normalized.sender) if self._persistence else 0.5
        )

        should_run_llm = self._should_run_llm(request, rule_verdict)
        if should_run_llm:
            verdict.detection_mode = DetectionMode.HYBRID
            # §1.3 fix: track the background task
            task = asyncio.create_task(
                self._run_llm_background(normalized, rule_verdict, verdict)
            )
            self._pending_tasks.add(task)
            task.add_done_callback(self._pending_tasks.discard)

        elapsed_ms = (time.monotonic() - start_time) * 1000

        logger.info(
            "Detection for %s: scam=%s, conf=%.2f, risk=%s, mode=%s, %.1fms",
            verdict.message_id, verdict.is_scam, verdict.confidence,
            verdict.risk_level.value, verdict.detection_mode.value, elapsed_ms,
        )

        if self._persistence:
            self._persistence.save_detection(verdict, normalized.original_text, normalized.sender)

        return DetectionResponse(
            verdict=verdict,
            processing_time_ms=round(elapsed_ms, 2),
            llm_pending=should_run_llm,
        )

    async def detect_full(self, request: DetectionRequest) -> DetectionResponse:
        """
        Synchronous full detection — waits for LLM result.
        Use when caller can tolerate ~2s latency.
        """
        start_time = time.monotonic()

        normalized = await self._normalize_input(request)
        rule_verdict = request.rule_verdict or RuleVerdict()
        verdict = self._build_rule_verdict(normalized, rule_verdict)
        verdict.sender_score = (
            self._persistence.get_sender_risk_score(normalized.sender) if self._persistence else 0.5
        )

        if self._should_run_llm(request, rule_verdict):
            llm_verdict = await self._classify_with_llm(normalized, rule_verdict)
            if llm_verdict:
                verdict = self._merge_verdicts(verdict, llm_verdict, normalized)
                verdict.detection_mode = DetectionMode.HYBRID

        elapsed_ms = (time.monotonic() - start_time) * 1000

        logger.info(
            "Full detection for %s: scam=%s, conf=%.2f, risk=%s, %.1fms",
            verdict.message_id, verdict.is_scam, verdict.confidence,
            verdict.risk_level.value, elapsed_ms,
        )

        if self._persistence:
            self._persistence.save_detection(verdict, normalized.original_text, normalized.sender)

        return DetectionResponse(
            verdict=verdict,
            processing_time_ms=round(elapsed_ms, 2),
            llm_pending=False,
        )

    # --- Private: Normalization ---

    async def _normalize_input(self, request: DetectionRequest) -> NormalizedInput:
        """Normalize input with fallback to raw text on failure."""
        try:
            raw_input = RawMessageInput(
                text=request.text,
                source=request.source,
                sender=request.sender,
            )
            return await self._normalizer.normalize(raw_input)
        except Exception as e:
            logger.error("Normalization failed, using raw text: %s", e)
            return NormalizedInput(
                text=request.text,
                original_text=request.text,
                source=request.source,
                sender=request.sender,
                language=request.language_hint or self._settings.default_language,
                normalization_applied=["fallback_raw"],
            )

    # --- Private: Rule Verdict Processing ---

    def _build_rule_verdict(
        self, normalized: NormalizedInput, rule_verdict: RuleVerdict,
    ) -> DetectionVerdict:
        """Build a DetectionVerdict from the rule engine result."""
        confidence = rule_verdict.confidence
        risk_level = classify_risk(confidence, self._settings)
        category = (
            rule_verdict.category_hints[0] if rule_verdict.category_hints
            else "unknown"
        )

        return DetectionVerdict(
            message_id=normalized.message_id,
            is_scam=confidence >= self._settings.detection_threshold,
            confidence=confidence,
            category=category,
            risk_level=risk_level,
            reasoning=build_rule_reasoning(rule_verdict),
            explanation=build_user_explanation(rule_verdict, risk_level),
            matched_rules=rule_verdict.matched_rules,
            explainability={
                "rule_score": confidence,
                "llm_score": 0.0,
                "sender_reputation": 0.5,
                "final_score": confidence,
                "reasons": [],
            },
            recommended_action=map_action(risk_level),
            language=normalized.language,
            source=normalized.source,
            detection_mode=DetectionMode.RULE_ONLY,
        )

    # --- Private: LLM Classification ---

    def _should_run_llm(
        self, request: DetectionRequest, rule_verdict: RuleVerdict
    ) -> bool:
        """Determine whether to invoke LLM classification.

        Aggressively runs LLM for most messages to catch unknown scam patterns
        that local rules miss. Only skips for privacy mode or very short text.
        """
        if request.privacy_mode or self._settings.privacy_mode:
            return False
        if self._llm_classifier is None:
            return False
        # Always run LLM for any message over 20 chars — catch unknown scam patterns
        if len(request.text.strip()) > 20:
            return True
        # Also run if rules found anything suspicious
        return rule_verdict.is_suspicious or rule_verdict.confidence > 0.1

    async def _classify_with_llm(
        self, normalized: NormalizedInput, rule_verdict: RuleVerdict,
    ) -> LLMVerdict | None:
        """Run LLM classification with retry, fallback cascade, and dual-provider support.

        §1.6 fix: If primary classifier confidence < 0.55 and text > 20 chars,
        automatically cascade to fallback classifier (OpenAI) for re-evaluation.
        """
        if self._llm_classifier is None:
            return None

        # --- Step 1: Primary classifier (Groq) ---
        primary_result = await self._classify_single_provider(
            normalized, rule_verdict, self._llm_classifier, label="primary",
        )

        if primary_result is None:
            # Primary completely failed — try fallback directly
            if self._fallback_classifier:
                logger.info("Primary LLM failed, trying fallback classifier")
                return await self._classify_single_provider(
                    normalized, rule_verdict, self._fallback_classifier, label="fallback",
                )
            return None

        # --- Step 2: Cascade to fallback if primary is uncertain (§1.6) ---
        needs_fallback = (
            self._fallback_classifier
            and primary_result.confidence < 0.55
            and len(normalized.text) > 20
        )
        if needs_fallback:
            logger.info(
                "Primary confidence=%.2f < 0.55, cascading to fallback classifier",
                primary_result.confidence,
            )
            fallback_result = await self._classify_single_provider(
                normalized, rule_verdict, self._fallback_classifier, label="fallback",
            )
            if fallback_result and fallback_result.confidence > primary_result.confidence:
                logger.info(
                    "Fallback improved confidence: %.2f → %.2f",
                    primary_result.confidence, fallback_result.confidence,
                )
                return fallback_result

        return primary_result

    async def _classify_single_provider(
        self, normalized: NormalizedInput, rule_verdict: RuleVerdict,
        classifier: Any, label: str = "llm",
    ) -> LLMVerdict | None:
        """Run a single LLM provider with retry logic."""
        retries = self._settings.llm_max_retries
        for attempt in range(1 + retries):
            try:
                sanitized_text = await self._sanitize_text(normalized.text)
                result = await asyncio.wait_for(
                    classifier.classify(
                        text=sanitized_text,
                        language=normalized.language,
                        rule_hints=rule_verdict.category_hints,
                    ),
                    timeout=self._settings.llm_timeout_seconds,
                )
                return result
            except asyncio.TimeoutError:
                logger.warning(
                    "%s LLM timeout (attempt %d/%d) for %s",
                    label, attempt + 1, 1 + retries, normalized.message_id,
                )
            except Exception as e:
                logger.error(
                    "%s LLM error (attempt %d/%d) for %s: %s",
                    label, attempt + 1, 1 + retries, normalized.message_id, e,
                )

        logger.warning("%s LLM failed after retries for %s", label, normalized.message_id)
        return None

    async def _run_llm_background(
        self, normalized: NormalizedInput,
        rule_verdict: RuleVerdict, initial_verdict: DetectionVerdict,
    ) -> None:
        """Background task: run LLM and notify via callback."""
        try:
            llm_verdict = await self._classify_with_llm(normalized, rule_verdict)
            if llm_verdict:
                merged = self._merge_verdicts(
                    initial_verdict, llm_verdict, normalized
                )
                logger.info(
                    "LLM update %s: conf %.2f→%.2f",
                    merged.message_id, initial_verdict.confidence,
                    merged.confidence,
                )
                if self._llm_callback:
                    await self._llm_callback(merged)
        except Exception as e:
            logger.error("Background LLM failed for %s: %s",
                         normalized.message_id, e)

    async def _sanitize_text(self, text: str) -> str:
        """Sanitize PII before sending to LLM."""
        if self._pii_sanitizer:
            return await self._pii_sanitizer.sanitize(text)
        return text

    # --- Private: Score Merging ---

    def _merge_verdicts(
        self, rule_based: DetectionVerdict,
        llm: LLMVerdict, normalized: NormalizedInput,
    ) -> DetectionVerdict:
        """Merge rule-based and LLM verdicts using weighted scoring."""
        w_rule = self._settings.rule_weight
        w_llm = self._settings.llm_weight
        sender_weight = 0.15
        link_risk_score, domain_flags = analyze_urls(normalized.urls)
        base = (w_rule * rule_based.confidence) + (w_llm * llm.confidence)
        merged_conf = min(max(base + (sender_weight * rule_based.sender_score) + (0.1 * link_risk_score), 0.0), 1.0)

        risk_level = classify_risk(merged_conf, self._settings)
        category = (
            llm.category if llm.confidence > rule_based.confidence
            else rule_based.category
        )

        explainability_reasons = list(rule_based.red_flags)
        if normalized.urls:
            explainability_reasons.append("Contains suspicious link")
        if llm.features.get("urgency_score", 0.0) > 0.6:
            explainability_reasons.append("Urgent language")
        if not normalized.sender:
            explainability_reasons.append("Unknown sender")
        explainability_reasons.extend(domain_flags)

        return DetectionVerdict(
            message_id=rule_based.message_id,
            is_scam=merged_conf >= self._settings.detection_threshold,
            confidence=round(merged_conf, 4),
            category=category,
            risk_level=risk_level,
            reasoning=llm.reasoning or rule_based.reasoning,
            explanation=llm.explanation or rule_based.explanation,
            matched_rules=rule_based.matched_rules,
            features=llm.features,
            red_flags=list(dict.fromkeys(llm.red_flags + explainability_reasons)),
            sender_score=rule_based.sender_score,
            link_risk_score=link_risk_score,
            domain_flags=domain_flags,
            explainability={
                "rule_score": rule_based.confidence,
                "llm_score": llm.confidence,
                "sender_reputation": rule_based.sender_score,
                "final_score": round(merged_conf, 4),
                "reasons": list(dict.fromkeys(explainability_reasons)),
            },
            recommended_action=map_action(risk_level),
            language=normalized.language,
            source=normalized.source,
            detection_mode=DetectionMode.HYBRID,
        )
