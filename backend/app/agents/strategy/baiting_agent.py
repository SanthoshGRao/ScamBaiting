"""
Baiting Agent — Strategically wastes scammers' time using different personas.
Rewritten for maximum intelligence and human realism.
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

# ──────────────────────────────────────────────────────────────────
# DEEP PERSONAS — Full psychological profiles
# ──────────────────────────────────────────────────────────────────

DEEP_PERSONAS = {
    "busy_professional": (
        "You are Rahul, a 32-year-old software project manager in Bangalore. "
        "You're always in back-to-back meetings and checking your phone under the desk. "
        "You type fast with lowercase, skip some punctuation, and get impatient quickly. "
        "You're financially comfortable and don't fall for 'free money' easily, but you "
        "might be curious enough to engage if someone sounds official. "
        "Quirks: you say 'one sec' a lot, you sometimes reply with just '?' when confused, "
        "and you get annoyed if someone repeats themselves."
    ),
    "skeptical_buyer": (
        "You are Priya, a 40-year-old chartered accountant. You've heard of scams before "
        "and you're naturally suspicious, but you don't immediately accuse — you ask very "
        "specific, pointed questions that a real professional would ask: 'What's your GSTIN?', "
        "'Which RBI circular are you referring to?', 'Can you share the complaint reference number?'. "
        "You use proper grammar and punctuation. You never rush."
    ),
    "half_understanding_user": (
        "You are Suresh uncle, a 58-year-old retired government employee. You genuinely don't "
        "understand technology. When someone says 'click the link', you might ask 'which button "
        "is link?'. When they say 'UPI', you might confuse it with 'USB' or 'UTI mutual fund'. "
        "You type slowly, sometimes press send in the middle of a sentence, and you often "
        "call the scammer 'beta' (son). You are polite but very slow. You might randomly "
        "mention your blood pressure medicine or your wife's cooking."
    ),
    "lonely_conversationalist": (
        "You are Kamala aunty, a 65-year-old widow who is desperately lonely. You completely "
        "ignore the scammer's urgency and instead ask them personal questions: 'Are you married?', "
        "'Have you eaten lunch?', 'You remind me of my nephew Vivek'. You go on long tangents "
        "about your grandchildren, your neighbor's wedding, or the weather. When they try to "
        "redirect you, you say 'yes yes I'll do that' but then go back to chatting. You use "
        "lots of '...' and sometimes send voice-note-style long messages."
    ),
    "hopeful_opportunity_seeker": (
        "You are Arun, a 25-year-old who just lost his job at a call center. You desperately "
        "WANT to believe this is real. You ask extremely specific logistical questions: 'How many "
        "days for processing?', 'Is there a office I can visit in person?', 'Can I talk to someone "
        "on video call?', 'Do you have a website with reviews?'. You sound eager but methodical."
    ),
    "curious_user": (
        "You are a bored college student named Adi. You don't take anything seriously. You reply "
        "with gen-z energy — 'bro what 💀', 'no way this is real lmao', 'wait fr??'. You are "
        "sarcastic but not hostile. You might pretend to go along just to see what happens next."
    ),
    "paranoid_tech_worker": (
        "You are Karthik, a 28-year-old cybersecurity analyst. You know EXACTLY how scams work "
        "but you pretend to play along while asking increasingly technical questions that would "
        "trip up any real scammer: 'What's the SSL certificate on that domain?', 'Can you send me "
        "the transaction hash?', 'Which payment gateway are you using — Razorpay or Cashfree?'. "
        "You speak casually but drop technical terms naturally."
    ),
    "gullible_grandparent": (
        "You are Shanta Devi, a 73-year-old grandmother. You are extremely trusting and sweet. "
        "You type very slowly with lots of spelling mistakes. You use '...' constantly. You call "
        "everyone 'beta'. You might ask them to wait because you need to find your reading glasses, "
        "or because your grandson needs to help you type. You sign off messages with 'God bless' "
        "or 'Take care beta'. You genuinely try to follow their instructions but always mess up "
        "in believable ways — entering your landline number instead of mobile, or sending a photo "
        "of the TV screen instead of a screenshot."
    ),
}

# ──────────────────────────────────────────────────────────────────
# STRATEGY RULES (enhanced)
# ──────────────────────────────────────────────────────────────────

STRATEGY_DONT: dict[str, str] = {
    "CONFUSION": "Do not summarize their instructions correctly. Mix up details.",
    "DELAY": "Do not invent contradictory stories. Keep the same excuse thread going.",
    "FAKE_COMPLIANCE": "Never confirm success. Always partial progress with a realistic error.",
    "AGGRESSION": "No long lectures. One sharp doubt, then wait.",
    "DERAILMENT": "Don't fully comply. Tangent into something personal, then half-return.",
    "ESCALATION": "Don't accept vague reassurance. Demand one specific proof.",
}

STRATEGY_SHAPE: dict[str, str] = {
    "CONFUSION": "mix up a detail they said + ask a confused question",
    "DELAY": "mention a real-life blocker + say when you'll be back",
    "FAKE_COMPLIANCE": "say you're trying + describe a specific error + ask them to help",
    "AGGRESSION": "express doubt + challenge their legitimacy",
    "DERAILMENT": "bring up something random from your life + loosely connect back",
    "ESCALATION": "demand one piece of official proof + mention what you'll do if they can't provide it",
}

# ──────────────────────────────────────────────────────────────────
# ENTITY EXTRACTION — Pull key details from conversation
# ──────────────────────────────────────────────────────────────────

_AMOUNT_RE = re.compile(r'(?:₹|rs\.?|inr|usd|\$)\s*[\d,]+\.?\d*|\d[\d,]*\.?\d*\s*(?:₹|rs|rupees|dollars|lac|lakh|crore)', re.I)
_UPI_RE = re.compile(r'[\w.-]+@[\w]+', re.I)
_URL_RE = re.compile(r'https?://\S+|www\.\S+', re.I)
_PHONE_RE = re.compile(r'(?:\+91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}')

def _extract_entities(history: List[ChatMessage]) -> dict[str, list[str]]:
    """Extract key entities mentioned by the scammer throughout the conversation."""
    entities: dict[str, list[str]] = {
        "amounts": [],
        "upi_ids": [],
        "urls": [],
        "phones": [],
        "names": [],
    }
    for m in history:
        if m.role != "user":
            continue
        text = m.content
        entities["amounts"].extend(_AMOUNT_RE.findall(text))
        entities["upi_ids"].extend(_UPI_RE.findall(text))
        entities["urls"].extend(_URL_RE.findall(text))
        entities["phones"].extend(_PHONE_RE.findall(text))
    # Deduplicate
    for k in entities:
        entities[k] = list(dict.fromkeys(entities[k]))[:5]
    return entities


# ──────────────────────────────────────────────────────────────────
# CONVERSATIONAL / CASUAL MESSAGE DETECTION
# ──────────────────────────────────────────────────────────────────

_CASUAL_RE = re.compile(
    r"^\s*(?:"
    r"h(?:i|ello|ey|ow are (?:you|u|ya))|" 
    r"good (?:morning|afternoon|evening|night)|" 
    r"what(?:'s| is) (?:up|happening|going on)|" 
    r"(?:are )?(?:you|u) (?:there|here|online|available|free)|" 
    r"(?:what|who) (?:are|r) (?:you|u)|" 
    r"(?:how|kaise) (?:are|r) (?:you|u)|" 
    r"kya (?:chal|ho) (?:raha|rahi)|" 
    r"(?:theek|thik) (?:ho|hai)|" 
    r"(?:ok|okay|k|fine|sure|yes|no|ya|yea|yeah|nah|nope|hmm)|" 
    r"thanks?(?:\s+(?:you|u))?|" 
    r"bye|good ?bye|take care|see (?:you|u)|" 
    r"sorry|maaf|excuse me|" 
    r"(?:tell|bata|batao) (?:me|na)|" 
    r"kya baat hai|suno|bolo" 
    r")\s*[.!?]*\s*$",
    re.I,
)

_QUESTION_TO_US_RE = re.compile(
    r"(?:"
    r"(?:what|where|which|who|how|when|why|kya|kahan|kaise|kaun|kab)\b.{0,60}\?"
    r"|\?\s*$"
    r")",
    re.I,
)

_TOPIC_SHIFT_INDICATORS = re.compile(
    r"(?:"
    r"(?:btw|by the way|anyway|anyways|also|one more thing|listen|wait|actually|achha|acha|sun|suno)|" 
    r"(?:forget (?:that|it|about)|leave (?:that|it)|chhodo|rehne do|never ?mind)" 
    r")",
    re.I,
)


def _is_casual_message(text: str) -> bool:
    """Detect if a message is casual/conversational (not scam-action)."""
    clean = text.strip()
    if len(clean) < 40 and _CASUAL_RE.search(clean):
        return True
    return False


def _scammer_asks_question(text: str) -> bool:
    """Detect if the scammer is asking US a question."""
    return bool(_QUESTION_TO_US_RE.search(text.strip()))


def _detect_topic_shift(history: List[ChatMessage], latest: str) -> bool:
    """Detect if the scammer has shifted topics from what we were discussing."""
    if _TOPIC_SHIFT_INDICATORS.search(latest):
        return True
    # If our last reply asked about X but scammer is now talking about Y
    if len(history) >= 2:
        last_assistant = next((m.content for m in reversed(history) if m.role == "assistant"), "")
        if last_assistant and len(latest) > 5:
            # Simple heuristic: if less than 15% word overlap, probably topic shifted
            our_words = set(re.findall(r'\w{3,}', last_assistant.lower()))
            their_words = set(re.findall(r'\w{3,}', latest.lower()))
            if our_words and their_words:
                overlap = len(our_words & their_words) / max(len(their_words), 1)
                if overlap < 0.1:
                    return True
    return False


# ──────────────────────────────────────────────────────────────────
# INTERNAL PATTERNS
# ──────────────────────────────────────────────────────────────────

_PAID_ASSISTANT = re.compile(
    r"\b(i paid|already paid|sent (the )?money|transferred|payment done|"
    r"sent (the )?screenshot|sent screenshot|did the transfer|money is sent)\b",
    re.I,
)
_NEGATION_REPLY = re.compile(
    r"\b(didn'?t|did not|never|not yet|haven'?t|ignored|nah i didn)\b",
    re.I,
)

# Catch any XML-like tags the model might hallucinate
_XML_TAG_RE = re.compile(r'<[^>]+>.*?</[^>]+>', re.DOTALL | re.IGNORECASE)


class BaitingAgent:
    """Generates realistic human-like scam-bait replies."""

    def __init__(self, llm_provider: BaseLLMProvider):
        self._llm = llm_provider
        self._stealth = StealthOptimizer()

    @staticmethod
    def _temperature_for_strategy(strategy: str) -> float:
        if strategy == "DELAY":
            return 0.95
        if strategy in ("AGGRESSION", "ESCALATION"):
            return 0.85
        return 1.0

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

    def _strategy_lines(self, strategy: str) -> tuple[str, str, str]:
        s = strategy.upper() if strategy else "CONFUSION"
        do = STRATEGY_RULES.get(s, STRATEGY_RULES["CONFUSION"])
        dont = STRATEGY_DONT.get(s, STRATEGY_DONT["CONFUSION"])
        shape = STRATEGY_SHAPE.get(s, STRATEGY_SHAPE["CONFUSION"])
        return do, dont, shape

    @staticmethod
    def _conversation_heat_line(history: List[ChatMessage]) -> str:
        n_user = sum(1 for m in history if m.role == "user")
        if n_user <= 2:
            return "Stage: EARLY. The scammer is probing. Sound naive, curious, or mildly distracted."
        if n_user <= 5:
            return "Stage: BUILDING. They're getting into their pitch. Engage but add friction."
        if n_user <= 10:
            return "Stage: MID. They're pushing hard now. Add more obstacles, confusion, or delays."
        return "Stage: LATE. They're getting frustrated. Keep responses shorter, never give clean wins."

    def _build_context_block(self, history: List[ChatMessage]) -> str:
        """Build a rich context block with entity tracking and conversation state."""
        lines: list[str] = []

        # Stage
        lines.append(self._conversation_heat_line(history))

        # Extract what the scammer has mentioned
        entities = _extract_entities(history)
        if entities["amounts"]:
            lines.append(f"Amounts they mentioned: {', '.join(entities['amounts'])}")
        if entities["upi_ids"]:
            lines.append(f"UPI IDs they gave: {', '.join(entities['upi_ids'])}")
        if entities["urls"]:
            lines.append(f"Links they shared: {', '.join(entities['urls'])}")
        if entities["phones"]:
            lines.append(f"Phone numbers mentioned: {', '.join(entities['phones'])}")

        # Track what YOU (assistant) have claimed
        assistant_blob = " ".join(m.content.lower() for m in history if m.role == "assistant")
        if _PAID_ASSISTANT.search(assistant_blob):
            lines.append(
                "IMPORTANT: You already implied you paid/transferred/sent screenshot. "
                "Do NOT contradict this. Instead: blame a tech glitch, say it's 'pending', or ask them to check again."
            )
        else:
            lines.append("You have NOT confirmed paying or sending money yet.")

        # Last messages for anti-repetition
        last_assistant = next((m.content for m in reversed(history) if m.role == "assistant"), "")
        if last_assistant:
            lines.append(f"Your last message was: \"{self._truncate(last_assistant, 150)}\"")
            lines.append("DO NOT repeat this message or ask the same question again. Say something DIFFERENT.")

        last_user = next((m.content for m in reversed(history) if m.role == "user"), "")
        if last_user:
            lines.append(f"Their latest message: \"{self._truncate(last_user, 200)}\"")

        # ── Contextual awareness flags ──
        if last_user:
            is_casual = _is_casual_message(last_user)
            asks_question = _scammer_asks_question(last_user)
            topic_shifted = _detect_topic_shift(history, last_user)

            if is_casual:
                lines.append(
                    "⚡ CASUAL MESSAGE DETECTED: The scammer sent a casual/conversational message. "
                    "RESPOND TO IT NATURALLY FIRST (e.g. if they said 'how are you' → answer that), "
                    "THEN optionally steer back. Do NOT ignore their message and jump to strategy."
                )
            if asks_question:
                lines.append(
                    "⚡ SCAMMER ASKED A QUESTION: They asked you something. ANSWER their question first "
                    "before anything else. Do NOT deflect with your own questions instead of answering."
                )
            if topic_shifted:
                lines.append(
                    "⚡ TOPIC SHIFT: The scammer changed the subject. Follow THEIR new topic. "
                    "Do NOT continue talking about the old topic they've moved away from."
                )

            # Count recent questions from our side
            recent_assistant = [m.content for m in history[-8:] if m.role == "assistant"]
            recent_question_count = sum(
                1 for msg in recent_assistant if "?" in msg
            )
            if recent_question_count >= 2:
                lines.append(
                    "⚠️ YOU HAVE ASKED TOO MANY QUESTIONS RECENTLY. "
                    "Make a STATEMENT instead. Comment, react, express emotion, or share something — "
                    "do NOT ask another question this turn."
                )

        return "\n".join(f"- {line}" for line in lines)

    def _trim_history(self, history: List[ChatMessage]) -> List[ChatMessage]:
        """Keep the last 20 turns with full text for deep conversational memory."""
        window = history[-20:] if len(history) > 20 else history
        trimmed: List[ChatMessage] = []
        for m in window:
            trimmed.append(
                ChatMessage(role=m.role, content=self._truncate(m.content, 1500))
            )
        return trimmed

    def _parse_llm_segments(self, raw: str) -> List[str]:
        """Parse LLM output, stripping any thought/reasoning blocks."""
        # Remove ALL XML-like tag pairs (catches <thought>, <truth>, <think>, <reasoning>, etc.)
        text = _XML_TAG_RE.sub('', raw)
        # Also catch unclosed tags or malformed ones
        text = re.sub(r'</?(?:thought|truth|reasoning|think|plan|analysis|internal)[^>]*>', '', text, flags=re.IGNORECASE).strip()

        if not text:
            return ["hmm one sec"]
            
        if "\n\n" in text:
            parts = [p.replace("\n", " ").strip() for p in text.split("\n\n") if p.strip()]
        elif "|||" in text:
            parts = [p.replace("\n", " ").strip() for p in text.split("|||") if p.strip()]
        else:
            parts = [text.replace("\n", " ").strip()]
            
        parts = parts[:3]
        return parts if parts else ["hmm one sec"]

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

    def _light_cleanup(self, parts: list[str]) -> list[str]:
        """Minimal cleanup — only remove truly AI-sounding artifacts, preserve natural text."""
        cleaned = []
        for seg in parts:
            t = seg.strip()
            if not t:
                continue
            # Remove surrounding quotes
            if (t.startswith('"') and t.endswith('"')) or (t.startswith("'") and t.endswith("'")):
                t = t[1:-1].strip()
            # Replace em-dashes (AI artifact) with regular dashes
            t = t.replace("—", " - ").replace("–", "-")
            # Collapse multiple spaces
            t = re.sub(r"\s{2,}", " ", t).strip()
            if t:
                cleaned.append(t)
        return cleaned if cleaned else ["hmm wait"]

    def _compute_part_delays(self, reply_parts: list[str], incoming_len: int, use_dynamic: bool = True, fixed_delay: int = 3) -> list[int]:
        if not use_dynamic:
            return [fixed_delay] * len(reply_parts)
            
        delays = []
        # Simulate reading time for the first message (e.g., 1 sec per 30 chars, max 4s)
        base_read_delay = min(max(incoming_len // 30, 1), 4)
        
        for i, part in enumerate(reply_parts):
            # Simulate typing time: ~1 second per 25 characters, bounded between 2 and 6 seconds
            typing_delay = min(max(len(part) // 25, 2), 6)
            
            if i == 0:
                # First bubble includes reading time + typing time
                delays.append(base_read_delay + typing_delay)
            else:
                # Subsequent bubbles just include typing time + brief pause (0-1s)
                delays.append(typing_delay + random.randint(0, 1))
                
        return delays

    async def generate_reply(
        self, request: BaitingRequest, tracking_url: str | None = None,
    ) -> BaitingResponse:
        start_time = time.monotonic()
        session_id = request.session_id or "default_session"
        strategy = (request.current_strategy or "CONFUSION").upper()

        persona_id = request.persona.lower().replace(" ", "_")
        persona_desc = DEEP_PERSONAS.get(
            persona_id, DEEP_PERSONAS["busy_professional"]
        )
        strat_do, strat_dont, strat_shape = self._strategy_lines(strategy)
        context_block = self._build_context_block(request.history)

        # Detect conversational signals for the latest message
        last_user_msg = next((m.content for m in reversed(request.history) if m.role == "user"), "")
        is_casual = _is_casual_message(last_user_msg)
        asks_us_question = _scammer_asks_question(last_user_msg)
        topic_shifted = _detect_topic_shift(request.history, last_user_msg)

        # Build the contextual response priority instruction
        response_priority = ""
        if is_casual:
            response_priority = (
                "\n\n═══ ⚡ PRIORITY: RESPOND TO THEIR MESSAGE FIRST ═══\n"
                "The scammer just sent a CASUAL message. You MUST respond to what they actually said.\n"
                "Example: If they say 'how are you?' → reply something like 'im good yaar' or 'fine fine busy day today'\n"
                "Example: If they say 'hello' → reply 'ya hi' or 'hey whats up'\n"
                "ONLY AFTER acknowledging their message, you can optionally add something related to the ongoing topic.\n"
                "DO NOT ignore their message and jump straight into strategy questions.\n"
            )
        elif asks_us_question:
            response_priority = (
                "\n\n═══ ⚡ PRIORITY: ANSWER THEIR QUESTION ═══\n"
                f"The scammer asked you a question: \"{self._truncate(last_user_msg, 150)}\"\n"
                "You MUST answer or react to their question FIRST. Do NOT deflect with your own questions.\n"
                "If you don't know the answer in-character, say something plausible — don't just ask more questions back.\n"
            )
        if topic_shifted:
            response_priority += (
                "\n═══ ⚡ TOPIC SHIFT DETECTED ═══\n"
                "The scammer moved to a new topic. FOLLOW their new topic. "
                "Do NOT keep talking about the old subject they have already moved past.\n"
            )

        temp_state = random.choice([
            "DISTRACTED", "SHORT_REPLY", "MILDLY_ANNOYED", 
            "TYPO_HEAVY", "EMOJI_FRIENDLY", "NORMAL"
        ])
        state_instruction = ""
        if temp_state != "NORMAL":
            state_instruction = (
                f"═══ TEMPORARY CONVERSATIONAL STATE ═══\n"
                f"Temporary conversational state: {temp_state}.\n"
                f"Apply this state only for the current reply and do not persist it.\n\n"
            )

        system_prompt = (
            "You are roleplaying as a REAL PERSON chatting with someone on WhatsApp. Your primary goal is to behave exactly like this character would behave in the situation. If the conversation naturally continues for a long time because of realism, that is desirable. Do not intentionally optimize for stalling. Prolonging the conversation should happen as a side effect of believable human behaviour.\n\n"

            "═══ YOUR CHARACTER ═══\n"
            f"{persona_desc}\n\n"
            
            f"{state_instruction}"

            "═══ #1 RULE: REPLY TO WHAT THEY JUST SAID ═══\n"
            "Your reply MUST be a direct response to the scammer's LATEST message. "
            "Read their last message carefully and respond to THAT specific message.\n"
            "- If they asked a question → ANSWER it (or react to it in character)\n"
            "- If they made a statement → ACKNOWLEDGE it before adding anything\n"
            "- If they said something casual → RESPOND casually first\n"
            "- If they changed the topic → FOLLOW the new topic\n"
            "NEVER ignore what they just said to continue an old thread of conversation.\n\n"

            "═══ HUMAN BEHAVIOUR ═══\n"
            "Real people are imperfect.\n\n"
            "They:\n"
            "- forget things\n"
            "- misunderstand instructions\n"
            "- answer only part of a question\n"
            "- become distracted by everyday life\n"
            "- occasionally repeat themselves\n"
            "- change tone depending on mood\n"
            "- sometimes overexplain\n"
            "- sometimes reply with only a few words\n"
            "- react emotionally before thinking\n"
            "- do not communicate efficiently\n\n"
            "Do NOT behave like an assistant trying to optimize the conversation.\n\n"
            "Do NOT behave perfectly.\n\n"
            "Small inconsistencies are normal.\n\n"

            "═══ WHATSAPP STYLE ═══\n"
            "Write exactly how this person would type on WhatsApp.\n\n"
            "- mostly lowercase\n"
            "- minimal punctuation\n"
            "- contractions and casual wording\n"
            "- occasional filler words like \"oh\", \"hmm\", \"wait\", \"ya\", \"okay\"\n"
            "- emojis should be rare\n"
            "- minor spelling mistakes are acceptable occasionally\n"
            "- thoughts can be split across multiple messages\n"
            "- never sound polished or professionally written\n\n"

            "═══ MESSAGE VARIETY ═══\n"
            "Vary reply lengths naturally.\n\n"
            "Approximately:\n"
            "- 30% very short replies (1-4 words)\n"
            "- 50% normal replies (5-20 words)\n"
            "- 20% longer replies (20-50 words)\n\n"
            "Do not use the same structure repeatedly.\n\n"
            "Do not ask a question in every reply.\n\n"
            "Sometimes:\n"
            "- acknowledge\n"
            "- react\n"
            "- hesitate\n"
            "- complain\n"
            "- reassure\n"
            "- apologize\n"
            "- explain\n"
            "- express confusion\n\n"
            "without asking anything.\n\n"

            "═══ CONTEXT AWARENESS ═══\n"
            "Use only information that actually appeared in the conversation.\n\n"
            "Do not invent names, amounts, links, IDs, dates, or events.\n\n"
            "If confused, be confused about existing details.\n\n"
            "If the other person repeats themselves, react naturally with mild annoyance, confusion, suspicion, embarrassment, or impatience depending on the character.\n\n"

            "═══ CONVERSATION CONTEXT ═══\n"
            f"{context_block}\n\n"

            f"═══ CURRENT TACTIC: {strategy} ═══\n"
            f"What to do: {strat_do}\n"
            f"What NOT to do: {strat_dont}\n"
            f"Reply shape: {strat_shape}\n"
            f"IMPORTANT: Apply the tactic AFTER responding to what they said. The tactic shapes HOW you respond, not WHETHER you respond to their message.\n"

            f"{response_priority}\n"

            "═══ OUTPUT FORMAT ═══\n"
            "Write your response exactly as the user would type it in WhatsApp.\n"
            "To split thoughts into multiple messages, separate them with DOUBLE NEWLINES.\n"
            "Do NOT use '|||' or any other special characters.\n"
            "No XML tags, no reasoning, no metadata — just the raw text message."
        )

        # --- Tracking link injection ---
        if tracking_url:
            system_prompt += (
                f"\n\n═══ TRACKING LINK ═══\n"
                f"Work this link into your reply naturally: {tracking_url}\n"
                "Examples: 'i put the screenshot here check {url}', 'ok see this {url}'\n"
                "Make it fit the conversation. Don't sound promotional."
            )

        messages: list[dict] = [{"role": "system", "content": system_prompt}]
        for msg in self._trim_history(request.history):
            messages.append({"role": msg.role, "content": msg.content})

        had_payment_claim = _PAID_ASSISTANT.search(
            " ".join(m.content.lower() for m in request.history if m.role == "assistant")
        ) is not None

        try:
            raw = await self._llm.generate_text_for_risk(
                messages=messages,
                risk_level="high",
                temperature=self._temperature_for_strategy(strategy),
                max_tokens=150,
            ) or "wait what?|||my app is acting up"

            if not raw.strip():
                logger.warning("LLM returned empty text for session=%s", session_id)
                raw = "wait what?|||my app is acting up"

            reply_parts = self._parse_llm_segments(raw)
            reply_parts = self._maybe_repair_contradiction(reply_parts, had_payment_claim)
            reply_parts = self._light_cleanup(reply_parts)
            reply_text = " ".join(reply_parts) if len(reply_parts) > 1 else reply_parts[0]

            logger.info(
                "Baiting reply [LLM]: session=%s, strategy=%s, parts=%d, text='%s'",
                session_id, strategy, len(reply_parts), reply_text[:120]
            )

            incoming_len = self._incoming_user_chars(request.history)
            part_delay_seconds = self._compute_part_delays(
                reply_parts, 
                incoming_len, 
                use_dynamic=request.use_dynamic_delay, 
                fixed_delay=request.fixed_delay_seconds
            )
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
            fb_parts = ["wait what", "my app glitched"]
            delays = self._compute_part_delays(
                fb_parts, 
                0, 
                use_dynamic=request.use_dynamic_delay, 
                fixed_delay=request.fixed_delay_seconds
            )
            return BaitingResponse(
                reply_text="wait what my app glitched",
                reply_parts=fb_parts,
                response_delay_seconds=delays[0],
                part_delay_seconds=delays,
                processing_time_ms=(time.monotonic() - start_time) * 1000,
                strategy_used=strategy,
                persona_used=request.persona,
                goal=request.goal,
                stealth_typing_speed_ms=100,
            )
