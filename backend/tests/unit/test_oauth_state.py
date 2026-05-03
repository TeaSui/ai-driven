"""Unit tests for ai_driven.auth.oauth_state.

Uses an in-memory fake Redis client (same shape used by the middleware
tests) so we can assert atomicity of GETDEL without standing up a real
Redis.
"""

from __future__ import annotations

import json
from typing import Any

import pytest

from ai_driven.auth import oauth_state


class _FakeRedis:
    """Minimal async-Redis replacement — supports set/get/getdel/delete.

    TTL is stored but not enforced (tests that need expiry call
    expire_now() explicitly).
    """

    def __init__(self) -> None:
        self.store: dict[str, str] = {}
        self.ttl: dict[str, int] = {}
        self.closed = False

    async def set(self, key: str, value: str, ex: int | None = None) -> bool:
        self.store[key] = value
        if ex is not None:
            self.ttl[key] = ex
        return True

    async def get(self, key: str) -> str | None:
        return self.store.get(key)

    async def getdel(self, key: str) -> str | None:
        return self.store.pop(key, None)

    async def delete(self, key: str) -> int:
        return 1 if self.store.pop(key, None) is not None else 0

    def pipeline(self, transaction: bool = True) -> Any:
        parent = self

        class _Pipe:
            def __init__(self) -> None:
                self.ops: list[tuple] = []

            def get(self, key: str) -> None:
                self.ops.append(("get", key))

            def delete(self, key: str) -> None:
                self.ops.append(("delete", key))

            async def execute(self) -> list:
                out = []
                for op, key in self.ops:
                    if op == "get":
                        out.append(parent.store.get(key))
                    elif op == "delete":
                        parent.store.pop(key, None)
                        out.append(1)
                return out

        return _Pipe()

    async def close(self) -> None:
        self.closed = True

    def expire_now(self, key: str) -> None:
        """Simulate TTL expiry for a specific key."""
        self.store.pop(key, None)


@pytest.fixture
def fake_redis(monkeypatch):
    fake = _FakeRedis()

    async def _fake_get_redis():
        return fake

    monkeypatch.setattr(oauth_state, "_get_redis", _fake_get_redis)
    return fake


async def test_create_and_consume_roundtrip(fake_redis):
    state = await oauth_state.create_state(
        member_id="m-1", team_id="t-1", provider="github", requested_scope="public_repo",
    )
    assert isinstance(state, str) and len(state) >= 32

    payload = await oauth_state.consume_state(state)
    assert payload == {
        "memberId": "m-1",
        "teamId": "t-1",
        "provider": "github",
        "requestedScope": "public_repo",
    }


async def test_consume_is_single_use(fake_redis):
    state = await oauth_state.create_state("m", "t", "github", "repo")
    first = await oauth_state.consume_state(state)
    assert first is not None

    # Replay — second consume finds nothing (GETDEL removed the key).
    second = await oauth_state.consume_state(state)
    assert second is None


async def test_unknown_state_returns_none(fake_redis):
    assert await oauth_state.consume_state("does-not-exist") is None


async def test_empty_state_returns_none(fake_redis):
    assert await oauth_state.consume_state("") is None
    assert await oauth_state.consume_state(None) is None  # type: ignore[arg-type]


async def test_expired_state_returns_none(fake_redis):
    state = await oauth_state.create_state("m", "t", "github", "repo")
    fake_redis.expire_now(f"scm_oauth:state:{state}")
    assert await oauth_state.consume_state(state) is None


async def test_ttl_set_on_create(fake_redis):
    state = await oauth_state.create_state("m", "t", "github", "repo")
    assert fake_redis.ttl[f"scm_oauth:state:{state}"] == 600


async def test_corrupt_payload_returns_none(fake_redis):
    # Hand-craft a bad payload directly in Redis, bypassing create_state.
    fake_redis.store["scm_oauth:state:bad"] = "not-json"
    assert await oauth_state.consume_state("bad") is None
