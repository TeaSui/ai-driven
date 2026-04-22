"""Tests for RBAC: TeamRole enum, TeamMember model, and require_role dependency."""

from __future__ import annotations

import uuid
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from ai_driven.domain.enums import TeamRole


# ── TeamRole enum tests ──────────────────────────────────────────────────────


class TestTeamRole:
    def test_values(self):
        assert TeamRole.OWNER.value == "owner"
        assert TeamRole.ADMIN.value == "admin"
        assert TeamRole.MEMBER.value == "member"
        assert TeamRole.VIEWER.value == "viewer"

    def test_is_str_enum(self):
        assert isinstance(TeamRole.OWNER, str)
        assert TeamRole.OWNER == "owner"

    def test_normalize_valid_values(self):
        assert TeamRole.normalize("owner") is TeamRole.OWNER
        assert TeamRole.normalize("ADMIN") is TeamRole.ADMIN
        assert TeamRole.normalize(" Member ") is TeamRole.MEMBER
        assert TeamRole.normalize("VIEWER") is TeamRole.VIEWER

    def test_normalize_none(self):
        assert TeamRole.normalize(None) is None

    def test_normalize_invalid(self):
        assert TeamRole.normalize("superadmin") is None
        assert TeamRole.normalize("") is None

    def test_all_roles_count(self):
        assert len(TeamRole) == 4


# ── TeamMember model tests ───────────────────────────────────────────────────


class TestTeamMemberModel:
    def test_tablename(self):
        from ai_driven.db.postgres.models import TeamMember

        assert TeamMember.__tablename__ == "team_members"

    def test_columns_exist(self):
        from ai_driven.db.postgres.models import TeamMember

        column_names = {c.name for c in TeamMember.__table__.columns}
        expected = {"id", "team_id", "email", "role", "api_key_hash", "is_active", "created_at"}
        assert expected == column_names

    def test_role_default(self):
        from ai_driven.db.postgres.models import TeamMember

        role_col = TeamMember.__table__.columns["role"]
        assert role_col.default.arg == "member"

    def test_email_not_nullable(self):
        from ai_driven.db.postgres.models import TeamMember

        email_col = TeamMember.__table__.columns["email"]
        assert email_col.nullable is False

    def test_api_key_hash_nullable(self):
        from ai_driven.db.postgres.models import TeamMember

        col = TeamMember.__table__.columns["api_key_hash"]
        assert col.nullable is True

    def test_team_id_has_foreign_key(self):
        from ai_driven.db.postgres.models import TeamMember

        col = TeamMember.__table__.columns["team_id"]
        fk_targets = {fk.target_fullname for fk in col.foreign_keys}
        assert "teams.id" in fk_targets


# ── require_role dependency tests ────────────────────────────────────────────


class TestRequireRole:
    def test_invalid_role_raises_value_error(self):
        from ai_driven.dependencies import require_role

        with pytest.raises(ValueError, match="Invalid role"):
            require_role("superadmin")

    def test_valid_roles_accepted(self):
        from ai_driven.dependencies import require_role

        # Should not raise
        dep = require_role("owner", "admin")
        assert dep is not None

    @pytest.mark.asyncio
    async def test_allowed_role_passes(self):
        from ai_driven.dependencies import require_role

        dep = require_role("owner", "admin")
        # Extract the actual callable from the Depends wrapper
        check_fn = dep.dependency

        request = MagicMock()
        request.state = SimpleNamespace(team_role="owner")
        # Should not raise
        await check_fn(request)

    @pytest.mark.asyncio
    async def test_disallowed_role_raises_403(self):
        from fastapi import HTTPException

        from ai_driven.dependencies import require_role

        dep = require_role("owner")
        check_fn = dep.dependency

        request = MagicMock()
        request.state = SimpleNamespace(team_role="viewer")
        with pytest.raises(HTTPException) as exc_info:
            await check_fn(request)
        assert exc_info.value.status_code == 403
        assert "Insufficient permissions" in str(exc_info.value.detail)

    @pytest.mark.asyncio
    async def test_missing_role_defaults_to_member(self):
        from fastapi import HTTPException

        from ai_driven.dependencies import require_role

        dep = require_role("owner")
        check_fn = dep.dependency

        # No team_role on request.state
        request = MagicMock()
        request.state = SimpleNamespace()
        with pytest.raises(HTTPException) as exc_info:
            await check_fn(request)
        assert exc_info.value.status_code == 403

    @pytest.mark.asyncio
    async def test_member_default_passes_when_member_allowed(self):
        from ai_driven.dependencies import require_role

        dep = require_role("member")
        check_fn = dep.dependency

        request = MagicMock()
        request.state = SimpleNamespace()  # no team_role -> defaults to "member"
        # Should not raise
        await check_fn(request)
