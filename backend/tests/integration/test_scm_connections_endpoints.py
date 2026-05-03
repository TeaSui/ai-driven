"""Integration tests for /api/admin/me/connections endpoints.

Covers:
- GET returns a list without tokens + require_admin enforcement
- DELETE calls the provider revoke endpoint (GitHub) then deletes the row
- POST /test calls GET /user on the provider and surfaces ok + scmUsername
"""

from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, MagicMock, patch

from cryptography.fernet import Fernet
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import SecretStr

from ai_driven.auth import scm_tokens
from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.config import get_settings
from ai_driven.db.postgres.session import get_session


SYSTEM_MEMBER = "22222222-2222-2222-2222-222222222222"
SYSTEM_TEAM = "11111111-1111-1111-1111-111111111111"


def _setup_fernet(monkeypatch):
    key = Fernet.generate_key().decode()
    get_settings.cache_clear()
    settings = get_settings()
    monkeypatch.setattr(settings, "scm_token_key", key, raising=False)
    monkeypatch.setattr(settings, "scm_token_key_prior_keys", [], raising=False)
    monkeypatch.setattr(settings, "scm_token_key_secret_arn", "", raising=False)
    monkeypatch.setattr(settings, "github_oauth_client_id", "cid", raising=False)
    monkeypatch.setattr(settings, "github_oauth_client_secret", SecretStr("csec"), raising=False)
    scm_tokens.reset_key_cache()


def _make_row(provider="github", scm_username="octocat"):
    """Build a mock UserScmConnection row with an encrypted token."""
    row = MagicMock()
    row.id = uuid.uuid4()
    row.provider = provider
    row.scm_username = scm_username
    row.scm_email = f"{scm_username}@example.com"
    row.scope = "public_repo read:user"
    row.expires_at = None
    row.connected_at = None  # serialization allows None → ""
    row.last_used_at = None
    # Encrypted token.
    row.access_token_enc = scm_tokens.encrypt_token("ghp_xyz")
    row.refresh_token_enc = None
    return row


def _make_app(session, *, admin=True, member_id=SYSTEM_MEMBER):
    from ai_driven.api.admin.connections import router as conn_router

    app = FastAPI()
    app.include_router(conn_router, prefix="/api/admin")

    async def _fake_session():
        yield session
    app.dependency_overrides[get_session] = _fake_session

    if admin:
        def _admin():
            return TeamMemberInfo(member_id=member_id, team_id=SYSTEM_TEAM, role="admin")
        app.dependency_overrides[require_admin] = _admin
    else:
        from fastapi import HTTPException

        def _deny():
            raise HTTPException(status_code=403, detail="Admin role required")
        app.dependency_overrides[require_admin] = _deny
    return TestClient(app)


# ---------------------------------------------------------------------------
# GET
# ---------------------------------------------------------------------------


def test_list_returns_rows_without_tokens(monkeypatch):
    _setup_fernet(monkeypatch)
    row = _make_row()
    from datetime import datetime, timezone
    row.connected_at = datetime(2026, 5, 1, tzinfo=timezone.utc)

    session = MagicMock()
    scalar = MagicMock()
    scalar.scalars = MagicMock(return_value=MagicMock(all=MagicMock(return_value=[row])))
    session.execute = AsyncMock(return_value=scalar)
    session.commit = AsyncMock()

    client = _make_app(session)
    resp = client.get("/api/admin/me/connections")
    assert resp.status_code == 200, resp.text
    items = resp.json()
    assert len(items) == 1
    item = items[0]
    assert item["provider"] == "github"
    assert item["scmUsername"] == "octocat"
    assert item["scope"] == "public_repo read:user"
    # No token field in the response shape.
    assert "accessToken" not in item
    assert "accessTokenEnc" not in item
    assert "token" not in item


def test_list_requires_admin(monkeypatch):
    _setup_fernet(monkeypatch)
    session = MagicMock()
    client = _make_app(session, admin=False)
    resp = client.get("/api/admin/me/connections")
    assert resp.status_code == 403


# ---------------------------------------------------------------------------
# DELETE
# ---------------------------------------------------------------------------


def test_delete_calls_provider_revoke_then_deletes_row(monkeypatch):
    _setup_fernet(monkeypatch)
    row = _make_row()
    session = MagicMock()
    # First execute: SELECT returns the row.
    select_result = MagicMock()
    select_result.scalar_one_or_none = MagicMock(return_value=row)
    # Second execute: DELETE — ignore result.
    delete_result = MagicMock()
    session.execute = AsyncMock(side_effect=[select_result, delete_result])
    session.commit = AsyncMock()

    # Mock the GitHub revoke HTTP call.
    revoke_resp = MagicMock()
    revoke_resp.status_code = 204
    revoke_resp.text = ""
    fake_http = MagicMock()
    fake_http.request = AsyncMock(return_value=revoke_resp)
    fake_http.__aenter__ = AsyncMock(return_value=fake_http)
    fake_http.__aexit__ = AsyncMock(return_value=False)

    with patch("ai_driven.api.admin.connections.httpx.AsyncClient", return_value=fake_http):
        client = _make_app(session)
        resp = client.delete("/api/admin/me/connections/github")

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["data"] == {"provider": "github", "deleted": True}

    # Revoke HTTP call was made.
    assert fake_http.request.called
    call_args = fake_http.request.call_args
    assert call_args[0][0] == "DELETE"
    assert "applications/cid/token" in call_args[0][1]

    # DB delete + commit happened.
    assert session.commit.called


def test_delete_unknown_provider(monkeypatch):
    _setup_fernet(monkeypatch)
    session = MagicMock()
    client = _make_app(session)
    resp = client.delete("/api/admin/me/connections/gitlab")
    assert resp.status_code == 400


def test_delete_no_connection(monkeypatch):
    _setup_fernet(monkeypatch)
    session = MagicMock()
    select_result = MagicMock()
    select_result.scalar_one_or_none = MagicMock(return_value=None)
    session.execute = AsyncMock(return_value=select_result)
    session.commit = AsyncMock()

    client = _make_app(session)
    resp = client.delete("/api/admin/me/connections/github")
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "CONNECTION_NOT_FOUND"


# ---------------------------------------------------------------------------
# POST /test
# ---------------------------------------------------------------------------


def test_test_endpoint_hits_github_user(monkeypatch):
    _setup_fernet(monkeypatch)
    row = _make_row()
    session = MagicMock()
    select_result = MagicMock()
    select_result.scalar_one_or_none = MagicMock(return_value=row)
    update_result = MagicMock()
    session.execute = AsyncMock(side_effect=[select_result, update_result])
    session.commit = AsyncMock()

    user_resp = MagicMock()
    user_resp.status_code = 200
    user_resp.json = MagicMock(return_value={"login": "octocat", "email": "octo@example.com"})
    fake_http = MagicMock()
    fake_http.get = AsyncMock(return_value=user_resp)
    fake_http.__aenter__ = AsyncMock(return_value=fake_http)
    fake_http.__aexit__ = AsyncMock(return_value=False)

    with patch("ai_driven.api.admin.connections.httpx.AsyncClient", return_value=fake_http):
        client = _make_app(session)
        resp = client.post("/api/admin/me/connections/github/test")

    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    assert data["ok"] is True
    assert data["scmUsername"] == "octocat"
    assert data["provider"] == "github"

    # /user was called.
    assert fake_http.get.called
    url = fake_http.get.call_args[0][0]
    assert url == "https://api.github.com/user"


def test_test_endpoint_reports_revoked(monkeypatch):
    _setup_fernet(monkeypatch)
    row = _make_row()
    session = MagicMock()
    select_result = MagicMock()
    select_result.scalar_one_or_none = MagicMock(return_value=row)
    session.execute = AsyncMock(return_value=select_result)
    session.commit = AsyncMock()

    user_resp = MagicMock()
    user_resp.status_code = 401
    fake_http = MagicMock()
    fake_http.get = AsyncMock(return_value=user_resp)
    fake_http.__aenter__ = AsyncMock(return_value=fake_http)
    fake_http.__aexit__ = AsyncMock(return_value=False)

    with patch("ai_driven.api.admin.connections.httpx.AsyncClient", return_value=fake_http):
        client = _make_app(session)
        resp = client.post("/api/admin/me/connections/github/test")

    assert resp.status_code == 400
    data = resp.json()["data"]
    assert data["ok"] is False
    assert data["reason"] == "revoked"


def test_test_endpoint_requires_admin(monkeypatch):
    _setup_fernet(monkeypatch)
    session = MagicMock()
    client = _make_app(session, admin=False)
    resp = client.post("/api/admin/me/connections/github/test")
    assert resp.status_code == 403


def test_delete_endpoint_requires_admin(monkeypatch):
    _setup_fernet(monkeypatch)
    session = MagicMock()
    client = _make_app(session, admin=False)
    resp = client.delete("/api/admin/me/connections/github")
    assert resp.status_code == 403
