"""Tests for RateLimiter (Task 6.5)."""

from __future__ import annotations

import pytest

from ai_driven.auth.rate_limiter import (
    DEFAULT_API_REQUESTS_PER_HOUR,
    DEFAULT_INPUT_TOKENS_PER_DAY,
    DEFAULT_MAX_CONCURRENT_TASKS,
    DEFAULT_OUTPUT_TOKENS_PER_DAY,
    RateLimitResult,
    RateLimiter,
)


class TestRateLimitResult:
    def test_allowed_result(self) -> None:
        r = RateLimitResult(allowed=True, remaining=999, limit=1000)
        assert r.allowed is True
        assert r.retry_after is None

    def test_blocked_result(self) -> None:
        r = RateLimitResult(allowed=False, remaining=0, limit=1000, retry_after=60)
        assert r.allowed is False
        assert r.retry_after == 60


class TestRateLimiterDefaults:
    def test_default_limits(self) -> None:
        limiter = RateLimiter()
        assert limiter.api_requests_per_hour == DEFAULT_API_REQUESTS_PER_HOUR
        assert limiter.input_tokens_per_day == DEFAULT_INPUT_TOKENS_PER_DAY
        assert limiter.output_tokens_per_day == DEFAULT_OUTPUT_TOKENS_PER_DAY
        assert limiter.max_concurrent_tasks == DEFAULT_MAX_CONCURRENT_TASKS

    @pytest.mark.asyncio
    async def test_no_redis_allows_requests(self) -> None:
        """Without Redis, rate limiter should allow all requests."""
        limiter = RateLimiter(redis_url="")
        result = await limiter.check_rate_limit("team-1", "api")
        assert result.allowed is True

    @pytest.mark.asyncio
    async def test_no_redis_allows_token_recording(self) -> None:
        """Without Redis, token recording should not raise."""
        limiter = RateLimiter(redis_url="")
        await limiter.record_token_usage("team-1", 100, 50)
