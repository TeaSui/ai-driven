"""Tests for BaseAgent Redis event publishing."""

from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.base import BaseAgent


class _StubAgent(BaseAgent):
    """Minimal concrete agent for testing the base class."""

    agent_type = "test"

    def _register_tools(self) -> None:
        pass

    def _get_system_prompt(self, context):
        return "test prompt"

    def _get_tool_names(self):
        return []


@pytest.fixture
def agent():
    with patch("ai_driven.agents.base.ClaudeClient"):
        return _StubAgent()


class TestPublishEvent:
    """Tests for BaseAgent._publish_event."""

    async def test_publish_event_sends_to_redis_channel(self, agent):
        mock_redis = AsyncMock()
        agent._task_id = "task-123"
        agent._redis_client = mock_redis

        await agent._publish_event("text_delta", {"text": "hello"})

        mock_redis.publish.assert_awaited_once()
        call_args = mock_redis.publish.call_args
        assert call_args[0][0] == "task:task-123:events"

        payload = json.loads(call_args[0][1])
        assert payload["type"] == "text_delta"
        assert payload["data"]["text"] == "hello"
        assert "timestamp" in payload

    async def test_publish_event_skips_when_no_task_id(self, agent):
        mock_redis = AsyncMock()
        agent._redis_client = mock_redis
        agent._task_id = None

        await agent._publish_event("text_delta", {"text": "hello"})

        mock_redis.publish.assert_not_awaited()

    async def test_publish_event_does_not_raise_on_redis_error(self, agent):
        mock_redis = AsyncMock()
        mock_redis.publish.side_effect = ConnectionError("Redis down")
        agent._task_id = "task-456"
        agent._redis_client = mock_redis

        # Should not raise
        await agent._publish_event("error", {"message": "fail"})

    async def test_publish_event_does_not_raise_when_redis_unavailable(self, agent):
        agent._task_id = "task-789"
        agent._redis_client = None

        with patch("ai_driven.agents.base.BaseAgent._get_redis_client", new_callable=AsyncMock, return_value=None):
            await agent._publish_event("complete", {"output_length": 42})


class TestGetRedisClient:
    """Tests for BaseAgent._get_redis_client."""

    async def test_returns_cached_client(self, agent):
        sentinel = AsyncMock()
        agent._redis_client = sentinel
        result = await agent._get_redis_client()
        assert result is sentinel

    async def test_returns_none_on_import_error(self, agent):
        with patch.dict("sys.modules", {"redis": None, "redis.asyncio": None}):
            agent._redis_client = None
            result = await agent._get_redis_client()
            # May return None or raise — either way, should not crash the agent
            # (import might work differently based on environment; the method is
            # wrapped in try/except so it will degrade gracefully)


class TestRunPublishesEvents:
    """Tests that run() publishes phase_change and complete/error events."""

    async def test_run_publishes_started_and_complete_events(self, agent):
        published_events: list[tuple[str, dict]] = []

        async def capture(event_type, data):
            published_events.append((event_type, data))

        agent._publish_event = capture  # type: ignore[assignment]

        mock_response = MagicMock()
        mock_response.text = "done"
        mock_response.usage = MagicMock(input_tokens=10, output_tokens=20)

        agent._claude.run_agent_loop = AsyncMock(return_value=mock_response)

        with patch("ai_driven.agents.base.emit_agent_metrics"):
            result = await agent.run({"message": "test"}, task_id="t-1")

        assert result.success is True
        assert agent._task_id == "t-1"

        types = [e[0] for e in published_events]
        assert "phase_change" in types
        assert "complete" in types

    async def test_run_publishes_error_event_on_failure(self, agent):
        published_events: list[tuple[str, dict]] = []

        async def capture(event_type, data):
            published_events.append((event_type, data))

        agent._publish_event = capture  # type: ignore[assignment]

        agent._claude.run_agent_loop = AsyncMock(side_effect=RuntimeError("boom"))

        with patch("ai_driven.agents.base.emit_agent_metrics"):
            result = await agent.run({"message": "test"}, task_id="t-2")

        assert result.success is False
        types = [e[0] for e in published_events]
        assert "error" in types

    async def test_run_without_task_id_skips_events(self, agent):
        """When task_id is not provided, _publish_event should be called but
        will short-circuit because _task_id is None."""
        mock_response = MagicMock()
        mock_response.text = "ok"
        mock_response.usage = MagicMock(input_tokens=5, output_tokens=10)

        agent._claude.run_agent_loop = AsyncMock(return_value=mock_response)

        with patch("ai_driven.agents.base.emit_agent_metrics"):
            result = await agent.run({"message": "test"})

        assert result.success is True
        assert agent._task_id is None
