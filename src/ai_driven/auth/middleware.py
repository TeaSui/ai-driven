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
    # Sprint 14: login must be reachable without prior auth. Logout is
    # idempotent and clears browser state even when the current session is
    # already invalid.
    "/api/auth/login",
    "/api/auth/logout",
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


def _extract_session_token_mw(request: Request) -> str | None:
    """Middleware version of _extract_session_token — avoids a cross-module import.

    Returns the session token if a browser-shaped credential is present.
    Separate from the API-key path so agents with only X-API-Key are not
    forced through Redis.
    """
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer "):
        return auth.removeprefix("Bearer ").strip() or None
    cookie = request.cookies.get("session_token")
    return cookie or None


async def _resolve_session(token: str) -> dict[str, Any] | None:
    """Validate a session token against Redis. None on any failure.

    Fail-closed on Redis outage — the caller returns 401 and the browser
    redirects to /login. Agents and scripts never see this path (they use
    X-API-Key), so the Redis hard-dependency is UI-only.

    In debug mode, the ``debug-session`` sentinel (set by the Next.js
    middleware when the backend reports ``debug=true`` on /health) resolves
    to the sentinel debug team — mirrors the X-API-Key debug bypass.
    """
    settings = get_settings()
    if settings.debug and token == "debug-session":
        return {
            "team_id": "00000000-0000-0000-0000-000000000000",
            "role": "owner",
            "member_id": None,
        }
    try:
        from ai_driven.auth import sessions as session_store
        sess = await session_store.load_session(token)
        if not sess:
            return None
        return {
            "team_id": sess.team_id,
            "role": sess.role,
            "member_id": sess.member_id,
        }
    except Exception as ex:
        logger.warning("session resolution failed: %s", ex)
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

        # Sprint 14: session-token path (browser UI). If either
        # Authorization: Bearer <token> or a session_token cookie is
        # present, resolve via Redis instead of the X-API-Key path.
        # Agents, webhooks, scripts still use X-API-Key.
        session_token = _extract_session_token_mw(request)
        if session_token:
            session_data = await _resolve_session(session_token)
            if session_data is None:
                from fastapi.responses import JSONResponse
                return JSONResponse(
                    status_code=401,
                    content={"error": {"code": "UNAUTHORIZED", "message": "Session expired or invalid", "details": []}},
                )
            request.state.team_id = session_data["team_id"]
            request.state.team_role = session_data.get("role", "member")
            request.state.member_id = session_data.get("member_id")
            request.state.session_token = session_token
            bind_team(session_data["team_id"])
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


# ---------------------------------------------------------------------------
# T24: require_admin dependency
# ---------------------------------------------------------------------------

# UUID of the seeded "system" team_member row used by background reapers.
# The migration f6a7b8c9d0e1 inserts this member as admin@system with a
# fixed UUID so reapers can reference it without a bootstrap lookup.
SYSTEM_MEMBER_ID = "00000000-0000-0000-0000-000000000001"


class TeamMemberInfo:
    """Lightweight view of the authenticated team member.

    Populated by ``require_admin``. Carries just the fields the override
    endpoint needs (member id for audit rows, team id for scoping, role
    for the guard we just passed). Deliberately not a Pydantic model —
    no validation cost, this is an in-process contract between the
    dependency and its callers.
    """

    __slots__ = ("member_id", "team_id", "role", "email")

    def __init__(
        self,
        *,
        member_id: str,
        team_id: str,
        role: str,
        email: str | None = None,
    ) -> None:
        self.member_id = member_id
        self.team_id = team_id
        self.role = role
        self.email = email


async def require_admin(request: Request) -> TeamMemberInfo:
    """FastAPI dependency: resolve the authenticated team_member and
    assert ``role in {"admin", "owner"}``.

    Auth paths accepted (mirrors APIKeyMiddleware):
      1. Session token (UI): middleware has set ``request.state.team_id``,
         ``team_role``, ``member_id``. We trust these.
      2. X-API-Key: middleware has set ``team_id`` + ``api_key_hash``.
         We look up the matching team_member row by api_key_hash; if the
         key is team-level (no member row), we use the seeded system
         member for owners, else 403.
      3. Debug bypass: when settings.debug and the sentinel team is set,
         we resolve to the system member with role='admin' so local dev
         can exercise the override endpoint without creating a real
         member.

    Returns ``TeamMemberInfo``. Raises:
      * 401 — no authenticated team on request.state
      * 403 — authenticated but role is not admin/owner
    """
    from fastapi import HTTPException

    team_id = getattr(request.state, "team_id", None)
    if not team_id:
        raise HTTPException(status_code=401, detail="Not authenticated")

    settings = get_settings()

    # Debug bypass: sentinel team id + debug mode = system admin.
    # Keeps local dev ergonomics — the existing debug path in the
    # middleware sets team_id without a member row, and we don't want
    # to force developers to seed a team_member to exercise admin APIs.
    if settings.debug and team_id == "00000000-0000-0000-0000-000000000000":
        return TeamMemberInfo(
            member_id=SYSTEM_MEMBER_ID,
            team_id=team_id,
            role="admin",
            email="debug@local",
        )

    # Session-token path: middleware has populated role (and optionally
    # member_id). Team-level logins (X-API-Key owner token exchanged for a
    # session) have role='owner' but no member_id because team keys don't
    # map to a specific team_member row — attribute those to the system
    # member, same convention as the X-API-Key branch below.
    role = getattr(request.state, "team_role", None)
    member_id = getattr(request.state, "member_id", None)

    if role:
        if role not in {"admin", "owner"}:
            raise HTTPException(status_code=403, detail="Admin role required")
        return TeamMemberInfo(
            member_id=member_id or SYSTEM_MEMBER_ID,
            team_id=team_id,
            role=role,
        )

    # X-API-Key path: role was set by middleware (team-level keys are
    # 'owner' by convention), but we need to resolve member_id from the
    # api_key_hash. Team-level keys have no matching team_member row —
    # we treat those as 'owner' and map them to the system member for
    # audit purposes.
    api_key_hash = getattr(request.state, "api_key_hash", None)
    if api_key_hash:
        import uuid as _uuid
        from ai_driven.db.postgres.models import TeamMember
        from ai_driven.db.postgres.session import async_session_factory
        from sqlalchemy import select

        async with async_session_factory() as s:
            res = await s.execute(
                select(TeamMember).where(
                    TeamMember.team_id == _uuid.UUID(team_id),
                    TeamMember.api_key_hash == api_key_hash,
                )
            )
            member = res.scalar_one_or_none()
            if member is not None:
                if member.role not in {"admin", "owner"}:
                    raise HTTPException(
                        status_code=403, detail="Admin role required"
                    )
                return TeamMemberInfo(
                    member_id=str(member.id),
                    team_id=team_id,
                    role=member.role,
                    email=member.email,
                )

        # No member row — the key is team-level. Treat it as an owner
        # (middleware already did) and audit under the system member.
        if role == "owner":
            return TeamMemberInfo(
                member_id=SYSTEM_MEMBER_ID, team_id=team_id, role="owner"
            )

    raise HTTPException(status_code=403, detail="Admin role required")
