"""Tests for health check endpoint (Task 6.9)."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.api.health import HealthResponse, _check_claude, _check_redis


class TestHealthChecks:
    @pytest.mark.asyncio
    async def test_check_redis_not_configured(self) -> None:
        mock_settings = MagicMock()
        mock_settings.redis_url = ""
        with patch("ai_driven.api.health.get_settings", return_value=mock_settings):
            result = await _check_redis()
            assert result.name == "redis"
            assert result.status == "degraded"

    @pytest.mark.asyncio
    async def test_check_claude_no_key(self) -> None:
        mock_settings = MagicMock()
        mock_settings.anthropic_api_key = ""
        mock_settings.claude_provider.value = "anthropic"
        with patch("ai_driven.api.health.get_settings", return_value=mock_settings):
            result = await _check_claude()
            assert result.name == "claude"
            assert result.status == "degraded"

    @pytest.mark.asyncio
    async def test_check_claude_with_key(self) -> None:
        mock_settings = MagicMock()
        mock_settings.anthropic_api_key = "sk-test"
        mock_settings.claude_provider.value = "anthropic"
        with patch("ai_driven.api.health.get_settings", return_value=mock_settings):
            result = await _check_claude()
            assert result.status == "healthy"
