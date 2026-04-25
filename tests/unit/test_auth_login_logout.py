"""Unit tests for Sprint 14 login + logout endpoints.

Covers:
    - POST /api/auth/login with a valid key → 200 + session cookie + Redis write
    - POST /api/auth/login with an invalid key → 401
    - POST /api/auth/login on Redis outage → 503
    - POST /api/auth/logout with a session cookie → 204 + Redis delete + cookie cleared
    - POST /api/auth/logout idempotent (no cookie) → 204
"""

from __future__ import annotations

import asyncio
import time
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.api.auth.router import LoginRequest, login, logout
from ai_driven.auth.sessions import Session


def _make_team(name: str = "Test Team", key_hash: str = "kh123") -> MagicMock:
    t = MagicMock()
    t.id = uuid.UUID("11111111-1111-1111-1111-111111111111")
    t.name = name
    t.api_key_hash = key_hash
    return t


def _fake_session(team_id: str) -> Session:
    now = time.time()
    return Session(
        token="test-token-abc",
        team_id=team_id,
        member_id=None,
        email="me@example.com",
        role="owner",
        created_at=now,
        last_seen_at=now,
    )


class TestLoginValid:
    def test_valid_key_returns_session_and_sets_cookie(self) -> None:
        team = _make_team(key_hash="hash_of_good_key")

        team_scalar = MagicMock()
        team_scalar.scalar_one_or_none.return_value = team
        member_scalar = MagicMock()
        member_scalar.scalar_one_or_none.return_value = None

        db_session = MagicMock()
        db_session.execute = AsyncMock(side_effect=[team_scalar, member_scalar])

        response = MagicMock()
        response.set_cookie = MagicMock()

        fake_session = _fake_session(str(team.id))

        with patch(
            "ai_driven.auth.middleware.hash_api_key",
            return_value="hash_of_good_key",
        ), patch(
            "ai_driven.auth.sessions.create_session",
            new=AsyncMock(return_value=fake_session),
        ):
            result = asyncio.run(
                login(
                    body=LoginRequest(api_key="good-key", email="me@example.com"),
                    response=response,
                    session=db_session,
                )
            )

        assert result.session_token == "test-token-abc"
        assert result.team_id == str(team.id)
        assert result.email == "me@example.com"
        assert result.role == "owner"

        # Cookie set with HttpOnly + Lax.
        response.set_cookie.assert_called_once()
        kwargs = response.set_cookie.call_args.kwargs
        assert kwargs["key"] == "session_token"
        assert kwargs["value"] == "test-token-abc"
        assert kwargs["httponly"] is True
        assert kwargs["samesite"] == "lax"


class TestLoginInvalidKey:
    def test_invalid_key_returns_401(self) -> None:
        from fastapi import HTTPException

        team_scalar = MagicMock()
        team_scalar.scalar_one_or_none.return_value = None  # no team matched

        db_session = MagicMock()
        db_session.execute = AsyncMock(return_value=team_scalar)

        with patch(
            "ai_driven.auth.middleware.hash_api_key",
            return_value="hash_of_bad_key",
        ):
            with pytest.raises(HTTPException) as exc:
                asyncio.run(
                    login(
                        body=LoginRequest(api_key="bad-key"),
                        response=MagicMock(),
                        session=db_session,
                    )
                )
        assert exc.value.status_code == 401


class TestLoginEmptyKey:
    def test_empty_key_returns_401_without_db_hit(self) -> None:
        from fastapi import HTTPException

        db_session = MagicMock()
        db_session.execute = AsyncMock()

        with pytest.raises(HTTPException) as exc:
            asyncio.run(
                login(
                    body=LoginRequest(api_key="   "),
                    response=MagicMock(),
                    session=db_session,
                )
            )
        assert exc.value.status_code == 401
        db_session.execute.assert_not_awaited()


class TestLoginRedisOutage:
    def test_redis_outage_returns_503(self) -> None:
        from fastapi import HTTPException

        team = _make_team(key_hash="hash_of_good_key")
        team_scalar = MagicMock()
        team_scalar.scalar_one_or_none.return_value = team
        member_scalar = MagicMock()
        member_scalar.scalar_one_or_none.return_value = None

        db_session = MagicMock()
        db_session.execute = AsyncMock(side_effect=[team_scalar, member_scalar])

        with patch(
            "ai_driven.auth.middleware.hash_api_key",
            return_value="hash_of_good_key",
        ), patch(
            "ai_driven.auth.sessions.create_session",
            new=AsyncMock(side_effect=ConnectionError("redis down")),
        ):
            with pytest.raises(HTTPException) as exc:
                asyncio.run(
                    login(
                        body=LoginRequest(api_key="good-key"),
                        response=MagicMock(),
                        session=db_session,
                    )
                )
        assert exc.value.status_code == 503
        assert "Session store unavailable" in exc.value.detail


class TestLogout:
    def test_logout_with_session_cookie_revokes_and_clears(self) -> None:
        request = MagicMock()
        request.headers.get.return_value = ""  # no Authorization header
        request.cookies.get.return_value = "test-token-abc"  # session_token cookie

        response = MagicMock()
        response.delete_cookie = MagicMock()

        with patch(
            "ai_driven.auth.sessions.revoke_session",
            new=AsyncMock(return_value=None),
        ) as revoke_mock:
            result = asyncio.run(logout(request=request, response=response))

        assert result is None
        revoke_mock.assert_awaited_once_with("test-token-abc")
        response.delete_cookie.assert_called_once_with(key="session_token", path="/")

    def test_logout_without_cookie_is_idempotent(self) -> None:
        request = MagicMock()
        request.headers.get.return_value = ""
        request.cookies.get.return_value = None

        response = MagicMock()
        response.delete_cookie = MagicMock()

        with patch(
            "ai_driven.auth.sessions.revoke_session",
            new=AsyncMock(),
        ) as revoke_mock:
            result = asyncio.run(logout(request=request, response=response))

        assert result is None
        revoke_mock.assert_not_awaited()
        response.delete_cookie.assert_called_once_with(key="session_token", path="/")

    def test_logout_tolerates_redis_outage(self) -> None:
        """Redis down during logout still clears the cookie — don't leave the
        browser sending a stale token forever."""
        request = MagicMock()
        request.headers.get.return_value = ""
        request.cookies.get.return_value = "test-token-abc"

        response = MagicMock()
        response.delete_cookie = MagicMock()

        with patch(
            "ai_driven.auth.sessions.revoke_session",
            new=AsyncMock(side_effect=ConnectionError("redis down")),
        ):
            # Should not raise.
            asyncio.run(logout(request=request, response=response))

        response.delete_cookie.assert_called_once_with(key="session_token", path="/")
