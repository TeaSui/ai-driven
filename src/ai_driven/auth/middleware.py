"""API key authentication middleware with team resolution and audit logging."""

from __future__ import annotations

import hashlib
import json
import logging
import time
from typing import Any

from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint

from ai_driven.config import get_settings
from ai_driven.observability.trace_context import bind_trace, bind_team, clear_trace

logger = logging.getLogger(__name__)

# Paths that don't require authentication
PUBLIC_PATHS = {
    "/health",
    "/docs",
    "/openapi.json",
    "/webhooks/github",
    "/webhooks/bitbucket",
    "/webhooks/jira",
    "/webhooks/datadog",
}

# Redis cache TTL for team lookups (seconds)
_TEAM_CACHE_TTL = 300  # 5 minutes
_TEAM_CACHE_PREFIX = "team_lookup:"

# Module-level Redis client (lazy-initialized)
_redis_client: Any = None
_redis_init_attempted: bool = False


def hash_api_key(api_key: str) -> str:
    """Hash an API key for storage/comparison."""
    return hashlib.sha256(api_key.encode()).hexdigest()


async def _get_redis() -> Any:
    """Lazy-initialize and return a Redis client, or None if unavailable."""
    global _redis_client, _redis_init_attempted
    if _redis_init_attempted:
        return _redis_client
    _redis_init_attempted = True
    try:
        import redis.asyncio as aioredis

        settings = get_settings()
        _redis_client = aioredis.from_url(
            settings.redis_url,
            decode_responses=True,
            socket_connect_timeout=2,
        )
        # Verify connectivity
        await _redis_client.ping()
        return _redis_client
    except Exception:
        logger.warning("Redis unavailable for team cache — falling back to no-cache mode")
        _redis_client = None
        return None


async def _get_cached_team(api_key_hash: str) -> dict[str, Any] | None:
    """Look up team data in Redis cache."""
    redis = await _get_redis()
    if redis is None:
        return None
    try:
        cached = await redis.get(f"{_TEAM_CACHE_PREFIX}{api_key_hash}")
        if cached:
            return json.loads(cached)
    except Exception:
        logger.debug("Redis cache read failed for team lookup", exc_info=True)
    return None


async def _set_cached_team(api_key_hash: str, team_data: dict[str, Any]) -> None:
    """Store team data in Redis cache with TTL."""
    redis = await _get_redis()
    if redis is None:
        return
    try:
        await redis.set(
            f"{_TEAM_CACHE_PREFIX}{api_key_hash}",
            json.dumps(team_data),
            ex=_TEAM_CACHE_TTL,
        )
    except Exception:
        logger.debug("Redis cache write failed for team lookup", exc_info=True)


async def _resolve_grace_key(api_key_hash: str) -> dict[str, Any] | None:
    """Check if this is a rotated key within the 48h grace period."""
    redis = await _get_redis()
    if redis is None:
        return None
    try:
        team_id = await redis.get(f"grace_key:{api_key_hash}")
        if team_id is None:
            return None
        # Resolve full team data from the team_id
        return await _resolve_team_from_db_by_id(team_id)
    except Exception:
        logger.debug("Grace key check failed", exc_info=True)
        return None


async def _resolve_team_from_db_by_id(team_id: str) -> dict[str, Any] | None:
    """Resolve team by ID (used for grace key lookups)."""
    import uuid as _uuid
    from ai_driven.db.postgres.session import async_session_factory
    from ai_driven.db.postgres.repository import TeamRepository

    async with async_session_factory() as session:
        repo = TeamRepository(session)
        team = await repo.get_by_id(_uuid.UUID(team_id))
        if team is None or not team.is_active:
            return None
        return {
            "id": str(team.id),
            "name": team.name,
            "max_concurrent_tasks": team.max_concurrent_tasks,
        }


async def _resolve_team_from_db(api_key_hash: str) -> dict[str, Any] | None:
    """Resolve team from PostgreSQL using the hashed API key."""
    from ai_driven.db.postgres.session import async_session_factory
    from ai_driven.db.postgres.repository import TeamRepository

    async with async_session_factory() as session:
        repo = TeamRepository(session)
        team = await repo.get_by_api_key_hash(api_key_hash)
        if team is None:
            return None
        return {
            "id": str(team.id),
            "name": team.name,
            "max_concurrent_tasks": team.max_concurrent_tasks,
        }


class APIKeyMiddleware(BaseHTTPMiddleware):
    """Validates API key from X-API-Key header and resolves team."""

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        path = request.url.path

        # Generate trace_id for every request (including public paths).
        # Incoming X-Trace-Id header is honoured for cross-service propagation.
        incoming_trace = request.headers.get("X-Trace-Id", "")
        trace_id = bind_trace(trace_id=incoming_trace or None)
        request.state.trace_id = trace_id

        # Skip auth for public paths and webhook endpoints (they use their own auth)
        if any(path.startswith(p) for p in PUBLIC_PATHS):
            response = await call_next(request)
            response.headers["X-Trace-Id"] = trace_id
            clear_trace()
            return response

        # In local/debug mode, skip auth for API paths (Web UI dev without API key)
        settings = get_settings()
        if settings.debug and not request.headers.get("X-API-Key"):
            request.state.team_id = "00000000-0000-0000-0000-000000000000"
            bind_team("00000000-0000-0000-0000-000000000000")
            response = await call_next(request)
            response.headers["X-Trace-Id"] = trace_id
            clear_trace()
            return response

        api_key = request.headers.get("X-API-Key")
        if not api_key:
            from fastapi.responses import JSONResponse
            return JSONResponse(
                status_code=401,
                content={"error": {"code": "UNAUTHORIZED", "message": "Missing X-API-Key header", "details": []}},
            )

        api_key_hash = hash_api_key(api_key)
        request.state.api_key_hash = api_key_hash

        # Resolve team: cache first, then DB
        team_data = await _get_cached_team(api_key_hash)
        if team_data is None:
            team_data = await _resolve_team_from_db(api_key_hash)
            if team_data is not None:
                await _set_cached_team(api_key_hash, team_data)

        # Grace key check: old rotated keys have 48h grace in Redis
        if team_data is None:
            team_data = await _resolve_grace_key(api_key_hash)
            if team_data is not None:
                logger.warning(
                    "API key using grace period — rotate to new key (prefix: %s)",
                    api_key_hash[:8],
                )
                request.state.grace_key = True

        if team_data is None:
            from fastapi.responses import JSONResponse
            return JSONResponse(
                status_code=401,
                content={"error": {"code": "UNAUTHORIZED", "message": "Invalid API key", "details": []}},
            )

        # Inject team_id and role into request state.
        request.state.team_id = team_data["id"]
        request.state.team_role = team_data.get("role", "owner")
        bind_team(team_data["id"])

        # Audit log: record API call per team (structured logging)
        start_time = time.monotonic()
        response = await call_next(request)
        duration_ms = round((time.monotonic() - start_time) * 1000, 2)

        logger.info(
            "api_call",
            extra={
                "method": request.method,
                "path": path,
                "status_code": response.status_code,
                "duration_ms": duration_ms,
            },
        )

        response.headers["X-Trace-Id"] = trace_id
        clear_trace()
        return response
