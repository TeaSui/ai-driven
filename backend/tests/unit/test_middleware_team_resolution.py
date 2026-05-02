"""Tests for API key middleware team resolution and caching."""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.auth.middleware import (
    APIKeyMiddleware,
    _TEAM_CACHE_PREFIX,
    _TEAM_CACHE_TTL,
    _get_cached_team,
    _set_cached_team,
    hash_api_key,
)


@pytest.fixture(autouse=True)
def reset_redis_state():
    """Reset the module-level Redis state between tests."""
    import ai_driven.auth.middleware as mw

    original_client = mw._redis_client
    original_attempted = mw._redis_init_attempted
    mw._redis_client = None
    mw._redis_init_attempted = False
    yield
    mw._redis_client = original_client
    mw._redis_init_attempted = original_attempted


class TestHashApiKey:
    def test_deterministic(self):
        key = "aidrv_test_key_123"
        assert hash_api_key(key) == hash_api_key(key)

    def test_different_keys_produce_different_hashes(self):
        assert hash_api_key("key_1") != hash_api_key("key_2")

    def test_returns_hex_string(self):
        result = hash_api_key("test")
        assert isinstance(result, str)
        assert len(result) == 64  # SHA-256 hex


class TestTeamCaching:
    @pytest.mark.asyncio
    async def test_get_cached_team_returns_none_when_redis_unavailable(self):
        import ai_driven.auth.middleware as mw

        mw._redis_client = None
        mw._redis_init_attempted = True
        result = await _get_cached_team("some-hash")
        assert result is None

    @pytest.mark.asyncio
    async def test_get_cached_team_returns_data_from_redis(self):
        import ai_driven.auth.middleware as mw

        mock_redis = AsyncMock()
        team_data = {"id": "team-123", "name": "test-team", "max_concurrent_tasks": 5}
        mock_redis.get = AsyncMock(return_value=json.dumps(team_data))

        mw._redis_client = mock_redis
        mw._redis_init_attempted = True

        result = await _get_cached_team("some-hash")
        assert result == team_data
        mock_redis.get.assert_awaited_once_with(f"{_TEAM_CACHE_PREFIX}some-hash")

    @pytest.mark.asyncio
    async def test_get_cached_team_returns_none_on_cache_miss(self):
        import ai_driven.auth.middleware as mw

        mock_redis = AsyncMock()
        mock_redis.get = AsyncMock(return_value=None)

        mw._redis_client = mock_redis
        mw._redis_init_attempted = True

        result = await _get_cached_team("some-hash")
        assert result is None

    @pytest.mark.asyncio
    async def test_set_cached_team_stores_with_ttl(self):
        import ai_driven.auth.middleware as mw

        mock_redis = AsyncMock()
        mock_redis.set = AsyncMock()

        mw._redis_client = mock_redis
        mw._redis_init_attempted = True

        team_data = {"id": "team-123", "name": "test-team", "max_concurrent_tasks": 5}
        await _set_cached_team("some-hash", team_data)

        mock_redis.set.assert_awaited_once_with(
            f"{_TEAM_CACHE_PREFIX}some-hash",
            json.dumps(team_data),
            ex=_TEAM_CACHE_TTL,
        )

    @pytest.mark.asyncio
    async def test_get_cached_team_handles_redis_error_gracefully(self):
        import ai_driven.auth.middleware as mw

        mock_redis = AsyncMock()
        mock_redis.get = AsyncMock(side_effect=Exception("Redis connection lost"))

        mw._redis_client = mock_redis
        mw._redis_init_attempted = True

        result = await _get_cached_team("some-hash")
        assert result is None

    @pytest.mark.asyncio
    async def test_set_cached_team_handles_redis_error_gracefully(self):
        import ai_driven.auth.middleware as mw

        mock_redis = AsyncMock()
        mock_redis.set = AsyncMock(side_effect=Exception("Redis connection lost"))

        mw._redis_client = mock_redis
        mw._redis_init_attempted = True

        # Should not raise
        await _set_cached_team("some-hash", {"id": "team-123"})
