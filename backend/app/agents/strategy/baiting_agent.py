"""
Baiting Agent — Strategically wastes scammers' time using different personas.
Token-optimized: short system prompt, compact history, delimiter-based multi-bubble.
"""

from __future__ import annotations

import logging
import random
import re
import time
from typing import List

from app.models.baiting_models import BaitingRequest, BaitingResponse, ChatMessage
from app.providers.llm_classifier import BaseLLMProvider
from app.agents.strategy.stealth_optimizer import StealthOptimizer
from app.agents.strategy.strategy_agent import STRATEGY_RULES

logger = logging.getLogger(__name__)

MINIMAL_PERSONAS = {
    "busy_professional": "distracted worker; txt like ur in a hurry; lowercase; light typos ok",
    "skeptical_buyer": "doubtful; blunt short lines; not polite corporate speak",
    "half_understanding_user": "confused; repeats questions; messy wording not neat sentences",
    "lonely_conversationalist": "chatty; wanders; sounds like casual dm not an essay",
    "hopeful_opportunity_seeker": "excited but sloppy typing; fragments not full grammar",
    "curious_user": "simple words; not eloquent; sounds like someone googling on phone",
}

# Strategy-specific micro-stalls (avoid generic "kk" / "im busy rn" spam).
STALL_BY_STRATEGY: dict[str, list[str]] = {
    "CONFUSION": [
        "wait which step was that again",
        "sorry was multitasking what u need",
        "the link u sent opens weird on my phone",
    ],
    "DELAY": [
        "in something rn ping u in a bit",
        "bad signal rn msg isnt going thru properly",
        "give me like 15 im not near my other phone",
    ],
    "FAKE_COMPLIANCE": [
        "trying it now screen froze for a sec",
        "otp box didnt pop up yet",
        "says invalid when i paste can u resend",
    ],
    "AGGRESSION": [
        "why u rushing me lol",
        "that sounds sketch tbh",
        "u got any proof ur legit",
    ],
    "DERAILMENT": [
        "btw random q u eat yet",
        "sorry sidetracked long day",
        "my kid walked in one sec",
    ],
    "ESCALATION": [
        "i need a real receipt not screenshots",
        "who do u work for exactly",
        "send me ur id i dont do blind transfers",
    ],
}

STRATEGY_DONT: dict[str, str] = {
    "CONFUSION": "Do not summarize their instructions correctly in one clean sentence.",
    "DELAY": "Do not invent a new story that contradicts FACTS; keep the same excuse thread.",
    "FAKE_COMPLIANCE": "Never say it's fully done; always partial progress + error/symptom.",
    "AGGRESSION": "No long rants; one punchy doubt per segment max.",
    "DERAILMENT": "Don't fully comply; tangent then one half-answer or question.",
    "ESCALATION": "Don't accept vague reassurance; one concrete verification ask.",
}

# Visible tactic shape (very short—adds little token weight).
STRATEGY_SHAPE: dict[str, str] = {
    "CONFUSION": "wrong detail + question (+ optional typo/self-correction)",
    "DELAY": "blocker + when u can + light apology",
    "FAKE_COMPLIANCE": "trying + specific error/symptom + ask for resend/clarify",
    "AGGRESSION": "skeptic line + challenge",
    "DERAILMENT": "random life bit + feigned return to topic",
    "ESCALATION": "one verification demand + consequence if vague",
}

# Lightweight session memory: last stall text (avoid immediate repeats).
_last_stall_by_session: dict[str, str] = {}
_LAZY_SINGLE = re.compile(
    r"^(ok+|kk+|k+\.?|lol+|hmm+|ya+|nah+|sure+|fine+|busy|no+|yes+)\W*$", re.I
)

# One-line templates (split on |||) when the model returns empty/low-substance text.
_SUBSTANCE_FALLBACKS: dict[str, list[str]] = {
    "CONFUSION": [
        "wait the amount doesnt match what u said earlier|||is it phonepe or gpay",
        "sorry brain fried rn|||u mean the first link or the other one",
    ],
    "DELAY": [
        "im stuck in something till like 7|||i can try after that ok",
        "signal keeps dropping at my place|||msg me again in 20 if i go quiet",
    ],
    "FAKE_COMPLIANCE": [
        "i pasted it but it says invalid|||can u send the code again slower",
        "otp screen just spins|||does it expire fast or something",
    ],
    "AGGRESSION": [
        "why u typing so aggressive lol|||u got proof ur not random",
        "this feels off tbh|||who is ur company exactly",
    ],
    "DERAILMENT": [
        "havent eaten all day lol|||anyway what was the step again",
        "my neighbor is loud af rn|||one sec what u need from me",
    ],
    "ESCALATION": [
        "send a proper receipt with letterhead|||not just screenshots",
        "i want a supervisor name i can google|||then we continue",
    ],
}

_PAID_ASSISTANT = re.compile(
    r"\b(i paid|already paid|sent (the )?money|transferred|payment done|"
    r"sent (the )?screenshot|sent screenshot|did the transfer|money is sent)\b",
    re.I,
)
_NEGATION_REPLY = re.compile(
    r"\b(didn'?t|did not|never|not yet|haven'?t|ignored|nah i didn)\b",
    re.I,
)

# Words to keep visually distinct when lowercasing for SMS vibe (after lower, match these).
_KEEP_WORD_LOWER = frozenset(
    {
        "otp",
        "upi",
        "pin",
        "sms",
        "atm",
        "kyc",
        "neft",
        "rtgs",
        "imps",
        "gst",
        "ifsc",
        "url",
        "qr",
    }
)

# Formal / assistant-ish phrases to strip or replace (whole-segment cleanup).
_FORMAL_PHRASES: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bhowever\b", re.I), "but"),
    (re.compile(r"\btherefore\b", re.I), "so"),
    (re.compile(r"\badditionally\b", re.I), "also"),
    (re.compile(r"\bfurthermore\b", re.I), "also"),
    (re.compile(r"\bI would like to\b", re.I), "i wanna"),
    (re.compile(r"\bI am writing to\b", re.I), "im msging to"),
    (re.compile(r"\bCould you please\b", re.I), "can u"),
    (re.compile(r"\bThank you for\b", re.I), "thx for"),
    (re.compile(r"\bAt your earliest convenience\b", re.I), "when u can"),
)


class BaitingAgent:
    """Generates realistic human-like scam-bait replies."""

    STALL_PROBABILITY = 0.06

    def __init__(self, llm_provider: BaseLLMProvider):
        self._llm = llm_provider
        self._stealth = StealthOptimizer()

    @staticmethod
    def _temperature_for_strategy(strategy: str) -> float:
        if strategy == "DELAY":
            return 0.82
        if strategy in ("AGGRESSION", "ESCALATION"):
            return 0.76
        return 0.92

    @staticmethod
    def _truncate(s: str, max_len: int) -> str:
        s = s.strip()
        if len(s) <= max_len:
            return s
        return s[: max_len - 1].rstrip() + "…"

    @staticmethod
    def _incoming_user_chars(history: List[ChatMessage]) -> int:
        for m in reversed(history):
            if m.role == "user":
                return len(m.content)
        return 0

    def _pick_stall(self, session_id: str, strategy: str) -> str:
        key = strategy.upper()
        pool = STALL_BY_STRATEGY.get(key)
        if not pool:
            pool = STALL_BY_STRATEGY["CONFUSION"]
        last = _last_stall_by_session.get(session_id)
        choices = [s for s in pool if s != last] or pool
        msg = random.choice(choices)
        _last_stall_by_session[session_id] = msg
        return msg

    def _strategy_lines(self, strategy: str) -> tuple[str, str, str]:
        s = strategy.upper() if strategy else "CONFUSION"
        do = STRATEGY_RULES.get(s, STRATEGY_RULES["CONFUSION"])
        dont = STRATEGY_DONT.get(s, STRATEGY_DONT["CONFUSION"])
        shape = STRATEGY_SHAPE.get(s, STRATEGY_SHAPE["CONFUSION"])
        return do, dont, shape

    @staticmethod
    def _opener_snippet(text: str) -> str:
        words = text.strip().split()
        if not words:
            return ""
        frag = " ".join(words[:4]).lower()
        return frag[:36]

    @staticmethod
    def _conversation_heat_line(history: List[ChatMessage]) -> str:
        n_user = sum(1 for m in history if m.role == "user")
        if n_user <= 2:
            return "Beat: early—they probe; sound a bit naive/distracted."
        if n_user <= 7:
            return "Beat: mid—they push; add one more friction layer, stay polite."
        return "Beat: late—they sound rushed; shorter msgs, don't give clean wins."

    def _build_commitment_facts(self, history: List[ChatMessage]) -> list[str]:
        assistant_blob = " ".join(
            m.content.lower() for m in history if m.role == "assistant"
        )
        facts: list[str] = []
        facts.append(self._conversation_heat_line(history))

        if _PAID_ASSISTANT.search(assistant_blob):
            facts.append(
                "You already implied payment/transfer/screenshot sent; "
                "do not deny it—clarify, stall, or blame tech instead."
            )
        else:
            facts.append("You have NOT confirmed paying or sending money.")

        last_assistant = next((m.content for m in reversed(history) if m.role == "assistant"), "")
        if last_assistant:
            op = self._opener_snippet(last_assistant)
            facts.append(
                f"Your last msg: {self._truncate(last_assistant, 72)}"
                + (f" — vary opener; don't echo '{op}' again." if op else "")
            )

        last_user = next((m.content for m in reversed(history) if m.role == "user"), "")
        if last_user:
            facts.append(f"They just said: {self._truncate(last_user, 95)}")

        return facts[:5]

    def _trim_history(self, history: List[ChatMessage]) -> List[ChatMessage]:
        """Last ~5 turns, truncated—enough continuity without long context."""
        window = history[-5:] if len(history) > 5 else history
        trimmed: List[ChatMessage] = []
        for m in window:
            trimmed.append(
                ChatMessage(role=m.role, content=self._truncate(m.content, 200))
            )
        return trimmed

    def _parse_llm_segments(self, raw: str) -> List[str]:
        text = raw.replace("\n", " ").strip()
        if not text:
            return ["ok"]
        if "|||" in text:
            parts = [p.strip() for p in text.split("|||") if p.strip()]
        else:
            parts = [text]
        parts = parts[:3]
        return parts if parts else [text]

    def _enforce_substance(self, parts: list[str], strategy: str) -> list[str]:
        """Replace lazy one-word replies with strategy-shaped fallbacks (no extra LLM)."""
        s = (strategy or "CONFUSION").upper()
        cleaned = [p.strip() for p in parts if p.strip()]
        if not cleaned:
            return self._substance_fallback(s)
        total_words = sum(len(seg.split()) for seg in cleaned)
        if total_words < 5:
            return self._substance_fallback(s)
        if any(_LAZY_SINGLE.match(seg) for seg in cleaned):
            return self._substance_fallback(s)
        return cleaned[:3]

    def _substance_fallback(self, strategy: str) -> list[str]:
        raw = random.choice(_SUBSTANCE_FALLBACKS.get(strategy, _SUBSTANCE_FALLBACKS["CONFUSION"]))
        return self._parse_llm_segments(raw)

    def _maybe_repair_contradiction(self, parts: list[str], had_payment_claim: bool) -> list[str]:
        if not parts or not had_payment_claim:
            return parts
        joined = " ".join(parts).lower()
        if not _NEGATION_REPLY.search(joined):
            return parts
        repaired = [
            "wait ignore that i meant its not going through on my end",
            "bank app acting weird",
        ]
        return repaired[: max(1, min(len(parts), 2))]

    @staticmethod
    def _sms_casualize_segment(seg: str) -> str:
        """Post-process LLM text so it reads like a real phone text, not a polished assistant."""
        t = seg.strip()
        if not t:
            return t

        # Preserve URLs — extract them before casualization, reinsert after
        url_pattern = re.compile(r'https?://\S+', re.I)
        urls_found = url_pattern.findall(t)
        url_placeholders = {}
        for i, url in enumerate(urls_found):
            placeholder = f"__URL{i}__"
            url_placeholders[placeholder] = url
            t = t.replace(url, placeholder, 1)

        for pat, rep in _FORMAL_PHRASES:
            t = pat.sub(rep, t)
        t = t.replace("—", " ").replace("–", "-")
        t = re.sub(r"\s*;\s*", ", ", t)
        t = re.sub(r":\s+(?=[A-Za-z])", ", ", t)  # "note: do this" -> comma (not times like 3:15)
        t = re.sub(r",\s*,+", ", ", t)
        t = re.sub(r"\s{2,}", " ", t).strip()
        if (t.startswith('"') and t.endswith('"')) or (t.startswith("'") and t.endswith("'")):
            t = t[1:-1].strip()

        def _lower_word_chunk(chunk: str) -> str:
            # Skip URL placeholders
            if chunk.startswith("__URL") and chunk.endswith("__"):
                return chunk
            m = re.match(r"^([^\w]*)(.+?)([^\w]*)$", chunk, flags=re.DOTALL)
            if not m:
                return chunk.lower()
            lead, core, trail = m.group(1), m.group(2), m.group(3)
            if not core:
                return chunk
            core_alnum = re.sub(r"[^\w]", "", core)
            if any(ch.isdigit() for ch in core) or core_alnum.lower() in _KEEP_WORD_LOWER:
                return f"{lead}{core}{trail}"
            return f"{lead}{core.lower()}{trail}"

        words = re.split(r"(\s+)", t)
        t = "".join(_lower_word_chunk(w) if not w.isspace() else w for w in words)

        t = re.sub(r"\.{3,}", "…", t)
        t = re.sub(r"[!?]{3,}", "?!", t)
        if len(t) < 100 and t.endswith(".") and random.random() < 0.62:
            t = t[:-1].rstrip()
        if random.random() < 0.28:
            t = re.sub(r"\bI am\b", "im", t, flags=re.I, count=1)
        if random.random() < 0.22:
            t = re.sub(r"\bPlease\b", "pls", t, flags=re.I, count=1)
        if random.random() < 0.18:
            t = re.sub(r"\bThanks\b", "thx", t, flags=re.I, count=1)

        # Reinsert original URLs
        for placeholder, url in url_placeholders.items():
            t = t.replace(placeholder, url)

        return t.strip()

    def _sms_casualize_parts(self, parts: list[str]) -> list[str]:
        out = [self._sms_casualize_segment(p) for p in parts if p.strip()]
        return out if out else ["wait what"]

    def _compute_part_delays(self, num_parts: int, incoming_len: int) -> list[int]:
        """Seconds of 'human pause' before each bubble (client adds typing on top)."""
        delays: list[int] = []
        for i in range(num_parts):
            if i == 0:
                read_bonus = min(4, incoming_len // 80)
                sec = random.randint(3, 6) + read_bonus
            else:
                # Second thoughts / typing rhythm—longer than first pause tail.
                sec = random.randint(3, 8)
            delays.append(max(2, min(12, sec)))
        return delays

    async def generate_reply(
        self, request: BaitingRequest, tracking_url: str | None = None,
    ) -> BaitingResponse:
        start_time = time.monotonic()
        session_id = request.session_id or "default_session"
        strategy = (request.current_strategy or "CONFUSION").upper()

        if random.random() < self.STALL_PROBABILITY:
            msg = self._pick_stall(session_id, strategy)
            delays = self._compute_part_delays(1, self._incoming_user_chars(request.history))
            logger.info("Baiting reply [STALL]: session=%s, strategy=%s, msg='%s'", session_id, strategy, msg)
            return BaitingResponse(
                reply_text=msg,
                reply_parts=[msg],
                response_delay_seconds=delays[0],
                part_delay_seconds=delays,
                processing_time_ms=(time.monotonic() - start_time) * 1000,
                strategy_used="stall",
                persona_used=request.persona,
                goal=request.goal,
                stealth_typing_speed_ms=self._stealth.randomize_typing_speed(),
                suspicion_detected=False,
            )

        persona_id = request.persona.lower().replace(" ", "_")
        persona_desc = MINIMAL_PERSONAS.get(
            persona_id, MINIMAL_PERSONAS["busy_professional"]
        )
        strat_do, strat_dont, strat_shape = self._strategy_lines(strategy)
        facts_lines = self._build_commitment_facts(request.history)
        facts_block = "\n".join(f"- {line}" for line in facts_lines)

        sms_rules = (
            "SMS/txt style (critical): write like a real person thumb-typing.\n"
            "- mostly lowercase; skip capitals except rare emphasis\n"
            "- sparse punctuation: avoid semicolons, em dashes, colons, and multiple commas\n"
            "- no polished essay tone: no 'However/Therefore/Furthermore/I would like'\n"
            "- short fragments ok; run-on ok; occasional missing apostrophe (dont, im, ur)\n"
            "- do not use perfect textbook grammar or long balanced sentences\n"
            "- at most one ? or ! per segment unless mirroring their panic\n"
        )
        system_prompt = (
            "You are replying on a phone chat app to a scammer. Sound human, not like ChatGPT.\n"
            f"{sms_rules}"
            f"Persona: {persona_id} ({persona_desc}).\n"
            "Voice: hesitate (wait/hmm), distracted, a bit vague—never formal or brochure-like.\n"
            "Banned alone as a whole segment: ok, kk, lol, busy rn, im busy, nah, sure (add substance).\n"
            "Each segment must name something concrete from their last message OR a specific tech symptom.\n\n"
            "FACTS (obey):\n"
            f"{facts_block}\n\n"
            f"{strategy}: {strat_do}\n"
            f"NOT: {strat_dont}\n"
            f"Shape: {strat_shape}\n\n"
            "Output ONLY: msg1|||msg2|||msg3 (1–3 segments; max ~16 words/segment; one idea each).\n"
            "Prefer 2 segments when you correct yourself or send a follow-up thought.\n"
            "No quotes; no newlines inside segments."
        )

        # --- Tracking link injection ---
        if tracking_url:
            system_prompt += (
                f"\n\nIMPORTANT — INCLUDE THIS LINK: {tracking_url}\n"
                "Weave this link into your reply naturally. Examples:\n"
                "- 'i pasted the receipt here check {url}'\n"
                "- 'the screenshot is here {url} see if it matches'\n"
                "- 'ok check this {url} thats the proof u asked for'\n"
                "Make it fit the conversation. Sound casual, not promotional."
            )

        messages: list[dict] = [{"role": "system", "content": system_prompt}]
        for msg in self._trim_history(request.history):
            messages.append({"role": msg.role, "content": msg.content})

        had_payment_claim = any(
            "already implied payment" in f for f in facts_lines
        )

        try:
            raw = await self._llm.generate_text_for_risk(
                messages=messages,
                risk_level="high",
                temperature=self._temperature_for_strategy(strategy),
                max_tokens=120 if tracking_url else 84,
            ) or "wait the amount u said doesnt match|||which app is this for again"

            raw = raw.replace("\n", " ").strip()
            if not raw:
                logger.warning("LLM returned empty text for session=%s, using strategy fallback", session_id)
                raw = "wait the amount u said doesnt match|||which app is this for again"
            reply_parts = self._parse_llm_segments(raw)
            reply_parts = self._enforce_substance(reply_parts, strategy)
            reply_parts = self._maybe_repair_contradiction(reply_parts, had_payment_claim)
            reply_parts = self._sms_casualize_parts(reply_parts)
            reply_text = " ".join(reply_parts) if len(reply_parts) > 1 else reply_parts[0]
            logger.info("Baiting reply [LLM]: session=%s, strategy=%s, parts=%d, text='%s'",
                        session_id, strategy, len(reply_parts), reply_text[:100])

            incoming_len = self._incoming_user_chars(request.history)
            part_delay_seconds = self._compute_part_delays(len(reply_parts), incoming_len)
            response_delay_seconds = part_delay_seconds[0] if part_delay_seconds else 3

            return BaitingResponse(
                reply_text=reply_text,
                reply_parts=reply_parts,
                response_delay_seconds=response_delay_seconds,
                part_delay_seconds=part_delay_seconds,
                processing_time_ms=(time.monotonic() - start_time) * 1000,
                strategy_used=strategy,
                persona_used=persona_id,
                goal=request.goal,
                stealth_typing_speed_ms=self._stealth.randomize_typing_speed(),
                suspicion_detected=False,
            )

        except Exception as e:
            logger.error("Baiting agent error for session=%s: %s", session_id, e)
            fb = "wait what|||my app glitched"
            parts = self._parse_llm_segments(fb)
            delays = self._compute_part_delays(len(parts), 0)
            return BaitingResponse(
                reply_text="wait what my app glitched",
                reply_parts=parts,
                response_delay_seconds=delays[0],
                part_delay_seconds=delays,
                processing_time_ms=(time.monotonic() - start_time) * 1000,
                strategy_used=strategy,
                persona_used=request.persona,
                goal=request.goal,
                stealth_typing_speed_ms=100,
            )
