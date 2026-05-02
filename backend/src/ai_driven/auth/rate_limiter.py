"""Token-based rate limiting and quota enforcement per team using Redis."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any

logger = logging.getLogger(__name__)


@dataclass
class RateLimitResult:
    """Result of a rate limit check."""

    allowed: bool
    remaining: int
    limit: int
    retry_after: int | None = None  # seconds until next allowed request


# Default rate limits
DEFAULT_API_REQUESTS_PER_HOUR = 1000
DEFAULT_INPUT_TOKENS_PER_DAY = 1_000_000
DEFAULT_OUTPUT_TOKENS_PER_DAY = 200_000
DEFAULT_MAX_CONCURRENT_TASKS = 5

# Per-resource rate limit overrides: {resource: (max_requests, window_seconds)}
_RESOURCE_LIMITS: dict[str, tuple[int, int]] = {
    "chat_send": (10, 60),  # 10 requests per minute
}

# Redis key prefixes
_RATE_PREFIX = "ratelimit:"
_TOKENS_PREFIX = "tokenusage:"
_CONCURRENT_PREFIX = "concurrent:"


class RateLimiter:
    """Token-based rate limiting per team using Redis sliding window."""

    def __init__(self, redis_url: str = "") -> None:
        self._redis_url = redis_url
        self._redis: Any = None
        self._redis_init_attempted = False

        # Configurable limits (can be overridden per team via settings)
        self.api_requests_per_hour = DEFAULT_API_REQUESTS_PER_HOUR
        self.input_tokens_per_day = DEFAULT_INPUT_TOKENS_PER_DAY
        self.output_tokens_per_day = DEFAULT_OUTPUT_TOKENS_PER_DAY
        self.max_concurrent_tasks = DEFAULT_MAX_CONCURRENT_TASKS

    async def _get_redis(self) -> Any:
        """Lazy-initialize Redis client."""
        if self._redis_init_attempted:
            return self._redis
        self._redis_init_attempted = True
        try:
            import redis.asyncio as aioredis

            url = self._redis_url
            if not url:
                from ai_driven.config import get_settings
                url = get_settings().redis_url
            self._redis = aioredis.from_url(
                url,
                decode_responses=True,
                socket_connect_timeout=2,
            )
            await self._redis.ping()
            return self._redis
        except Exception:
            logger.warning("Redis unavailable for rate limiting — allowing all requests")
            self._redis = None
            return None

    async def check_rate_limit(
        self, team_id: str, resource: str = "api"
    ) -> RateLimitResult:
        """Check if the team is within rate limits using a sliding window.

        Uses Redis sorted sets with timestamps as scores. Entries older than
        the window are pruned on each check.

        Args:
            team_id: The team identifier.
            resource: The resource type to rate-limit (e.g., "api", "concurrent").

        Returns:
            RateLimitResult with allowed, remaining, limit, and retry_after fields.
        """
        redis = await self._get_redis()
        if redis is None:
            # No Redis available — allow all requests (graceful degradation)
            return RateLimitResult(
                allowed=True,
                remaining=self.api_requests_per_hour,
                limit=self.api_requests_per_hour,
            )

        if resource == "concurrent":
            return await self._check_concurrent(redis, team_id)

        return await self._check_sliding_window(redis, team_id, resource)

    async def check_concurrent(self, team_id: str) -> RateLimitResult:
        """Public convenience method for concurrent task limit check.

        Returns:
            RateLimitResult with allowed=True if under limit, False otherwise.
        """
        return await self.check_rate_limit(team_id, "concurrent")

    async def _check_sliding_window(
        self, redis: Any, team_id: str, resource: str
    ) -> RateLimitResult:
        """Sliding window rate limit using Redis sorted sets (ZRANGEBYSCORE)."""
        key = f"{_RATE_PREFIX}{resource}:{team_id}"
        now = time.time()

        # Use per-resource overrides if defined, otherwise fall back to defaults
        if resource in _RESOURCE_LIMITS:
            limit, window_seconds = _RESOURCE_LIMITS[resource]
        else:
            window_seconds = 3600  # 1 hour
            limit = self.api_requests_per_hour

        window_start = now - window_seconds

        try:
            pipe = redis.pipeline()
            # Remove entries outside the window
            pipe.zremrangebyscore(key, 0, window_start)
            # Count entries within the window
            pipe.zcard(key)
            # Add the current request (score=timestamp, member=timestamp with uniqueness)
            member = f"{now}:{id(object())}"
            pipe.zadd(key, {member: now})
            # Set expiry on the key to auto-cleanup
            pipe.expire(key, window_seconds + 60)
            results = await pipe.execute()

            current_count = results[1]  # zcard result before adding new entry

            if current_count >= limit:
                # Over limit — find the oldest entry to calculate retry_after
                oldest = await redis.zrange(key, 0, 0, withscores=True)
                retry_after = int(oldest[0][1] + window_seconds - now) + 1 if oldest else 1
                # Remove the entry we just added since the request is denied
                await redis.zrem(key, member)
                return RateLimitResult(
                    allowed=False,
                    remaining=0,
                    limit=limit,
                    retry_after=max(retry_after, 1),
                )

            remaining = max(0, limit - current_count - 1)
            return RateLimitResult(
                allowed=True,
                remaining=remaining,
                limit=limit,
            )
        except Exception:
            logger.warning("Rate limit check failed — allowing request", exc_info=True)
            return RateLimitResult(
                allowed=True,
                remaining=limit,
                limit=limit,
            )

    async def _check_concurrent(
        self, redis: Any, team_id: str
    ) -> RateLimitResult:
        """Check concurrent task limit for a team.

        If the Redis counter is at-or-above the limit we FIRST reconcile
        against the authoritative DB count before rejecting. This auto-
        heals drift from crashed workers / missed decrements so legitimate
        submissions aren't indefinitely 429'd by a stale counter. The
        reconcile is only triggered at the boundary (not every check),
        so the hot path stays O(1).
        """
        key = f"{_CONCURRENT_PREFIX}{team_id}"
        limit = self.max_concurrent_tasks

        try:
            current = await redis.get(key)
            current_count = int(current) if current else 0

            if current_count >= limit:
                # Drift guard: recompute from DB before rejecting. If the
                # true count is still at/over limit the rejection stands;
                # otherwise the counter is overwritten and we re-read.
                true_count = await self.reconcile_concurrent(team_id)
                if true_count >= limit:
                    return RateLimitResult(
                        allowed=False,
                        remaining=0,
                        limit=limit,
                        retry_after=60,
                    )
                current_count = true_count

            return RateLimitResult(
                allowed=True,
                remaining=limit - current_count,
                limit=limit,
            )
        except Exception:
            logger.warning("Concurrent limit check failed — allowing request", exc_info=True)
            return RateLimitResult(
                allowed=True,
                remaining=limit,
                limit=limit,
            )

    async def increment_concurrent(self, team_id: str) -> None:
        """Increment concurrent task count for a team."""
        redis = await self._get_redis()
        if redis is None:
            return
        try:
            key = f"{_CONCURRENT_PREFIX}{team_id}"
            await redis.incr(key)
            # Set a safety TTL so counts don't stick forever on crash
            await redis.expire(key, 86400)
        except Exception:
            logger.warning("Failed to increment concurrent count", exc_info=True)

    async def decrement_concurrent(self, team_id: str) -> None:
        """Decrement concurrent task count for a team."""
        redis = await self._get_redis()
        if redis is None:
            return
        try:
            key = f"{_CONCURRENT_PREFIX}{team_id}"
            result = await redis.decr(key)
            # Don't let it go negative
            if result is not None and int(result) < 0:
                await redis.set(key, 0)
        except Exception:
            logger.warning("Failed to decrement concurrent count", exc_info=True)

    async def reconcile_concurrent(self, team_id: str) -> int:
        """Reset the Redis concurrent-task counter to the true count of
        in-flight tasks for a team.

        Redis counters drift when a worker crashes, is SIGKILL'd, or when
        cancel paths don't fire the decrement. Without reconciliation, the
        counter climbs until every submission gets 429'd. This method
        queries Postgres for the authoritative set of active tasks
        (statuses: queued, running, planning, coding, testing,
        awaiting_input) and overwrites the Redis counter with that count.

        Safe to call any time — it uses a SET not a subtraction, so
        concurrent increments from new submissions are tolerated (they
        just race to land just-after the reconcile and are correct either
        way). Returns the reconciled count.
        """
        from ai_driven.db.postgres.models import Task
        from ai_driven.db.postgres.session import async_session_factory
        from sqlalchemy import func, select
        import uuid as _uuid

        redis = await self._get_redis()
        if redis is None:
            return 0

        ACTIVE_STATUSES = (
            "queued", "running", "planning", "coding", "testing",
            "awaiting_input",
        )
        try:
            team_uuid = _uuid.UUID(team_id)
        except (ValueError, TypeError):
            return 0

        try:
            async with async_session_factory() as session:
                res = await session.execute(
                    select(func.count(Task.id)).where(
                        Task.team_id == team_uuid,
                        Task.status.in_(ACTIVE_STATUSES),
                    )
                )
                true_count = int(res.scalar_one() or 0)
        except Exception:
            logger.warning("reconcile_concurrent_db_lookup_failed", exc_info=True)
            return 0

        try:
            key = f"{_CONCURRENT_PREFIX}{team_id}"
            await redis.set(key, true_count, ex=86400)
            logger.info(
                "reconciled_concurrent_counter",
                extra={"team_id": team_id, "count": true_count},
            )
        except Exception:
            logger.warning("reconcile_concurrent_redis_set_failed", exc_info=True)
            return 0

        return true_count

    async def record_token_usage(
        self, team_id: str, input_tokens: int, output_tokens: int
    ) -> None:
        """Track Claude token usage for quota enforcement.

        Uses Redis hashes keyed by day to accumulate token counts.
        """
        if input_tokens < 0 or output_tokens < 0:
            raise ValueError("Token counts must be non-negative")

        redis = await self._get_redis()
        if redis is None:
            return

        try:
            import datetime

            today = datetime.date.today().isoformat()
            key = f"{_TOKENS_PREFIX}{team_id}:{today}"

            pipe = redis.pipeline()
            pipe.hincrby(key, "input_tokens", input_tokens)
            pipe.hincrby(key, "output_tokens", output_tokens)
            # Expire at end of day + buffer (48 hours for safety)
            pipe.expire(key, 172800)
            await pipe.execute()
        except Exception:
            logger.warning("Failed to record token usage", exc_info=True)

    async def check_token_quota(
        self, team_id: str
    ) -> RateLimitResult:
        """Check if the team is within daily token quota.

        Returns the most restrictive result between input and output tokens.
        """
        redis = await self._get_redis()
        if redis is None:
            return RateLimitResult(
                allowed=True,
                remaining=self.input_tokens_per_day,
                limit=self.input_tokens_per_day,
            )

        try:
            import datetime

            today = datetime.date.today().isoformat()
            key = f"{_TOKENS_PREFIX}{team_id}:{today}"

            data = await redis.hgetall(key)
            input_used = int(data.get("input_tokens", 0))
            output_used = int(data.get("output_tokens", 0))

            input_remaining = max(0, self.input_tokens_per_day - input_used)
            output_remaining = max(0, self.output_tokens_per_day - output_used)

            # Block if either quota is exceeded
            if input_used >= self.input_tokens_per_day:
                return RateLimitResult(
                    allowed=False,
                    remaining=0,
                    limit=self.input_tokens_per_day,
                    retry_after=_seconds_until_midnight(),
                )
            if output_used >= self.output_tokens_per_day:
                return RateLimitResult(
                    allowed=False,
                    remaining=0,
                    limit=self.output_tokens_per_day,
                    retry_after=_seconds_until_midnight(),
                )

            # Return the most constrained quota
            if input_remaining < output_remaining:
                return RateLimitResult(
                    allowed=True,
                    remaining=input_remaining,
                    limit=self.input_tokens_per_day,
                )
            return RateLimitResult(
                allowed=True,
                remaining=output_remaining,
                limit=self.output_tokens_per_day,
            )
        except Exception:
            logger.warning("Token quota check failed — allowing request", exc_info=True)
            return RateLimitResult(
                allowed=True,
                remaining=self.input_tokens_per_day,
                limit=self.input_tokens_per_day,
            )

    async def close(self) -> None:
        """Close the Redis connection."""
        if self._redis is not None:
            await self._redis.close()
            self._redis = None
            self._redis_init_attempted = False


def _seconds_until_midnight() -> int:
    """Calculate seconds remaining until midnight UTC."""
    import datetime

    now = datetime.datetime.now(datetime.timezone.utc)
    midnight = (now + datetime.timedelta(days=1)).replace(
        hour=0, minute=0, second=0, microsecond=0
    )
    return int((midnight - now).total_seconds())
