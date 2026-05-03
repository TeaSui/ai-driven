"""Redis-backed OAuth ``state`` parameter store (PR 1).

The state parameter is the sole auth on the provider's redirect-to-
callback leg, so it needs three properties:

1. **Unguessable** — 32 byte ``secrets.token_urlsafe`` is ~192 bits
   of entropy.
2. **Single-use** — ``consume_state`` uses Redis ``GETDEL`` so the key
   is atomically read and deleted, eliminating the replay window that
   a separate GET-then-DEL would leave open. Falls back to MULTI/GET+DEL
   when Redis < 6.2 lacks GETDEL (best-effort atomicity).
3. **TTL-bounded** — 10 minute expiry so an abandoned flow can't sit
   in Redis forever. Matches the brief.

Key shape ``scm_oauth:state:<state>`` — documented as env-specific in
the runbook; either separate Redis instances per env or prefix the key
via configuration.
"""

from __future__ import annotations

import json
import logging
import secrets
from typing import Any

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

_STATE_KEY_PREFIX = "scm_oauth:state:"
_STATE_TTL_SECONDS = 600  # 10 minutes


async def _get_redis() -> Any:
    """Return an async Redis client."""
    import redis.asyncio as aioredis

    settings = get_settings()
    return aioredis.from_url(settings.redis_url, decode_responses=True)


async def create_state(
    member_id: str, team_id: str, provider: str, requested_scope: str
) -> str:
    """Generate + persist a new state token. Returns the ``state`` value.

    The payload is JSON-serialized so the callback can look up the
    originating member + team + requested scope without trusting
    anything from the redirect query string except the state token itself.
    """
    state = secrets.token_urlsafe(32)
    payload = {
        "memberId": member_id,
        "teamId": team_id,
        "provider": provider,
        "requestedScope": requested_scope,
    }
    redis = await _get_redis()
    try:
        await redis.set(
            f"{_STATE_KEY_PREFIX}{state}",
            json.dumps(payload),
            ex=_STATE_TTL_SECONDS,
        )
    finally:
        try:
            await redis.close()
        except Exception:
            pass
    return state


async def consume_state(state: str) -> dict | None:
    """Atomically fetch + delete the state payload.

    Returns ``None`` when the state is unknown, already consumed, or
    expired. Returns the decoded payload dict on success. Never raises
    on bad input — handlers want a single branch for "state is invalid"
    regardless of reason.
    """
    if not state or not isinstance(state, str):
        return None
    key = f"{_STATE_KEY_PREFIX}{state}"
    redis = await _get_redis()
    raw: str | None = None
    try:
        # GETDEL: atomic "read the value, then delete it" — prevents the
        # narrow replay window a plain GET-then-DEL would open (attacker
        # sees the code in the provider's redirect log AND the same
        # state in a parallel request before we delete it).
        try:
            raw = await redis.getdel(key)
        except AttributeError:
            # Older py-redis: fall back to a MULTI/EXEC pipeline which
            # is atomic but not isolated — acceptable degradation since
            # the TTL still bounds abuse to <10 min.
            pipe = redis.pipeline(transaction=True)
            pipe.get(key)
            pipe.delete(key)
            raw, _ = await pipe.execute()
    except Exception as ex:
        logger.warning("oauth_state consume failed: %s", ex)
        return None
    finally:
        try:
            await redis.close()
        except Exception:
            pass

    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        # Corrupt payload — treat as invalid state. Shouldn't happen
        # in practice; log for investigation.
        logger.warning("oauth_state: corrupt payload for key %s", key)
        return None
