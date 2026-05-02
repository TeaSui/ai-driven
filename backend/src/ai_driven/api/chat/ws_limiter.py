"""WebSocket connection limiter — tracks connections per team in Redis."""

from __future__ import annotations

import logging

import redis.asyncio as aioredis

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

MAX_PER_TEAM = 20
KEY_PREFIX = "ws_connections:"
KEY_TTL = 3600  # 1h safety net for stale entries


class WebSocketLimiter:
    """Track WebSocket connections per team in Redis.

    Uses INCR/DECR for atomic counting with a TTL safety net.
    If Redis is unavailable, fails open (allows connection).
    """

    async def _get_redis(self) -> aioredis.Redis | None:
        try:
            settings = get_settings()
            return aioredis.from_url(settings.redis_url, decode_responses=True)
        except Exception:
            return None

    async def acquire(self, team_id: str) -> bool:
        """Increment connection count. Returns False if limit exceeded."""
        redis = await self._get_redis()
        if redis is None:
            return True  # Fail open

        key = f"{KEY_PREFIX}{team_id}"
        try:
            count = await redis.incr(key)
            # Set TTL on first connection (safety net)
            if count == 1:
                await redis.expire(key, KEY_TTL)

            if count > MAX_PER_TEAM:
                await redis.decr(key)  # Roll back
                logger.warning("WS connection limit exceeded for team %s (%d/%d)", team_id, count, MAX_PER_TEAM)
                return False
            return True
        except Exception:
            logger.debug("WS limiter acquire failed, allowing connection", exc_info=True)
            return True

    async def release(self, team_id: str) -> None:
        """Decrement connection count."""
        redis = await self._get_redis()
        if redis is None:
            return

        key = f"{KEY_PREFIX}{team_id}"
        try:
            count = await redis.decr(key)
            # Floor at 0
            if count < 0:
                await redis.set(key, 0, ex=KEY_TTL)
        except Exception:
            logger.debug("WS limiter release failed", exc_info=True)
