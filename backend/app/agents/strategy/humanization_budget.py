"""
Humanization Budget — Prevents over-layered humanization.

Caps the total number of text mutations per message to 2-3,
ensuring responses read like a real human with 1-2 natural
imperfections rather than a wall of tics.

Each humanization layer (stealth, casual mapping, mood,
persona signature, mistakes) consumes from the same budget.
"""

from __future__ import annotations

import random


class HumanizationBudget:
    """Shared mutation budget for a single message generation cycle.

    Usage:
        budget = HumanizationBudget(max_mutations=3)
        if budget.can_mutate():
            text = apply_typo(text)
            budget.spend("typo")
        if budget.can_mutate():
            text = append_hesitation(text)
            budget.spend("hesitation")
        # After 3 mutations, all subsequent can_mutate() return False.
    """

    def __init__(self, max_mutations: int = 3) -> None:
        self._max = max_mutations
        self._spent = 0
        self._mutations: list[str] = []

    def can_mutate(self) -> bool:
        """Check if the budget allows another mutation."""
        return self._spent < self._max

    def spend(self, label: str) -> None:
        """Record a mutation against the budget."""
        self._spent += 1
        self._mutations.append(label)

    def try_mutate(self, probability: float = 0.5) -> bool:
        """Roll probability AND budget check together.

        Returns True (and spends) only if both the random roll
        succeeds and budget remains. Does NOT auto-spend —
        caller must call spend() after applying the mutation.
        """
        if not self.can_mutate():
            return False
        return random.random() < probability

    @property
    def remaining(self) -> int:
        return max(0, self._max - self._spent)

    @property
    def applied_mutations(self) -> list[str]:
        return list(self._mutations)

    def __repr__(self) -> str:
        return (
            f"HumanizationBudget(spent={self._spent}/{self._max}, "
            f"mutations={self._mutations})"
        )
