"""Regression test for the uq_team_provider_scmuser guard.

Two members of the same team both try to connect as the same GitHub
identity. The second one must fail with 409 SCM_IDENTITY_CLAIMED.

We simulate by forcing the DB session to raise ``IntegrityError`` with
the uniqueness name in the message — this is how asyncpg / SQLAlchemy
surface the constraint collision.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

from cryptography.fernet import Fernet
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy.exc import IntegrityError

from ai_driven.auth import oauth_state
from ai_driven.auth import scm_tokens
from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.db.postgres.session import get_session
from ai_driven.config import get_settings


class _FakeRedis:
    def __init__(self):
        self.store = {}

    async def set(self, k, v, ex=None):
        self.store[k] = v
        return True

    async def getdel(self, k):
        return self.store.pop(k, None)

    async def delete(self, k):
        return 1 if self.store.pop(k, None) else 0

    async def close(self):
        pass


def _session_with_integrity_error():
    """First SELECT returns None (no existing row). commit() raises
    IntegrityError with the uq_team_provider_scmuser constraint name.
    """
    session = MagicMock()
    session.rollback = AsyncMock()

    def _add(obj): pass
    session.add = _add

    scalar = MagicMock()
    scalar.scalar_one_or_none = MagicMock(return_value=None)
    scalar.scalars = MagicMock(return_value=MagicMock(all=MagicMock(return_value=[])))
    session.execute = AsyncMock(return_value=scalar)

    # commit raises the constraint violation.
    session.commit = AsyncMock(
        side_effect=IntegrityError(
            statement="INSERT ...",
            params={},
            orig=Exception('duplicate key value violates unique constraint "uq_team_provider_scmuser"'),
        )
    )
    return session


def _mock_github_http():
    token_resp = MagicMock()
    token_resp.raise_for_status = MagicMock()
    token_resp.json = MagicMock(return_value={
        "access_token": "ghp_claimed",
        "scope": "public_repo read:user",
    })
    user_resp = MagicMock()
    user_resp.raise_for_status = MagicMock()
    user_resp.json = MagicMock(return_value={
        "id": 999,
        "login": "shared-user",
        "email": "shared@example.com",
    })
    inst = MagicMock()
    inst.post = AsyncMock(return_value=token_resp)
    inst.get = AsyncMock(return_value=user_resp)
    inst.__aenter__ = AsyncMock(return_value=inst)
    inst.__aexit__ = AsyncMock(return_value=False)
    return inst


def test_identity_collision_returns_409(monkeypatch):
    # Arrange config + Redis + fernet key.
    fake_redis = _FakeRedis()

    async def _fake_get_redis():
        return fake_redis
    monkeypatch.setattr(oauth_state, "_get_redis", _fake_get_redis)

    key = Fernet.generate_key().decode()
    get_settings.cache_clear()
    settings = get_settings()
    monkeypatch.setattr(settings, "scm_token_key", key, raising=False)
    monkeypatch.setattr(settings, "github_oauth_client_id", "cid", raising=False)
    from pydantic import SecretStr
    monkeypatch.setattr(settings, "github_oauth_client_secret", SecretStr("csec"), raising=False)
    monkeypatch.setattr(settings, "scm_oauth_redirect_base", "http://localhost:8888", raising=False)
    scm_tokens.reset_key_cache()

    from ai_driven.api.auth.scm_oauth import router as oauth_router

    app = FastAPI()
    app.include_router(oauth_router, prefix="/api/auth/scm")

    session = _session_with_integrity_error()

    async def _fake_session():
        yield session
    app.dependency_overrides[get_session] = _fake_session

    def _fake_admin():
        # Simulates member-2 of team-A attempting to connect a GitHub
        # account already claimed by member-1 of the same team.
        return TeamMemberInfo(
            member_id="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            team_id="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            role="admin",
        )
    app.dependency_overrides[require_admin] = _fake_admin

    with TestClient(app) as client:
        # Seed state by calling /authorize first.
        resp1 = client.get(
            "/api/auth/scm/github/authorize?scope=public_repo",
            follow_redirects=False,
        )
        assert resp1.status_code == 302
        state = next(iter(fake_redis.store.keys())).split(":")[-1]

        fake_http = _mock_github_http()
        with patch("ai_driven.api.auth.scm_oauth.httpx.AsyncClient", return_value=fake_http):
            resp2 = client.get(
                f"/api/auth/scm/github/callback?code=abc&state={state}",
                follow_redirects=False,
            )

    assert resp2.status_code == 409, resp2.text
    body = resp2.json()
    assert body["error"]["code"] == "SCM_IDENTITY_CLAIMED"
    assert body["error"]["details"][0]["scmUsername"] == "shared-user"
