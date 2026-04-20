from __future__ import annotations

import random
import re


class Humanizer:
    """
    Adds controlled human-like variation and imperfection.
    """

    STYLES = ("short_confused", "curious", "slightly_annoyed", "normal")
    SHORT_REPLIES = ("ok", "hmm", "wait", "what?", "idk")
    STYLE_PROMPTS = {
        "short_confused": "Reply briefly, slightly confused, 2-8 words when possible.",
        "curious": "Reply casually and ask one small follow-up question when natural.",
        "slightly_annoyed": "Reply short, mildly annoyed, but still conversational.",
        "normal": "Reply naturally like a normal WhatsApp chat.",
    }

    def choose_style(self) -> str:
        return random.choice(self.STYLES)

    def maybe_short_reply(self, probability: float = 0.25) -> str | None:
        if random.random() < probability:
            return random.choice(self.SHORT_REPLIES)
        return None

    def style_prompt(self, style: str) -> str:
        return self.STYLE_PROMPTS.get(style, self.STYLE_PROMPTS["normal"])

    def inject_imperfections(self, text: str, probability: float = 0.30) -> str:
        if not text.strip() or random.random() >= probability:
            return text

        out = text
        typo_rules = (
            (r"\bbecause\b", "becuase"),
            (r"\breceive\b", "recieve"),
        )
        for pattern, repl in typo_rules:
            if random.random() < 0.5:
                out = re.sub(pattern, repl, out, flags=re.IGNORECASE)

        if random.random() < 0.5:
            out = re.sub(r"[.!]{1,3}$", "", out.strip())
        if random.random() < 0.5:
            out = out.lower()

        return re.sub(r"\s+", " ", out).strip()
