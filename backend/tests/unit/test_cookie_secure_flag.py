"""Session cookie Secure flag is env-aware (P0 from Sprint-14 audit)."""

from __future__ import annotations

import asyncio
import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.api.auth.router import LoginRequest, login
from ai_driven.auth.sessions import Session
from ai_driven.config import Environment


def _team():
    t = MagicMock()
    t.id = "11111111-1111-1111-1111-111111111111"
    t.name = "T"
    t.api_key_hash = "h"
    return t


def _session(team_id: str) -> Session:
    now = time.time()
    return Session(
        token="tok",
        team_id=team_id,
        member_id="22222222-2222-2222-2222-222222222222",
        email="e@x",
        role="owner",
        created_at=now,
        last_seen_at=now,
    )


def _run_login(env: Environment) -> MagicMock:
    """Invoke login() with a fake DB + session store; return the response mock
    so the caller can inspect the Secure flag passed to set_cookie()."""
    team = _team()
    member = MagicMock()
    member.id = "22222222-2222-2222-2222-222222222222"
    member.email = "e@x"
    member.role = "owner"
    member.is_active = True

    team_scalar = MagicMock()
    team_scalar.scalar_one_or_none.return_value = team
    member_scalar = MagicMock()
    member_scalar.scalar_one_or_none.return_value = member
    db = MagicMock()
    db.execute = AsyncMock(side_effect=[team_scalar, member_scalar])
    response = MagicMock()
    response.set_cookie = MagicMock()

    fake_settings = MagicMock()
    fake_settings.environment = env
    fake_settings.session_ttl_seconds = 86400

    with patch(
        "ai_driven.auth.middleware.hash_api_key", return_value="h"
    ), patch(
        "ai_driven.auth.sessions.create_session",
        new=AsyncMock(return_value=_session(str(team.id))),
    ), patch(
        "ai_driven.config.get_settings", return_value=fake_settings
    ):
        asyncio.run(login(
            body=LoginRequest(apiKey="k", email="e@x"),
            response=response,
            session=db,
        ))
    return response


class TestCookieSecureFlag:
    def test_local_env_is_insecure(self) -> None:
        resp = _run_login(Environment.LOCAL)
        kwargs = resp.set_cookie.call_args.kwargs
        assert kwargs["secure"] is False, (
            "LOCAL env must send Secure=False so http://localhost works"
        )

    @pytest.mark.parametrize(
        "env", [Environment.DEV, Environment.STAGING, Environment.PRODUCTION]
    )
    def test_deployed_envs_set_secure(self, env: Environment) -> None:
        resp = _run_login(env)
        kwargs = resp.set_cookie.call_args.kwargs
        assert kwargs["secure"] is True, (
            f"{env} runs behind HTTPS and must set Secure=True to avoid "
            f"leaking session tokens to a downgrade attack."
        )
