"""Tests for CircuitBreaker and ResilientProvider (Task 6.9)."""

from __future__ import annotations

import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.claude.provider import CircuitBreaker, ResilientProvider


class TestCircuitBreaker:
    def test_starts_closed(self) -> None:
        cb = CircuitBreaker()
        assert cb.is_open is False

    def test_opens_after_threshold(self) -> None:
        cb = CircuitBreaker(failure_threshold=3)
        cb.record_failure()
        cb.record_failure()
        assert cb.is_open is False
        cb.record_failure()
        assert cb.is_open is True

    def test_success_resets_count(self) -> None:
        cb = CircuitBreaker(failure_threshold=3)
        cb.record_failure()
        cb.record_failure()
        cb.record_success()
        cb.record_failure()
        assert cb.is_open is False

    def test_recovers_after_timeout(self) -> None:
        cb = CircuitBreaker(failure_threshold=1, recovery_timeout=0.01)
        cb.record_failure()
        assert cb.is_open is True
        time.sleep(0.02)
        # Should transition to half_open
        assert cb.is_open is False


class TestResilientProvider:
    @pytest.mark.asyncio
    async def test_success_passes_through(self) -> None:
        mock_provider = MagicMock()
        mock_response = MagicMock()
        mock_provider.create_message = AsyncMock(return_value=mock_response)

        resilient = ResilientProvider(mock_provider)
        result = await resilient.create_message(
            model="claude-sonnet-4-6", max_tokens=100, messages=[]
        )
        assert result == mock_response

    @pytest.mark.asyncio
    async def test_fallback_on_error(self) -> None:
        mock_provider = MagicMock()
        mock_response = MagicMock()
        # First call fails, second (fallback) succeeds
        mock_provider.create_message = AsyncMock(
            side_effect=[Exception("API down"), mock_response]
        )

        with patch("ai_driven.claude.provider.get_settings") as mock_settings:
            mock_settings.return_value.claude_planning_model = "claude-opus-4-7"
            mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
            mock_settings.return_value.claude_fast_model = "claude-haiku-4-5"

            resilient = ResilientProvider(mock_provider)
            result = await resilient.create_message(
                model="claude-opus-4-7", max_tokens=100, messages=[]
            )
            assert result == mock_response
            # Second call should use fallback model
            assert mock_provider.create_message.call_count == 2
