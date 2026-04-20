"""
Stealth Optimizer — Injects human-like imperfections into AI-generated text.

Prevents scammers from detecting AI patterns by introducing:
- Randomized typing speed
- Typos and self-corrections
- Variable response lengths
- Human distraction injections
"""

from __future__ import annotations

import random


class StealthOptimizer:
    """Injects human-like imperfections into AI-generated text."""

    # Common typos that real humans make
    TYPO_MAP: dict[str, list[str]] = {
        "the": ["teh", "hte"],
        "receive": ["recieve"],
        "because": ["becuase", "bc"],
        "transfer": ["tansfer", "tranfer"],
        "account": ["acount", "acccount"],
        "payment": ["payemnt", "paymnet"],
        "screenshot": ["screesnhot", "screensht"],
        "message": ["mesage", "msg"],
        "please": ["plz", "pls"],
        "okay": ["okai", "ok"],
        "money": ["monye", "mony"],
        "sending": ["sendign", "sendin"],
        "waiting": ["waitign", "waitin"],
        "already": ["alredy", "alreayd"],
        "problem": ["probem", "problm"],
    }

    # Self-correction patterns
    CORRECTIONS = [
        "wait sorry i meant {correct}",
        "*{correct}",
        "oops i mean {correct}",
        "{correct}*",
        "lol sorry {correct}",
    ]

    # Human distraction interjections
    DISTRACTIONS = [
        "hold on someones calling me",
        "sry phone was on 2% had to charge",
        "ok back sorry my mom was yelling at me",
        "wait i just got another call brb",
        "omg my dog just knocked over the water bowl one sec",
        "sorry my internet just died for a min",
        "brb doorbell just rang",
        "had to take a call from my boss sry",
        "sorry fell asleep for a sec lol",
        "my phone just restarted wtf",
    ]

    def humanize(self, text: str, aggression_level: float = 0.3) -> str:
        """
        Apply random human imperfections to AI-generated text.

        Args:
            text: Clean AI-generated text.
            aggression_level: 0.0-1.0, how aggressively to inject typos.

        Returns:
            Humanized text, possibly split into multiple messages with \\n.
        """
        result = text

        # 30% chance: inject a typo
        if random.random() < aggression_level:
            result = self._inject_typo(result)

        # 15% chance: add a self-correction as a follow-up
        if random.random() < 0.15:
            typo_word, correct_word = self._pick_correctable_typo(result)
            if typo_word:
                correction = random.choice(self.CORRECTIONS).format(
                    correct=correct_word
                )
                result = f"{result}\n{correction}"

        # 10% chance: randomly drop end punctuation
        if random.random() < 0.10 and result.endswith((".", "!", "?")):
            result = result[:-1]

        # 20% chance: lowercase the entire message (casual texting style)
        if random.random() < 0.20:
            result = result.lower()

        # 10% chance: abbreviate common words
        if random.random() < 0.10:
            result = self._abbreviate(result)

        return result

    def should_inject_distraction(self, turn_number: int) -> str | None:
        """
        Occasionally inject a human distraction instead of a real reply.

        Returns distraction text if one should be injected, None otherwise.
        """
        if turn_number < 3:
            return None
        if turn_number % random.randint(4, 8) == 0 and random.random() < 0.25:
            return random.choice(self.DISTRACTIONS)
        return None

    def randomize_typing_speed(self, base_ms_per_char: int = 55) -> int:
        """
        Return a randomized typing speed instead of fixed ms/char.

        Real humans type at 30-90ms per character with bursts and pauses.
        """
        variation = random.uniform(0.6, 1.4)
        speed = int(base_ms_per_char * variation)

        # 20% chance of a "thinking pause"
        if random.random() < 0.20:
            speed += random.randint(200, 500)

        return max(25, min(120, speed))

    def vary_response_length(self, target_length: int) -> int:
        """
        Vary the max_tokens parameter unpredictably.

        Humans don't write consistent-length messages.
        """
        roll = random.random()
        if roll < 0.20:      # 20% ultra-short
            return max(3, target_length // 3)
        elif roll < 0.40:    # 20% slightly short
            return int(target_length * 0.7)
        elif roll < 0.85:    # 45% normal
            return target_length
        else:                # 15% slightly longer
            return int(target_length * 1.3)

    def _inject_typo(self, text: str) -> str:
        """Inject a realistic typo into the text."""
        words = text.split()
        for i, word in enumerate(words):
            clean = word.lower().strip(".,!?;:")
            if clean in self.TYPO_MAP:
                replacement = random.choice(self.TYPO_MAP[clean])
                # Preserve original casing/punctuation
                if word[0].isupper():
                    replacement = replacement.capitalize()
                trailing = ""
                for ch in reversed(word):
                    if ch in ".,!?;:":
                        trailing = ch + trailing
                    else:
                        break
                words[i] = replacement + trailing
                break  # Only one typo per message
        return " ".join(words)

    def _pick_correctable_typo(
        self, text: str
    ) -> tuple[str | None, str | None]:
        """Find a word that was typo'd and return (typo, correct) pair."""
        words = text.lower().split()
        for typo_list in self.TYPO_MAP.values():
            for typo in typo_list:
                if typo in words:
                    # Find the correct word
                    for correct, typos in self.TYPO_MAP.items():
                        if typo in typos:
                            return typo, correct
        return None, None

    @staticmethod
    def _abbreviate(text: str) -> str:
        """Abbreviate common words casually."""
        replacements = {
            "you": "u",
            "your": "ur",
            "are": "r",
            "okay": "ok",
            "tomorrow": "tmrw",
            "tonight": "2nite",
            "because": "cuz",
        }
        words = text.split()
        for i, word in enumerate(words):
            clean = word.lower().strip(".,!?;:")
            if clean in replacements and random.random() < 0.5:
                words[i] = replacements[clean]
                break
        return " ".join(words)
