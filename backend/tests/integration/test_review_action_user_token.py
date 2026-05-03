"""Integration tests for /api/admin/reviews/{id}/approve (PR 3).

Covers the refactor from bot-token → caller's OAuth connection:

- happy path: connection present, SCM mock invoked with decrypted user
  token (verified via mock inspection); ``last_used_at`` updated.
- 412 SCM_NOT_CONNECTED when no connection row exists for the caller.
- 412 SCM_RECONNECT_REQUIRED when the connection is expired AND has no
  refresh token (refresh path stubbed in PR 3 — see
  ``_apply_review_action`` TODO).
- 412 SCM_SCOPE_UPGRADE_REQUIRED when SCM raises 404 and the caller
  has only ``public_repo`` scope — maps to a scope-upgrade CTA.
- cross-team 404 for reviews owned by another team.

All tests stub the DB session via ``AsyncMock(side_effect=...)`` so
the ordering is:

  1. SELECT Review  (from _resolve_review_scm_target)
  2. SELECT scm_type from Repository (optional — depends on task linkage)
  3. SELECT UserScmConnection (new in PR 3)
  4. UPDATE Review.status (on success only)
  5. UPDATE UserScmConnection.last_used_at (on success only)
"""

from __future__ import annotations

import uuid as _uuid
from datetime import datetime, timedelta, timezone
from unittest.mock import AsyncMock, MagicMock, patch

from cryptography.fernet import Fernet
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import SecretStr

from ai_driven.auth import scm_tokens
from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.config import get_settings
from ai_driven.db.postgres.session import get_session


# Caller info — uses valid UUIDs because _apply_review_action now parses
# member.member_id as a UUID to look up UserScmConnection rows.
CALLER_MEMBER_ID = "22222222-2222-2222-2222-222222222222"
CALLER_TEAM_ID = "11111111-1111-1111-1111-111111111111"
OTHER_TEAM_ID = "33333333-3333-3333-3333-333333333333"


def _setup_fernet(monkeypatch):
    """Wire a fresh Fernet key into settings so encrypt/decrypt round-trips."""
    key = Fernet.generate_key().decode()
    get_settings.cache_clear()
    settings = get_settings()
    monkeypatch.setattr(settings, "scm_token_key", key, raising=False)
    monkeypatch.setattr(settings, "scm_token_key_prior_keys", [], raising=False)
    monkeypatch.setattr(settings, "scm_token_key_secret_arn", "", raising=False)
    monkeypatch.setattr(settings, "github_oauth_client_id", "cid", raising=False)
    monkeypatch.setattr(settings, "github_oauth_client_secret", SecretStr("csec"), raising=False)
    scm_tokens.reset_key_cache()


def _make_connection_row(
    *,
    scope: str = "public_repo read:user",
    expires_at: datetime | None = None,
    refresh_token_enc: bytes | None = None,
    access_token_plaintext: str = "ghp_user_abc",
):
    row = MagicMock()
    row.id = _uuid.uuid4()
    row.provider = "github"
    row.scope = scope
    row.expires_at = expires_at
    row.access_token_enc = scm_tokens.encrypt_token(access_token_plaintext)
    row.refresh_token_enc = refresh_token_enc
    row.scm_username = "octocat"
    return row


def _make_review_row(team_id: str = CALLER_TEAM_ID):
    """Return a mock Review row with a linked Task (team-scoped)."""
    review = MagicMock()
    review.id = _uuid.uuid4()
    review.repo_full_name = "acme/widgets"
    review.pr_number = 42
    review.comments_json = []
    review.status = "pending"
    task = MagicMock()
    task.team_id = _uuid.UUID(team_id)
    task.repo_id = None  # skip Repository lookup — default scm_type=github
    review.task = task
    return review


def _make_app(session, *, member_id=CALLER_MEMBER_ID, team_id=CALLER_TEAM_ID):
    """Build a minimal FastAPI app with just the admin router + overrides."""
    from ai_driven.api.admin.router import router as admin_router

    app = FastAPI()
    app.include_router(admin_router, prefix="/api/admin")

    async def _fake_session():
        yield session
    app.dependency_overrides[get_session] = _fake_session

    def _admin():
        return TeamMemberInfo(member_id=member_id, team_id=team_id, role="admin")
    app.dependency_overrides[require_admin] = _admin
    return TestClient(app)


def _wire_session(session, execute_side_effects, *, commit=True):
    """Assign AsyncMock side-effects to session.execute + commit."""
    session.execute = AsyncMock(side_effect=execute_side_effects)
    session.commit = AsyncMock() if commit else None


def _mk_select_result(value):
    """Mock for ``select(...).scalar_one_or_none()``."""
    res = MagicMock()
    res.scalar_one_or_none = MagicMock(return_value=value)
    return res


# ---------------------------------------------------------------------------
# Happy path: SCM approve invoked with decrypted user token, last_used_at set
# ---------------------------------------------------------------------------


def test_approve_uses_caller_user_token_and_updates_last_used(monkeypatch):
    _setup_fernet(monkeypatch)
    review = _make_review_row()
    conn = _make_connection_row(access_token_plaintext="ghp_user_123")

    session = MagicMock()
    # Order: select review → select conn → update review.status → update conn.last_used_at
    _wire_session(session, [
        _mk_select_result(review),
        _mk_select_result(conn),
        MagicMock(),  # UPDATE Review
        MagicMock(),  # UPDATE UserScmConnection
    ])

    # Mock the per-user SCM client so we can inspect the token it received.
    fake_client = MagicMock()
    fake_client.approve_pr = AsyncMock()
    fake_client.close = AsyncMock()

    captured: dict = {}
    def _factory(scm_type, creds):
        captured["scm_type"] = scm_type
        captured["creds"] = dict(creds)
        return fake_client

    with patch("ai_driven.scm.factory.get_scm_client", side_effect=_factory):
        client = _make_app(session)
        resp = client.post(
            f"/api/admin/reviews/{review.id}/approve",
            json={"comment": ""},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "approved"

    # The SCM client was built with the *decrypted* per-user token, NOT the
    # bot token from settings.
    assert captured["scm_type"] == "github"
    assert captured["creds"] == {"token": "ghp_user_123"}

    # approve_pr was called with the repo + PR number.
    fake_client.approve_pr.assert_awaited_once_with("acme/widgets", 42, "")

    # Both UPDATE statements fired, and we committed.
    assert session.execute.call_count == 4
    assert session.commit.called


# ---------------------------------------------------------------------------
# 412 SCM_NOT_CONNECTED — no row for caller
# ---------------------------------------------------------------------------


def test_approve_returns_412_when_no_connection(monkeypatch):
    _setup_fernet(monkeypatch)
    review = _make_review_row()

    session = MagicMock()
    _wire_session(session, [
        _mk_select_result(review),
        _mk_select_result(None),  # no UserScmConnection row
    ])

    client = _make_app(session)
    resp = client.post(f"/api/admin/reviews/{review.id}/approve", json={"comment": ""})

    assert resp.status_code == 412, resp.text
    body = resp.json()
    assert body["error"]["code"] == "SCM_NOT_CONNECTED"
    assert "github" in body["error"]["message"].lower()
    details = body["error"]["details"]
    assert details[0]["provider"] == "github"
    assert details[0]["connectUrl"].startswith("/api/auth/scm/github/authorize")
    # Should NOT have committed — no state change.
    assert not session.commit.called


# ---------------------------------------------------------------------------
# 412 SCM_RECONNECT_REQUIRED — expired, no refresh_token
# ---------------------------------------------------------------------------


def test_approve_returns_412_when_expired_no_refresh(monkeypatch):
    _setup_fernet(monkeypatch)
    review = _make_review_row()
    # Expired one hour ago, no refresh token.
    conn = _make_connection_row(
        expires_at=datetime.now(timezone.utc) - timedelta(hours=1),
        refresh_token_enc=None,
    )

    session = MagicMock()
    _wire_session(session, [
        _mk_select_result(review),
        _mk_select_result(conn),
    ])

    client = _make_app(session)
    resp = client.post(f"/api/admin/reviews/{review.id}/approve", json={"comment": ""})

    assert resp.status_code == 412, resp.text
    body = resp.json()
    assert body["error"]["code"] == "SCM_RECONNECT_REQUIRED"
    details = body["error"]["details"]
    assert details[0]["provider"] == "github"
    assert details[0]["reason"] == "no_refresh_supported"
    assert "connectUrl" in details[0]
    assert not session.commit.called


def test_approve_returns_412_when_expired_with_refresh_token_stubbed(monkeypatch):
    """PR 3 stubs the refresh path — even when refresh_token_enc is set,
    we return 412 with reason=refresh_expired so the user reconnects.
    The full refresh flow is tracked as a follow-up (TODO in the code).
    """
    _setup_fernet(monkeypatch)
    review = _make_review_row()
    conn = _make_connection_row(
        expires_at=datetime.now(timezone.utc) - timedelta(hours=1),
        refresh_token_enc=scm_tokens.encrypt_token("refresh_xyz"),
    )

    session = MagicMock()
    _wire_session(session, [
        _mk_select_result(review),
        _mk_select_result(conn),
    ])

    client = _make_app(session)
    resp = client.post(f"/api/admin/reviews/{review.id}/approve", json={"comment": ""})

    assert resp.status_code == 412, resp.text
    assert resp.json()["error"]["code"] == "SCM_RECONNECT_REQUIRED"
    assert resp.json()["error"]["details"][0]["reason"] == "refresh_expired"


# ---------------------------------------------------------------------------
# 412 SCM_SCOPE_UPGRADE_REQUIRED — SCM 404 + scope lacks 'repo'
# ---------------------------------------------------------------------------


def test_approve_returns_412_scope_upgrade_on_scm_404_with_public_repo(monkeypatch):
    _setup_fernet(monkeypatch)
    from ai_driven.scm.exceptions import SCMNotFoundError

    review = _make_review_row()
    conn = _make_connection_row(scope="public_repo read:user")  # no 'repo'

    session = MagicMock()
    _wire_session(session, [
        _mk_select_result(review),
        _mk_select_result(conn),
    ])

    fake_client = MagicMock()
    fake_client.approve_pr = AsyncMock(side_effect=SCMNotFoundError("pr not found"))
    fake_client.close = AsyncMock()

    with patch("ai_driven.scm.factory.get_scm_client", return_value=fake_client):
        client = _make_app(session)
        resp = client.post(f"/api/admin/reviews/{review.id}/approve", json={"comment": ""})

    assert resp.status_code == 412, resp.text
    body = resp.json()
    assert body["error"]["code"] == "SCM_SCOPE_UPGRADE_REQUIRED"
    details = body["error"]["details"][0]
    assert details["currentScope"] == "public_repo read:user"
    assert details["scopeNeeded"] == "repo"
    assert details["connectUrl"].endswith("scope=repo")
    # close() was still called.
    fake_client.close.assert_awaited()


def test_approve_returns_502_on_scm_404_when_scope_already_repo(monkeypatch):
    """If the token already has ``repo`` scope, a 404 is a genuine missing
    resource — not a scope problem. Surface as 502 (existing behavior).
    """
    _setup_fernet(monkeypatch)
    from ai_driven.scm.exceptions import SCMNotFoundError

    review = _make_review_row()
    conn = _make_connection_row(scope="repo read:user")

    session = MagicMock()
    _wire_session(session, [
        _mk_select_result(review),
        _mk_select_result(conn),
    ])

    fake_client = MagicMock()
    fake_client.approve_pr = AsyncMock(side_effect=SCMNotFoundError("pr not found"))
    fake_client.close = AsyncMock()

    with patch("ai_driven.scm.factory.get_scm_client", return_value=fake_client):
        client = _make_app(session)
        resp = client.post(f"/api/admin/reviews/{review.id}/approve", json={"comment": ""})

    assert resp.status_code == 502, resp.text


# ---------------------------------------------------------------------------
# Cross-team 404
# ---------------------------------------------------------------------------


def test_approve_cross_team_returns_404(monkeypatch):
    _setup_fernet(monkeypatch)
    # Review belongs to OTHER_TEAM but caller is on CALLER_TEAM.
    review = _make_review_row(team_id=OTHER_TEAM_ID)

    session = MagicMock()
    _wire_session(session, [
        _mk_select_result(review),
    ])

    client = _make_app(session)
    resp = client.post(f"/api/admin/reviews/{review.id}/approve", json={"comment": ""})

    assert resp.status_code == 404, resp.text


# ---------------------------------------------------------------------------
# request-changes mirrors approve for the 412 path
# ---------------------------------------------------------------------------


def test_request_changes_returns_412_when_no_connection(monkeypatch):
    _setup_fernet(monkeypatch)
    review = _make_review_row()

    session = MagicMock()
    _wire_session(session, [
        _mk_select_result(review),
        _mk_select_result(None),
    ])

    client = _make_app(session)
    resp = client.post(
        f"/api/admin/reviews/{review.id}/request-changes",
        json={"comment": "please fix"},
    )

    assert resp.status_code == 412, resp.text
    assert resp.json()["error"]["code"] == "SCM_NOT_CONNECTED"


def test_request_changes_happy_path_uses_user_token(monkeypatch):
    _setup_fernet(monkeypatch)
    review = _make_review_row()
    conn = _make_connection_row(access_token_plaintext="ghp_rc_456")

    session = MagicMock()
    _wire_session(session, [
        _mk_select_result(review),
        _mk_select_result(conn),
        MagicMock(),  # UPDATE Review
        MagicMock(),  # UPDATE conn
    ])

    fake_client = MagicMock()
    fake_client.request_changes = AsyncMock()
    fake_client.close = AsyncMock()

    captured: dict = {}
    def _factory(scm_type, creds):
        captured["creds"] = dict(creds)
        return fake_client

    with patch("ai_driven.scm.factory.get_scm_client", side_effect=_factory):
        client = _make_app(session)
        resp = client.post(
            f"/api/admin/reviews/{review.id}/request-changes",
            json={"comment": "please fix"},
        )

    assert resp.status_code == 200, resp.text
    assert resp.json()["status"] == "changes_requested"
    assert captured["creds"] == {"token": "ghp_rc_456"}
    fake_client.request_changes.assert_awaited_once_with("acme/widgets", 42, "please fix")
