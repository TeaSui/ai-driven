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


class TestConcurrentCounterDriftHealing:
    """Regression guards for the concurrent-counter drift bug.

    Symptom: Redis counter climbed past max_concurrent_tasks because the
    SQS consumer's terminal paths (success/fail/exception/cancel) didn't
    reliably fire decrement_concurrent. Every subsequent submission got
    silently 429'd. Two fixes:

      1. consumer.py now decrements in `finally`
      2. rate_limiter._check_concurrent reconciles against DB when the
         counter claims we're over limit, so drift self-heals on the
         very next submission attempt.
    """

    @pytest.mark.asyncio
    async def test_reconcile_sets_counter_from_db(
        self, monkeypatch
    ) -> None:
        """reconcile_concurrent must overwrite Redis with the DB count."""
        from unittest.mock import AsyncMock, MagicMock

        import uuid

        limiter = RateLimiter(redis_url="redis://fake")
        fake_redis = AsyncMock()
        limiter._get_redis = AsyncMock(return_value=fake_redis)  # type: ignore[method-assign]

        team_id = str(uuid.uuid4())

        # Stand in a minimal DB session that returns count=2
        fake_session = AsyncMock()
        fake_result = MagicMock()
        fake_result.scalar_one.return_value = 2
        fake_session.execute = AsyncMock(return_value=fake_result)

        fake_session_factory = MagicMock()
        fake_session_factory.return_value.__aenter__ = AsyncMock(return_value=fake_session)
        fake_session_factory.return_value.__aexit__ = AsyncMock(return_value=None)

        monkeypatch.setattr(
            "ai_driven.db.postgres.session.async_session_factory",
            fake_session_factory,
        )

        count = await limiter.reconcile_concurrent(team_id)

        assert count == 2
        fake_redis.set.assert_awaited_once()
        call_args = fake_redis.set.await_args
        assert call_args.args[1] == 2  # value
        assert f"concurrent:{team_id}" == call_args.args[0]  # key

    @pytest.mark.asyncio
    async def test_check_concurrent_heals_drift_before_rejecting(
        self, monkeypatch
    ) -> None:
        """If Redis says 5 but DB says 0, we must ALLOW the request."""
        from unittest.mock import AsyncMock, MagicMock

        import uuid

        limiter = RateLimiter(redis_url="redis://fake")
        limiter.max_concurrent_tasks = 5

        fake_redis = AsyncMock()
        # Redis claims 5 (at limit)
        fake_redis.get = AsyncMock(return_value=b"5")
        fake_redis.set = AsyncMock()
        # _check_concurrent calls self.reconcile_concurrent → _get_redis
        limiter._get_redis = AsyncMock(return_value=fake_redis)  # type: ignore[method-assign]

        team_id = str(uuid.uuid4())

        # DB says 0 in-flight — drift
        fake_session = AsyncMock()
        fake_result = MagicMock()
        fake_result.scalar_one.return_value = 0
        fake_session.execute = AsyncMock(return_value=fake_result)

        fake_session_factory = MagicMock()
        fake_session_factory.return_value.__aenter__ = AsyncMock(return_value=fake_session)
        fake_session_factory.return_value.__aexit__ = AsyncMock(return_value=None)

        monkeypatch.setattr(
            "ai_driven.db.postgres.session.async_session_factory",
            fake_session_factory,
        )

        result = await limiter._check_concurrent(fake_redis, team_id)

        # Drift was healed; request allowed
        assert result.allowed is True
        assert result.remaining == 5  # full budget after reset to 0
        # Redis was overwritten with the true count
        fake_redis.set.assert_awaited()

    @pytest.mark.asyncio
    async def test_check_concurrent_rejects_when_db_confirms_limit(
        self, monkeypatch
    ) -> None:
        """If Redis AND DB both say at-limit, rejection stands."""
        from unittest.mock import AsyncMock, MagicMock

        import uuid

        limiter = RateLimiter(redis_url="redis://fake")
        limiter.max_concurrent_tasks = 5

        fake_redis = AsyncMock()
        fake_redis.get = AsyncMock(return_value=b"5")
        fake_redis.set = AsyncMock()
        limiter._get_redis = AsyncMock(return_value=fake_redis)  # type: ignore[method-assign]

        team_id = str(uuid.uuid4())

        # DB agrees: 5 active tasks
        fake_session = AsyncMock()
        fake_result = MagicMock()
        fake_result.scalar_one.return_value = 5
        fake_session.execute = AsyncMock(return_value=fake_result)

        fake_session_factory = MagicMock()
        fake_session_factory.return_value.__aenter__ = AsyncMock(return_value=fake_session)
        fake_session_factory.return_value.__aexit__ = AsyncMock(return_value=None)

        monkeypatch.setattr(
            "ai_driven.db.postgres.session.async_session_factory",
            fake_session_factory,
        )

        result = await limiter._check_concurrent(fake_redis, team_id)

        assert result.allowed is False
        assert result.retry_after == 60
