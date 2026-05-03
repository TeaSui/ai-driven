"""Integration tests for the SCM OAuth authorize/callback flow.

Mocks httpx at the module boundary so the provider's token endpoint
and /user endpoint are faked. The Redis-backed state store uses an
in-memory fake. The DB is a mocked AsyncSession — PR 1 doesn't need
real SQL to exercise the business logic.
"""

from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
from cryptography.fernet import Fernet
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_driven.auth import oauth_state
from ai_driven.auth import scm_tokens
from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.db.postgres.session import get_session
from ai_driven.config import get_settings


SYSTEM_TEAM = "11111111-1111-1111-1111-111111111111"
SYSTEM_MEMBER = "22222222-2222-2222-2222-222222222222"


class _FakeRedis:
    def __init__(self) -> None:
        self.store: dict[str, str] = {}

    async def set(self, key: str, value: str, ex: int | None = None) -> bool:
        self.store[key] = value
        return True

    async def getdel(self, key: str) -> str | None:
        return self.store.pop(key, None)

    async def delete(self, key: str) -> int:
        return 1 if self.store.pop(key, None) is not None else 0

    async def close(self) -> None:
        pass


@pytest.fixture
def fake_redis(monkeypatch):
    fake = _FakeRedis()

    async def _fake_get_redis():
        return fake

    monkeypatch.setattr(oauth_state, "_get_redis", _fake_get_redis)
    return fake


@pytest.fixture
def fernet_key(monkeypatch):
    key = Fernet.generate_key().decode()
    get_settings.cache_clear()
    settings = get_settings()
    monkeypatch.setattr(settings, "scm_token_key", key, raising=False)
    monkeypatch.setattr(settings, "scm_token_key_prior_keys", [], raising=False)
    monkeypatch.setattr(settings, "scm_token_key_secret_arn", "", raising=False)
    monkeypatch.setattr(settings, "github_oauth_client_id", "client-id-gh", raising=False)
    from pydantic import SecretStr
    monkeypatch.setattr(settings, "github_oauth_client_secret", SecretStr("client-secret-gh"), raising=False)
    monkeypatch.setattr(settings, "scm_oauth_redirect_base", "http://localhost:8888", raising=False)
    scm_tokens.reset_key_cache()
    yield key
    scm_tokens.reset_key_cache()


def _make_session():
    """Build a mocked AsyncSession that simulates 'no existing row' and
    stores the inserted row on session.add."""
    session = MagicMock()
    session.commit = AsyncMock()
    session.rollback = AsyncMock()

    added: list = []

    def _add(obj):
        added.append(obj)
    session.add = _add
    session.added = added

    # First SELECT returns None (no existing connection).
    scalar_result = MagicMock()
    scalar_result.scalar_one_or_none = MagicMock(return_value=None)
    scalar_result.scalars = MagicMock(return_value=MagicMock(all=MagicMock(return_value=[])))
    session.execute = AsyncMock(return_value=scalar_result)
    return session


def _make_app(session, *, admin=True, member_id=SYSTEM_MEMBER, team_id=SYSTEM_TEAM):
    from ai_driven.api.auth.scm_oauth import router as oauth_router
    from ai_driven.api.admin.connections import router as conn_router

    app = FastAPI()
    app.include_router(oauth_router, prefix="/api/auth/scm")
    app.include_router(conn_router, prefix="/api/admin")

    async def _fake_session():
        yield session

    app.dependency_overrides[get_session] = _fake_session

    if admin:
        def _fake_admin():
            return TeamMemberInfo(member_id=member_id, team_id=team_id, role="admin")
        app.dependency_overrides[require_admin] = _fake_admin
    else:
        from fastapi import HTTPException

        def _deny():
            raise HTTPException(status_code=403, detail="Admin role required")
        app.dependency_overrides[require_admin] = _deny
    return TestClient(app)


# ---------------------------------------------------------------------------
# authorize
# ---------------------------------------------------------------------------


def test_authorize_redirects_to_github_with_state(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session)

    resp = client.get(
        "/api/auth/scm/github/authorize?scope=public_repo",
        follow_redirects=False,
    )
    assert resp.status_code == 302
    loc = resp.headers["location"]
    assert loc.startswith("https://github.com/login/oauth/authorize?")
    assert "client_id=client-id-gh" in loc
    assert "state=" in loc
    # Exactly one state key in Redis.
    assert len(fake_redis.store) == 1


def test_authorize_rejects_unknown_scope(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session)

    resp = client.get(
        "/api/auth/scm/github/authorize?scope=delete:repo",
        follow_redirects=False,
    )
    assert resp.status_code == 400
    body = resp.json()
    assert body["error"]["code"] == "SCOPE_NOT_ALLOWED"


def test_authorize_rejects_unknown_provider(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session)

    resp = client.get(
        "/api/auth/scm/gitlab/authorize?scope=public_repo",
        follow_redirects=False,
    )
    assert resp.status_code == 400
    assert resp.json()["error"]["code"] == "PROVIDER_NOT_SUPPORTED"


def test_authorize_without_admin_is_denied(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session, admin=False)

    resp = client.get(
        "/api/auth/scm/github/authorize?scope=public_repo",
        follow_redirects=False,
    )
    assert resp.status_code == 403


# ---------------------------------------------------------------------------
# callback — happy path
# ---------------------------------------------------------------------------


def _mock_github_http():
    """Patch httpx.AsyncClient to answer GitHub token + /user with canned data."""
    token_resp = MagicMock()
    token_resp.raise_for_status = MagicMock()
    token_resp.json = MagicMock(return_value={
        "access_token": "ghp_fake_token",
        "scope": "public_repo read:user",
        "token_type": "bearer",
    })

    user_resp = MagicMock()
    user_resp.raise_for_status = MagicMock()
    user_resp.json = MagicMock(return_value={
        "id": 42,
        "login": "octocat",
        "email": "octo@example.com",
    })

    client_instance = MagicMock()
    client_instance.post = AsyncMock(return_value=token_resp)
    client_instance.get = AsyncMock(return_value=user_resp)
    client_instance.__aenter__ = AsyncMock(return_value=client_instance)
    client_instance.__aexit__ = AsyncMock(return_value=False)
    return client_instance


def test_callback_happy_path_inserts_row(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session)

    # 1. /authorize — captures state in Redis.
    resp1 = client.get(
        "/api/auth/scm/github/authorize?scope=public_repo",
        follow_redirects=False,
    )
    assert resp1.status_code == 302
    state_key = next(iter(fake_redis.store.keys()))
    state = state_key.split(":")[-1]

    # 2. /callback — mocked httpx for token exchange + user lookup.
    fake_http = _mock_github_http()
    with patch("ai_driven.api.auth.scm_oauth.httpx.AsyncClient", return_value=fake_http):
        resp2 = client.get(
            f"/api/auth/scm/github/callback?code=abc123&state={state}",
            follow_redirects=False,
        )

    assert resp2.status_code == 302, resp2.text
    assert resp2.headers["location"] == "/dashboard/settings/connections?connected=github"

    # Row was inserted with encrypted token.
    assert len(session.added) == 1
    row = session.added[0]
    assert row.provider == "github"
    assert row.scm_user_id == "42"
    assert row.scm_username == "octocat"
    assert row.scm_email == "octo@example.com"
    assert row.scope == "public_repo read:user"
    assert isinstance(row.access_token_enc, bytes)
    # Encrypted — not the plaintext.
    assert b"ghp_fake_token" not in row.access_token_enc
    # Round-trip decrypt works with the configured key.
    assert scm_tokens.decrypt_token(row.access_token_enc) == "ghp_fake_token"


def test_callback_state_is_single_use(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session)

    resp1 = client.get(
        "/api/auth/scm/github/authorize?scope=public_repo",
        follow_redirects=False,
    )
    assert resp1.status_code == 302
    state = next(iter(fake_redis.store.keys())).split(":")[-1]

    # First callback consumes the state.
    fake_http = _mock_github_http()
    with patch("ai_driven.api.auth.scm_oauth.httpx.AsyncClient", return_value=fake_http):
        first = client.get(
            f"/api/auth/scm/github/callback?code=abc&state={state}",
            follow_redirects=False,
        )
    assert first.status_code == 302

    # Second callback with the same state → 400 OAUTH_INVALID_STATE.
    second = client.get(
        f"/api/auth/scm/github/callback?code=abc&state={state}",
        follow_redirects=False,
    )
    assert second.status_code == 302
    assert "error=OAUTH_INVALID_STATE" in second.headers["location"]
    assert second.headers["location"].startswith("/dashboard/settings/connections")


def test_callback_unknown_state_redirects_with_error(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session)

    resp = client.get(
        "/api/auth/scm/github/callback?code=abc&state=never-existed",
        follow_redirects=False,
    )
    assert resp.status_code == 302
    assert "error=OAUTH_INVALID_STATE" in resp.headers["location"]


def test_callback_missing_code_redirects_with_error(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session)

    resp = client.get(
        "/api/auth/scm/github/callback?state=anything",
        follow_redirects=False,
    )
    assert resp.status_code == 302
    assert "error=OAUTH_INVALID_REQUEST" in resp.headers["location"]


def test_callback_token_exchange_failure_redirects_with_error(fake_redis, fernet_key):
    session = _make_session()
    client = _make_app(session)

    resp1 = client.get(
        "/api/auth/scm/github/authorize?scope=public_repo",
        follow_redirects=False,
    )
    state = next(iter(fake_redis.store.keys())).split(":")[-1]

    # Mock httpx so token exchange raises.
    fake_http = _mock_github_http()
    fake_http.post = AsyncMock(side_effect=httpx.HTTPError("boom"))
    with patch("ai_driven.api.auth.scm_oauth.httpx.AsyncClient", return_value=fake_http):
        resp = client.get(
            f"/api/auth/scm/github/callback?code=abc&state={state}",
            follow_redirects=False,
        )

    assert resp.status_code == 302
    assert "error=OAUTH_TOKEN_EXCHANGE_FAILED" in resp.headers["location"]
