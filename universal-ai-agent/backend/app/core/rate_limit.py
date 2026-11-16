from __future__ import annotations

import asyncio
import time
from collections import defaultdict, deque

from app.core.errors import APIError


class SlidingWindowLimiter:
    """Per-key sliding window limiter.

    Single-process only: when `REDIS_URL` is configured the deployment section of
    the docs describes replacing this with a Redis backed limiter so limits hold
    across replicas. The interface (`check`) stays the same.
    """

    def __init__(self, limit: int, window_seconds: float = 60.0) -> None:
        self.limit = max(1, limit)
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = asyncio.Lock()

    async def check(self, key: str) -> tuple[bool, int, float]:
        now = time.monotonic()
        async with self._lock:
            bucket = self._hits[key]
            cutoff = now - self.window
            while bucket and bucket[0] < cutoff:
                bucket.popleft()
            if len(bucket) >= self.limit:
                retry_after = max(0.0, self.window - (now - bucket[0]))
                return False, 0, retry_after
            bucket.append(now)
            remaining = self.limit - len(bucket)
            return True, remaining, 0.0

    async def enforce(self, key: str) -> None:
        allowed, remaining, retry_after = await self.check(key)
        if not allowed:
            raise APIError(
                429,
                "rate_limited",
                "Too many requests. Please slow down.",
                details={"retry_after_seconds": round(retry_after, 1), "remaining": remaining},
            )

    def reset(self) -> None:
        self._hits.clear()
