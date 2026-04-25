"""Unit tests for /api/auth/session scaffolding (Sprint 9 Phase 2)."""

from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import HTTPException

from ai_driven.api.auth.router import (
    SessionResponse,
    _resolve_identity,
    verify_cognito_jwt,
)


class TestVerifyCognitoJwtNotYetImplemented:
    def test_raises_not_implemented_until_pool_is_provisioned(self) -> None:
        with pytest.raises(NotImplementedError) as exc:
            verify_cognito_jwt("anything")
        assert "Cognito" in str(exc.value)


class TestResolveIdentityDebugMode:
    def test_debug_mode_returns_sentinel_session(self) -> None:
        request = MagicMock()
        request.headers.get.return_value = None
        request.cookies.get.return_value = None

        session = MagicMock()
        fake_settings = MagicMock()
        fake_settings.debug = True

        with patch(
            "ai_driven.config.get_settings", return_value=fake_settings
        ):
            result = asyncio.run(_resolve_identity(request, session))

        assert isinstance(result, SessionResponse)
        assert result.auth_source == "debug"
        assert result.team_name == "Debug Team"
        assert result.role == "owner"


class TestResolveIdentityNoAuth:
    def test_raises_401_when_no_api_key_and_not_debug(self) -> None:
        request = MagicMock()
        request.headers.get.return_value = None
        request.cookies.get.return_value = None

        session = MagicMock()
        fake_settings = MagicMock()
        fake_settings.debug = False

        with patch(
            "ai_driven.config.get_settings", return_value=fake_settings
        ):
            with pytest.raises(HTTPException) as exc:
                asyncio.run(_resolve_identity(request, session))

        assert exc.value.status_code == 401


class TestResolveIdentityApiKey:
    def test_api_key_resolves_to_team_session(self) -> None:
        request = MagicMock()
        # Simulate X-API-Key header
        request.headers.get = MagicMock(return_value="test_key_abc")
        request.cookies.get.return_value = None

        # Team record
        team = MagicMock()
        team.id = "11111111-1111-1111-1111-111111111111"
        team.name = "Test Team"
        team_scalar = MagicMock()
        team_scalar.scalar_one_or_none.return_value = team

        # No specific member match — should fall back to team-level identity
        member_scalar = MagicMock()
        member_scalar.scalar_one_or_none.return_value = None

        session = MagicMock()
        session.execute = AsyncMock(side_effect=[team_scalar, member_scalar])

        fake_settings = MagicMock()
        fake_settings.debug = False

        with patch(
            "ai_driven.config.get_settings", return_value=fake_settings
        ), patch(
            "ai_driven.auth.middleware.hash_api_key",
            return_value="hash_of_test_key_abc",
        ):
            result = asyncio.run(_resolve_identity(request, session))

        assert result.auth_source == "api_key"
        assert result.team_id == team.id
        assert result.team_name == "Test Team"
        assert result.role == "owner"  # team-level fallback defaults to owner
