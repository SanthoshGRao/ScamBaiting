from __future__ import annotations

import time
from collections import defaultdict, deque

from fastapi import HTTPException


class PerUserRateLimiter:
    def __init__(self, max_requests: int = 120, window_seconds: int = 60) -> None:
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def check(self, user_key: str) -> None:
        now = time.monotonic()
        window_start = now - self.window_seconds
        hits = self._hits[user_key]
        while hits and hits[0] < window_start:
            hits.popleft()
        if len(hits) >= self.max_requests:
            raise HTTPException(status_code=429, detail="Per-user rate limit exceeded")
        hits.append(now)


rate_limiter = PerUserRateLimiter()
