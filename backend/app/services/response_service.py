from __future__ import annotations

import random
import re

from app.providers.llm_classifier import BaseLLMProvider
from app.services.humanizer import Humanizer


class ResponseService:
    """
    OpenAI response orchestration with risk-based model routing.

    v2.1: Token budget decided ONCE here (§1.5 fix). Model routing
    delegated to provider.generate_text_for_risk() (§1.2 fix).
    Humanization removed — BaitingAgent owns that via HumanizationBudget.
    """

    def __init__(
        self,
        provider: BaseLLMProvider,
    ) -> None:
        self._provider = provider
        self._humanizer = Humanizer()

    async def generate_text_for_risk(
        self,
        messages: list[dict[str, str]],
        risk_level: str,
        temperature: float = 0.85,
    ) -> str:
        # 25% chance of ultra-short human reply (no LLM call needed)
        short = self._humanizer.maybe_short_reply(probability=0.25)
        if short is not None:
            return short

        style = self._humanizer.choose_style()
        compact = self._truncate_history(messages, style)

        # §1.5 fix: Token budget decided ONCE here, passed through without
        # further re-randomization downstream.
        max_tokens = 30 if random.random() < 0.4 else random.randint(100, 120)

        # §1.2 fix: Provider internally routes to gpt-5.5 (high) or gpt-5.5-mini (medium/low)
        text = await self._provider.generate_text_for_risk(
            messages=compact,
            risk_level=risk_level,
            temperature=temperature,
            max_tokens=max_tokens,
        )
        return text

    def _truncate_history(self, messages: list[dict[str, str]], style: str) -> list[dict[str, str]]:
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

        tail = non_system[-6:]
        older = non_system[:-6]
        older_summary = ""
        if older:
            snippets = [x["content"][:40] for x in older[-3:]]
            older_summary = f"Older context summary: {' | '.join(snippets)}"

        style_instruction = self._humanizer.style_prompt(style)
        if system_message:
            merged_system = {
                "role": "system",
                "content": f"{system_message['content']}\n\nStyle: {style_instruction}",
            }
        else:
            merged_system = {
                "role": "system",
                "content": f"You are texting casually like WhatsApp. {style_instruction}",
            }

        prepared = [merged_system]
        if older_summary:
            prepared.append({"role": "system", "content": older_summary})
        prepared.extend(tail)
        return prepared
