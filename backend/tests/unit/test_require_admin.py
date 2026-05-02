"""T24 unit tests for auth/middleware.require_admin.

Exercises the three auth paths (session token, X-API-Key, debug) and
the role guard (owner/admin pass; member/viewer get 403; no auth gets
401).
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import HTTPException


@pytest.fixture
def _fake_request(monkeypatch):
    def _build(state_kwargs: dict) -> SimpleNamespace:
        # FastAPI's Request exposes .state but mocking a real Request is
        # heavy. All we need is ``request.state.<attr>`` lookup.
        req = SimpleNamespace()
        req.state = SimpleNamespace(**state_kwargs)
        return req

    return _build


@pytest.mark.asyncio
async def test_require_admin_session_owner_passes(_fake_request):
    from ai_driven.auth.middleware import require_admin, TeamMemberInfo

    req = _fake_request({
        "team_id": "11111111-1111-1111-1111-111111111111",
        "team_role": "owner",
        "member_id": "22222222-2222-2222-2222-222222222222",
    })
    # Avoid the debug bypass branch (requires settings.debug=True AND
    # team_id == sentinel). Our team_id is non-sentinel.
    with patch("ai_driven.auth.middleware.get_settings") as gs:
        gs.return_value = SimpleNamespace(debug=False)
        info = await require_admin(req)

    assert isinstance(info, TeamMemberInfo)
    assert info.role == "owner"
    assert info.member_id == "22222222-2222-2222-2222-222222222222"


@pytest.mark.asyncio
async def test_require_admin_session_admin_passes(_fake_request):
    from ai_driven.auth.middleware import require_admin

    req = _fake_request({
        "team_id": "11111111-1111-1111-1111-111111111111",
        "team_role": "admin",
        "member_id": "22222222-2222-2222-2222-222222222222",
    })
    with patch("ai_driven.auth.middleware.get_settings") as gs:
        gs.return_value = SimpleNamespace(debug=False)
        info = await require_admin(req)
    assert info.role == "admin"


@pytest.mark.asyncio
async def test_require_admin_member_gets_403(_fake_request):
    from ai_driven.auth.middleware import require_admin

    req = _fake_request({
        "team_id": "11111111-1111-1111-1111-111111111111",
        "team_role": "member",
        "member_id": "22222222-2222-2222-2222-222222222222",
    })
    with patch("ai_driven.auth.middleware.get_settings") as gs:
        gs.return_value = SimpleNamespace(debug=False)
        with pytest.raises(HTTPException) as excinfo:
            await require_admin(req)
    assert excinfo.value.status_code == 403


@pytest.mark.asyncio
async def test_require_admin_viewer_gets_403(_fake_request):
    from ai_driven.auth.middleware import require_admin

    req = _fake_request({
        "team_id": "11111111-1111-1111-1111-111111111111",
        "team_role": "viewer",
        "member_id": "22222222-2222-2222-2222-222222222222",
    })
    with patch("ai_driven.auth.middleware.get_settings") as gs:
        gs.return_value = SimpleNamespace(debug=False)
        with pytest.raises(HTTPException) as excinfo:
            await require_admin(req)
    assert excinfo.value.status_code == 403


@pytest.mark.asyncio
async def test_require_admin_no_team_gets_401(_fake_request):
    from ai_driven.auth.middleware import require_admin

    req = _fake_request({"team_id": None})
    with pytest.raises(HTTPException) as excinfo:
        await require_admin(req)
    assert excinfo.value.status_code == 401


@pytest.mark.asyncio
async def test_require_admin_debug_sentinel_team_passes_as_admin(_fake_request):
    """Local dev ergonomics: debug mode + sentinel team id = system admin."""
    from ai_driven.auth.middleware import require_admin, SYSTEM_MEMBER_ID

    req = _fake_request({
        "team_id": "00000000-0000-0000-0000-000000000000",
    })
    with patch("ai_driven.auth.middleware.get_settings") as gs:
        gs.return_value = SimpleNamespace(debug=True)
        info = await require_admin(req)
    assert info.role == "admin"
    assert info.member_id == SYSTEM_MEMBER_ID


@pytest.mark.asyncio
async def test_require_admin_apikey_team_level_owner_passes(_fake_request):
    """X-API-Key path, team-level key (no matching member row) → treated
    as owner and audited under the system member."""
    from ai_driven.auth.middleware import require_admin, SYSTEM_MEMBER_ID

    req = _fake_request({
        "team_id": "11111111-1111-1111-1111-111111111111",
        "team_role": "owner",
        "api_key_hash": "abc123",
    })

    # No member row matches the hash.
    session = MagicMock()
    session.execute = AsyncMock(
        return_value=MagicMock(scalar_one_or_none=MagicMock(return_value=None))
    )
    fake_factory = MagicMock()
    fake_factory.return_value.__aenter__ = AsyncMock(return_value=session)
    fake_factory.return_value.__aexit__ = AsyncMock(return_value=None)

    with patch("ai_driven.auth.middleware.get_settings") as gs, \
         patch("ai_driven.db.postgres.session.async_session_factory", fake_factory):
        gs.return_value = SimpleNamespace(debug=False)
        info = await require_admin(req)
    assert info.role == "owner"
    assert info.member_id == SYSTEM_MEMBER_ID


@pytest.mark.asyncio
async def test_require_admin_apikey_member_with_member_role_403(_fake_request):
    """X-API-Key path, member row has role='member' → 403."""
    from ai_driven.auth.middleware import require_admin

    req = _fake_request({
        "team_id": "11111111-1111-1111-1111-111111111111",
        "team_role": "member",  # middleware default for non-session paths
        "api_key_hash": "abc123",
    })

    member_row = MagicMock()
    member_row.id = uuid.UUID("33333333-3333-3333-3333-333333333333")
    member_row.role = "member"
    member_row.email = "m@example.com"

    session = MagicMock()
    session.execute = AsyncMock(
        return_value=MagicMock(
            scalar_one_or_none=MagicMock(return_value=member_row)
        )
    )
    fake_factory = MagicMock()
    fake_factory.return_value.__aenter__ = AsyncMock(return_value=session)
    fake_factory.return_value.__aexit__ = AsyncMock(return_value=None)

    with patch("ai_driven.auth.middleware.get_settings") as gs, \
         patch("ai_driven.db.postgres.session.async_session_factory", fake_factory):
        gs.return_value = SimpleNamespace(debug=False)
        with pytest.raises(HTTPException) as excinfo:
            await require_admin(req)
    assert excinfo.value.status_code == 403
