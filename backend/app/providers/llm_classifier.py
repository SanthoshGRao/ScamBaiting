"""
LLM Provider — Base interface and provider implementations.

Provider-agnostic design: swap providers (Groq/OpenAI/other)
by implementing the BaseLLMProvider interface.

Location: Backend (FastAPI)
Max: 300 lines | Async-first | Production-ready
"""

from __future__ import annotations

import json
import logging
import random
import re
import time
from abc import ABC, abstractmethod

from pydantic import Field
from pydantic_settings import BaseSettings

from app.models.detection_models import LLMVerdict

logger = logging.getLogger(__name__)


# --- Settings ---

class LLMSettings(BaseSettings):
    """LLM provider configuration."""
    groq_api_key: str = Field(default="", description="Groq API key")
    openai_api_key: str = Field(default="", description="OpenAI API key")
    llm_model: str = Field(default="gpt-5.5-mini", description="OpenAI model ID")
    detection_groq_model: str = Field(default="llama-3.1-8b-instant")
    detection_openai_fallback_model: str = Field(default="gpt-5.5-mini")
    response_openai_high_model: str = Field(default="gpt-5.5")
    response_openai_medium_model: str = Field(default="gpt-5.5-mini")
    llm_temperature: float = Field(default=0.78, ge=0.0, le=2.0)
    llm_top_p: float = Field(default=0.9, ge=0.0, le=1.0)
    llm_max_tokens: int = Field(default=220, ge=32, le=4096)
    llm_history_turns: int = Field(default=8, ge=2, le=30)

    model_config = {
        "env_prefix": "",
        "env_file": ".env",
        "extra": "ignore",
    }


# --- Base Interface ---

class BaseLLMProvider(ABC):
    """Abstract base for LLM providers. Implement classify() to add a provider."""

    @abstractmethod
    async def classify(
        self,
        text: str,
        language: str = "en",
        rule_hints: list[str] | None = None,
        matched_rules: list[str] | None = None,
        urls: list[str] | None = None,
    ) -> LLMVerdict:
        """Classify text and return an LLMVerdict."""
        ...

    @abstractmethod
    async def health_check(self) -> bool:
        """Verify the provider is reachable."""
        ...

    @abstractmethod
    async def generate_text(
        self,
        messages: list[dict[str, str]],
        temperature: float = 0.3,
        max_tokens: int = 256,
    ) -> str:
        """Generate free-form text response from a message list."""
        ...

    async def generate_text_for_risk(
        self,
        messages: list[dict[str, str]],
        risk_level: str,
        temperature: float = 0.85,
        max_tokens: int = 120,
    ) -> str:
        """Generate text with risk-based model routing.

        Default implementation delegates to generate_text().
        Providers with multiple models should override this.
        """
        return await self.generate_text(
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens,
        )


# --- Prompt Builder ---

_SYSTEM_PROMPT = """You are an expert scam detection system. Analyze the message and determine if it is a scam.

You MUST respond with valid JSON only. No markdown, no commentary.

JSON schema:
{
  "is_scam": bool,
  "confidence": float (0.0 to 1.0),
  "category": string (one of: financial_fraud, phishing, impersonation, job_scam, tech_support, romance_scam, unknown),
  "reasoning": string (1 sentence, technical),
  "explanation": string (1 sentence, user-friendly),
  "features": {
    "urgency_score": float (0-1),
    "financial_indicator": float (0-1),
    "link_suspicion": float (0-1),
    "threat_level": float (0-1),
    "emotional_manipulation": float (0-1)
  },
  "red_flags": ["list", "of", "exact", "suspicious", "words", "from", "user", "text"]
}"""


def build_user_prompt(
    text: str,
    language: str,
    rule_hints: list[str] | None = None,
    matched_rules: list[str] | None = None,
    urls: list[str] | None = None,
) -> str:
    """Build the user prompt with full context for LLM analysis."""
    parts = [f"Analyze this message:\n---\n{text}\n---"]
    parts.append(f"Language: {language}")

    if rule_hints:
        parts.append(f"Category hints from rules: {', '.join(rule_hints)}")
    if matched_rules:
        parts.append(f"Matched rules: {', '.join(matched_rules[:10])}")
    if urls:
        parts.append(f"URLs found: {', '.join(urls[:5])}")

    parts.append("\nRespond with JSON only.")
    return "\n".join(parts)


# --- JSON Parser (Hybrid: strict → lenient) ---

def parse_llm_response(raw: str) -> LLMVerdict | None:
    """
    Parse LLM response into LLMVerdict.

    Strategy: strict JSON → extract from code block → regex extraction.
    """
    # Attempt 1: Strict JSON parse
    cleaned = raw.strip()
    try:
        data = json.loads(cleaned)
        return _dict_to_verdict(data)
    except (json.JSONDecodeError, ValueError):
        pass

    # Attempt 2: Extract from ```json ... ``` code block
    code_block = re.search(r"```(?:json)?\s*\n?(.*?)\n?```", cleaned, re.DOTALL)
    if code_block:
        try:
            data = json.loads(code_block.group(1).strip())
            return _dict_to_verdict(data)
        except (json.JSONDecodeError, ValueError):
            pass

    # Attempt 3: Find first { ... } in response
    brace_match = re.search(r"\{.*\}", cleaned, re.DOTALL)
    if brace_match:
        try:
            data = json.loads(brace_match.group(0))
            return _dict_to_verdict(data)
        except (json.JSONDecodeError, ValueError):
            pass

    logger.warning("Failed to parse LLM response: %.100s...", raw)
    return None


def _dict_to_verdict(data: dict) -> LLMVerdict:
    """Convert parsed dict to LLMVerdict with safe defaults."""
    return LLMVerdict(
        is_scam=bool(data.get("is_scam", False)),
        confidence=_clamp(float(data.get("confidence", 0.0))),
        category=str(data.get("category", "unknown")),
        reasoning=str(data.get("reasoning", "")),
        explanation=str(data.get("explanation", "")),
        features={
            k: _clamp(float(v))
            for k, v in data.get("features", {}).items()
            if isinstance(v, (int, float))
        },
        red_flags=data.get("red_flags", []),
    )


def _clamp(value: float, lo: float = 0.0, hi: float = 1.0) -> float:
    """Clamp a float between lo and hi."""
    return max(lo, min(hi, value))


# --- OpenAI Provider ---

class OpenAIProvider(BaseLLMProvider):
    """OpenAI LLM provider using async chat completions."""

    def __init__(self, settings: LLMSettings | None = None) -> None:
        self._settings = settings or LLMSettings()
        self._client = None
        self._available = self._init_client()

    def _init_client(self) -> bool:
        """Initialize the OpenAI async client."""
        if not self._settings.openai_api_key:
            logger.error("OPENAI_API_KEY not set.")
            return False
        try:
            from openai import AsyncOpenAI

            self._client = AsyncOpenAI(api_key=self._settings.openai_api_key)
            logger.info(
                "OpenAIProvider initialized: detection=%s, response_high=%s, response_medium=%s",
                self._settings.detection_openai_fallback_model,
                self._settings.response_openai_high_model,
                self._settings.response_openai_medium_model,
            )
            return True
        except ImportError:
            logger.error("openai package not installed. Run: pip install openai")
            return False

    async def _chat_completions_create(
        self,
        model: str,
        messages: list[dict[str, str]],
        max_tokens: int,
        temperature: float | None = None,
        top_p: float | None = None,
        response_format: dict | None = None,
    ):
        kwargs = {
            "model": model,
            "messages": messages,
        }
        if temperature is not None:
            kwargs["temperature"] = temperature
        if top_p is not None:
            kwargs["top_p"] = top_p
        if response_format is not None:
            kwargs["response_format"] = response_format

        if model.startswith(("gpt-5", "o1", "o3")):
            kwargs["max_completion_tokens"] = max_tokens
            kwargs.pop("temperature", None)
            kwargs.pop("top_p", None)
        else:
            kwargs["max_tokens"] = max_tokens

        return await self._client.chat.completions.create(**kwargs)

    async def classify(
        self,
        text: str,
        language: str = "en",
        rule_hints: list[str] | None = None,
        matched_rules: list[str] | None = None,
        urls: list[str] | None = None,
    ) -> LLMVerdict:
        """Classify text using OpenAI model."""
        if not self._available or not self._client:
            logger.warning("OpenAIProvider not available, returning empty verdict")
            return LLMVerdict()

        start_time = time.monotonic()
        user_prompt = build_user_prompt(
            text, language, rule_hints, matched_rules, urls
        )

        try:
            response = await self._chat_completions_create(
                model=self._settings.detection_openai_fallback_model,
                messages=[
                    {"role": "system", "content": _SYSTEM_PROMPT},
                    {"role": "user", "content": user_prompt},
                ],
                temperature=min(self._settings.llm_temperature, 0.45),
                top_p=self._settings.llm_top_p,
                max_tokens=self._settings.llm_max_tokens,
                response_format={"type": "json_object"},
            )

            raw_content = response.choices[0].message.content or ""
            elapsed_ms = (time.monotonic() - start_time) * 1000

            verdict = parse_llm_response(raw_content)
            if verdict is None:
                logger.warning("LLM returned unparseable response, %.1fms", elapsed_ms)
                return LLMVerdict()

            logger.info(
                "LLM classified: scam=%s, conf=%.2f, cat=%s, %.1fms",
                verdict.is_scam, verdict.confidence,
                verdict.category, elapsed_ms,
            )
            return verdict

        except Exception as e:
            elapsed_ms = (time.monotonic() - start_time) * 1000
            logger.error("OpenAI API error: %s (%.1fms)", e, elapsed_ms)
            raise

    async def health_check(self) -> bool:
        """Check if OpenAI API is reachable."""
        if not self._available or not self._client:
            return False
        try:
            response = await self._chat_completions_create(
                model=self._settings.response_openai_medium_model,
                messages=[{"role": "user", "content": "ping"}],
                temperature=0.0,
                max_tokens=5,
            )
            return bool(response.choices)
        except Exception as e:
            logger.error("OpenAI health check failed: %s", e)
            return False

    async def generate_text(
        self,
        messages: list[dict[str, str]],
        temperature: float = 0.3,
        max_tokens: int = 256,
    ) -> str:
        return await self._generate(
            messages=messages,
            model=self._settings.response_openai_medium_model,
            temperature=temperature,
            max_tokens=max_tokens,
        )

    async def generate_text_for_risk(
        self,
        messages: list[dict[str, str]],
        risk_level: str,
        temperature: float = 0.85,
        max_tokens: int = 120,
    ) -> str:
        """Route to gpt-5.5 for high-risk, gpt-5.5-mini for medium/low."""
        model = (
            self._settings.response_openai_high_model
            if risk_level == "high"
            else self._settings.response_openai_medium_model
        )
        logger.debug("Risk-routed generation: risk=%s → model=%s", risk_level, model)
        return await self._generate(
            messages=messages,
            model=model,
            temperature=temperature,
            max_tokens=max_tokens,
        )

    async def _generate(
        self,
        messages: list[dict[str, str]],
        model: str,
        temperature: float,
        max_tokens: int,
    ) -> str:
        """Shared generation logic for all text generation calls."""
        if not self._available or not self._client or not messages:
            return ""

        prepared_messages = self._prepare_messages(messages)
        tuned_temperature = self._tuned_temperature(temperature)
        response = await self._chat_completions_create(
            model=model,
            messages=prepared_messages,
            temperature=tuned_temperature,
            top_p=self._settings.llm_top_p,
            max_tokens=max_tokens,
        )
        return (response.choices[0].message.content or "").strip()

    def _prepare_messages(self, messages: list[dict[str, str]]) -> list[dict[str, str]]:
        """
        Keep context compact for cost/speed:
        - preserve one system prompt
        - keep only the latest N turns
        - trim whitespace-heavy content
        """
        system_message: dict[str, str] | None = None
        non_system: list[dict[str, str]] = []
        for m in messages:
            role = (m.get("role") or "").strip()
            content = re.sub(r"\s+", " ", (m.get("content") or "")).strip()
            if not role or not content:
                continue
            item = {"role": role, "content": content}
            if role == "system" and system_message is None:
                system_message = item
            else:
                non_system.append(item)

        keep = max(2, self._settings.llm_history_turns)
        recent = non_system[-keep:]
        return ([system_message] if system_message else []) + recent

    def _tuned_temperature(self, requested_temperature: float) -> float:
        """
        Human-like randomness with bounded jitter.
        For baiting, allow higher temperatures for more creative and varied responses.
        """
        base = requested_temperature if requested_temperature > 0 else self._settings.llm_temperature
        jitter = random.uniform(-0.05, 0.05)
        return max(0.6, min(1.0, base + jitter))

    # NOTE: _tuned_max_tokens removed (§1.5 fix).
    # Token budget is now decided ONCE by the caller (ResponseService/BaitingAgent)
    # and passed through without re-randomization.


class GroqProvider(BaseLLMProvider):
    """Groq provider focused on fast/cheap classification."""

    def __init__(self, settings: LLMSettings | None = None) -> None:
        self._settings = settings or LLMSettings()
        self._client = None
        self._available = self._init_client()

    def _init_client(self) -> bool:
        if not self._settings.groq_api_key:
            logger.error("GROQ_API_KEY not set.")
            return False
        try:
            from groq import AsyncGroq
            self._client = AsyncGroq(api_key=self._settings.groq_api_key)
            logger.info("GroqProvider initialized: model=%s", self._settings.detection_groq_model)
            return True
        except ImportError:
            logger.error("groq package not installed. Run: pip install groq")
            return False

    async def classify(
        self,
        text: str,
        language: str = "en",
        rule_hints: list[str] | None = None,
        matched_rules: list[str] | None = None,
        urls: list[str] | None = None,
    ) -> LLMVerdict:
        if not self._available or not self._client:
            return LLMVerdict()

        user_prompt = build_user_prompt(text, language, rule_hints, matched_rules, urls)
        response = await self._client.chat.completions.create(
            model=self._settings.detection_groq_model,
            messages=[
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            temperature=min(self._settings.llm_temperature, 0.4),
            max_tokens=min(self._settings.llm_max_tokens, 220),
            response_format={"type": "json_object"},
        )
        raw_content = response.choices[0].message.content or ""
        return parse_llm_response(raw_content) or LLMVerdict()

    async def health_check(self) -> bool:
        if not self._available or not self._client:
            return False
        try:
            response = await self._client.chat.completions.create(
                model=self._settings.detection_groq_model,
                messages=[{"role": "user", "content": "ping"}],
                max_tokens=5,
            )
            return bool(response.choices)
        except Exception:
            return False

    async def generate_text(
        self,
        messages: list[dict[str, str]],
        temperature: float = 0.3,
        max_tokens: int = 256,
    ) -> str:
        if not self._available or not self._client:
            return ""
        response = await self._client.chat.completions.create(
            model=self._settings.detection_groq_model,
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens,
        )
        return (response.choices[0].message.content or "").strip()


# --- Factory ---

def create_llm_provider(
    provider: str = "openai",
    settings: LLMSettings | None = None,
) -> BaseLLMProvider:
    """
    Factory to create LLM provider instances.

    Args:
        provider: Provider name ("groq" or "openai").
        settings: Optional LLM settings override.

    Returns:
        Configured BaseLLMProvider instance.

    Raises:
        ValueError: If provider is not supported.
    """
    settings = settings or LLMSettings()

    match provider.lower():
        case "groq":
            return GroqProvider(settings)
        case "openai":
            return OpenAIProvider(settings)
        case _:
            raise ValueError(
                f"Unsupported LLM provider: {provider}. "
                f"Supported: groq, openai."
            )
