"""Redis-backed session store for the UI login flow.

Sprint 14 (docs/implementation/14-login-ux-overhaul.md). Defaults:
    - 24 h sliding TTL
    - 30 d absolute cap (sessions created longer ago than max_age are rejected)
    - opaque 32-byte base64url token (not a JWT)

Agents, webhooks, and scripts still authenticate via ``X-API-Key`` directly;
sessions are a *browser-only* credential. On Redis outage we fail closed for
login — the user gets a clear error and retries shortly. Request-time session
validation also fails closed; they can re-login or use an API key.

Redis keys:
    session:{token}           hash {team_id, member_id, email, created_at, last_seen_at}
    sessions:team:{team_id}   set of token strings (for bulk revoke / device list)
"""

from __future__ import annotations

import json
import secrets
import time
from dataclasses import asdict, dataclass
from typing import Any

import redis.asyncio as aioredis

from ai_driven.config import get_settings


SESSION_KEY_PREFIX = "session:"
SESSION_INDEX_PREFIX = "sessions:team:"


@dataclass
class Session:
    """In-memory view of a session record. Mirrors the Redis hash payload."""

    token: str
    team_id: str
    member_id: str | None
    email: str | None
    role: str
    created_at: float   # epoch seconds
    last_seen_at: float  # epoch seconds

    @property
    def age_seconds(self) -> float:
        return time.time() - self.created_at


def _token() -> str:
    """32-byte URL-safe token. Not a JWT — no structure for an attacker to exploit."""
    return secrets.token_urlsafe(32)


async def _client() -> aioredis.Redis:
    settings = get_settings()
    return aioredis.from_url(settings.redis_url, decode_responses=True)


async def create_session(
    *,
    team_id: str,
    member_id: str | None,
    email: str | None,
    role: str,
) -> Session:
    """Mint a new session and persist it to Redis.

    Raises ``redis.exceptions.RedisError`` on Redis outage — caller should
    surface that as HTTP 503 so the user retries, not 5xx so we alert.
    """
    settings = get_settings()
    now = time.time()
    token = _token()

    session = Session(
        token=token,
        team_id=team_id,
        member_id=member_id,
        email=email,
        role=role,
        created_at=now,
        last_seen_at=now,
    )

    redis = await _client()
    try:
        pipe = redis.pipeline()
        pipe.set(
            SESSION_KEY_PREFIX + token,
            json.dumps(asdict(session)),
            ex=settings.session_ttl_seconds,
        )
        pipe.sadd(SESSION_INDEX_PREFIX + team_id, token)
        # Keep the index key alive at least as long as any session could live.
        pipe.expire(
            SESSION_INDEX_PREFIX + team_id, settings.session_max_age_seconds
        )
        await pipe.execute()
    finally:
        await redis.aclose()

    return session


async def load_session(token: str) -> Session | None:
    """Return the session, touching ``last_seen_at`` and extending the TTL.

    Returns None if the token is missing, expired, or past the 30-day hard cap.
    Never raises on malformed tokens — returns None.
    """
    if not token:
        return None

    settings = get_settings()
    redis = await _client()
    try:
        raw = await redis.get(SESSION_KEY_PREFIX + token)
        if not raw:
            return None

        try:
            data = json.loads(raw)
            session = Session(**data)
        except Exception:
            # Corrupt record — treat as missing so the user is forced to re-login.
            return None

        # Hard cap: sessions older than max_age are invalid regardless of TTL.
        # Keeps a sliding-TTL session from outliving the absolute limit.
        if time.time() - session.created_at > settings.session_max_age_seconds:
            await redis.delete(SESSION_KEY_PREFIX + token)
            await redis.srem(SESSION_INDEX_PREFIX + session.team_id, token)
            return None

        # Slide the window: touch last_seen_at and re-extend the TTL.
        session.last_seen_at = time.time()
        await redis.set(
            SESSION_KEY_PREFIX + token,
            json.dumps(asdict(session)),
            ex=settings.session_ttl_seconds,
        )

        return session
    finally:
        await redis.aclose()


async def revoke_session(token: str) -> None:
    """Delete a single session. Idempotent — missing tokens are a no-op."""
    if not token:
        return
    redis = await _client()
    try:
        # Fetch team_id for the index cleanup.
        raw = await redis.get(SESSION_KEY_PREFIX + token)
        team_id: str | None = None
        if raw:
            try:
                team_id = json.loads(raw).get("team_id")
            except Exception:
                team_id = None
        pipe = redis.pipeline()
        pipe.delete(SESSION_KEY_PREFIX + token)
        if team_id:
            pipe.srem(SESSION_INDEX_PREFIX + team_id, token)
        await pipe.execute()
    finally:
        await redis.aclose()


async def list_team_sessions(team_id: str) -> list[Session]:
    """Return all live sessions for a team. Used by /dashboard/admin/sessions.

    Cleans up index entries whose session keys have expired.
    """
    redis = await _client()
    out: list[Session] = []
    try:
        tokens = await redis.smembers(SESSION_INDEX_PREFIX + team_id)
        stale: list[str] = []
        for token in tokens:
            raw = await redis.get(SESSION_KEY_PREFIX + token)
            if not raw:
                stale.append(token)
                continue
            try:
                out.append(Session(**json.loads(raw)))
            except Exception:
                stale.append(token)
        if stale:
            await redis.srem(SESSION_INDEX_PREFIX + team_id, *stale)
        return out
    finally:
        await redis.aclose()


def to_wire(session: Session) -> dict[str, Any]:
    """Serialize a session for HTTP responses (ISO timestamps, no raw epoch floats)."""
    settings = get_settings()
    from datetime import datetime, timezone

    def iso(ts: float) -> str:
        return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()

    # Expiry = min(TTL-from-last-seen, hard-cap-from-created-at).
    expires_at = min(
        session.last_seen_at + settings.session_ttl_seconds,
        session.created_at + settings.session_max_age_seconds,
    )

    return {
        "session_token": session.token,
        "team_id": session.team_id,
        "member_id": session.member_id,
        "email": session.email,
        "role": session.role,
        "created_at": iso(session.created_at),
        "last_seen_at": iso(session.last_seen_at),
        "expires_at": iso(expires_at),
    }
