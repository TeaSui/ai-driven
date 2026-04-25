"""Unit tests for the Redis session store (Sprint 14).

These tests use a fakeredis-like MagicMock so the file runs without a real
Redis instance. They cover:
    - create_session writes the hash + index set, returns a Session with a
      fresh token + timestamps.
    - load_session returns None for missing token, expired token, corrupt
      data; touches last_seen_at on hit.
    - load_session enforces the 30-day hard cap.
    - revoke_session deletes the hash + index entry; idempotent on missing.
"""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import asdict
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.auth.sessions import (
    Session,
    SESSION_INDEX_PREFIX,
    SESSION_KEY_PREFIX,
    create_session,
    load_session,
    revoke_session,
)


def _fake_redis_pipeline() -> MagicMock:
    pipe = MagicMock()
    pipe.set = MagicMock(return_value=pipe)
    pipe.sadd = MagicMock(return_value=pipe)
    pipe.expire = MagicMock(return_value=pipe)
    pipe.delete = MagicMock(return_value=pipe)
    pipe.srem = MagicMock(return_value=pipe)
    pipe.execute = AsyncMock(return_value=[])
    return pipe


class TestCreateSession:
    def test_mints_token_and_writes_both_keys(self) -> None:
        pipe = _fake_redis_pipeline()
        redis = MagicMock()
        redis.pipeline = MagicMock(return_value=pipe)
        redis.aclose = AsyncMock()

        with patch(
            "ai_driven.auth.sessions._client",
            new=AsyncMock(return_value=redis),
        ):
            sess = asyncio.run(
                create_session(
                    team_id="00000000-0000-0000-0000-000000000000",
                    member_id=None,
                    email="me@example.com",
                    role="owner",
                )
            )

        assert sess.team_id == "00000000-0000-0000-0000-000000000000"
        assert sess.email == "me@example.com"
        assert sess.role == "owner"
        assert len(sess.token) > 30  # base64url of 32 bytes is ~43 chars
        # Both Redis writes executed.
        pipe.set.assert_called_once()
        pipe.sadd.assert_called_once()
        pipe.expire.assert_called_once()
        pipe.execute.assert_awaited_once()


class TestLoadSession:
    def _redis_with_get(self, value: str | None) -> MagicMock:
        redis = MagicMock()
        redis.get = AsyncMock(return_value=value)
        redis.set = AsyncMock(return_value=None)
        redis.delete = AsyncMock(return_value=None)
        redis.srem = AsyncMock(return_value=None)
        redis.aclose = AsyncMock()
        return redis

    def test_missing_token_returns_none(self) -> None:
        redis = self._redis_with_get(None)
        with patch(
            "ai_driven.auth.sessions._client",
            new=AsyncMock(return_value=redis),
        ):
            result = asyncio.run(load_session("nope"))
        assert result is None

    def test_empty_token_returns_none_without_redis(self) -> None:
        # Short-circuit: no Redis call for an empty token.
        with patch(
            "ai_driven.auth.sessions._client",
            new=AsyncMock(side_effect=AssertionError("should not be called")),
        ):
            result = asyncio.run(load_session(""))
        assert result is None

    def test_corrupt_payload_returns_none(self) -> None:
        redis = self._redis_with_get("{not: valid json}")
        with patch(
            "ai_driven.auth.sessions._client",
            new=AsyncMock(return_value=redis),
        ):
            result = asyncio.run(load_session("corrupt"))
        assert result is None

    def test_valid_session_touches_last_seen_and_returns_it(self) -> None:
        now = time.time()
        old_session = Session(
            token="t1",
            team_id="team-1",
            member_id=None,
            email="a@b.c",
            role="owner",
            created_at=now - 60,
            last_seen_at=now - 60,
        )
        redis = self._redis_with_get(json.dumps(asdict(old_session)))
        with patch(
            "ai_driven.auth.sessions._client",
            new=AsyncMock(return_value=redis),
        ):
            result = asyncio.run(load_session("t1"))
        assert result is not None
        assert result.team_id == "team-1"
        # last_seen_at bumped.
        assert result.last_seen_at >= old_session.last_seen_at
        # set() was called to slide the TTL.
        redis.set.assert_awaited_once()

    def test_hard_cap_rejects_ancient_session(self) -> None:
        now = time.time()
        ancient = Session(
            token="t1",
            team_id="team-1",
            member_id=None,
            email=None,
            role="owner",
            created_at=now - (31 * 86400),  # older than 30d cap
            last_seen_at=now - 60,
        )
        redis = self._redis_with_get(json.dumps(asdict(ancient)))
        with patch(
            "ai_driven.auth.sessions._client",
            new=AsyncMock(return_value=redis),
        ):
            result = asyncio.run(load_session("t1"))
        assert result is None
        # The hard-cap path deletes the expired token.
        redis.delete.assert_awaited_once()


class TestRevokeSession:
    def test_deletes_session_and_index_entry(self) -> None:
        pipe = _fake_redis_pipeline()
        redis = MagicMock()
        redis.pipeline = MagicMock(return_value=pipe)
        redis.get = AsyncMock(return_value=json.dumps({"team_id": "team-xyz"}))
        redis.aclose = AsyncMock()

        with patch(
            "ai_driven.auth.sessions._client",
            new=AsyncMock(return_value=redis),
        ):
            asyncio.run(revoke_session("some-token"))

        pipe.delete.assert_called_once_with(SESSION_KEY_PREFIX + "some-token")
        pipe.srem.assert_called_once_with(
            SESSION_INDEX_PREFIX + "team-xyz", "some-token"
        )

    def test_missing_token_is_noop(self) -> None:
        # Short-circuits on empty token — no Redis call.
        with patch(
            "ai_driven.auth.sessions._client",
            new=AsyncMock(side_effect=AssertionError("should not be called")),
        ):
            asyncio.run(revoke_session(""))
