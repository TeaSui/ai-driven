"""Unit tests for the shadow-mode orchestrator dispatch in TaskProducer.

Covers the fire-and-forget path that fires OrchestratorAgent.orchestrate()
alongside every coding-task enqueue. The orchestrator runs via
``asyncio.create_task`` — NOT via ``enqueue_task('orchestrator', ...)`` —
so the queue/inline-fallback path never sees the orchestrator agent_type.
"""
from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.config import get_settings


@pytest.fixture(autouse=True)
def _clear_settings_cache():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _make_producer():
    """Construct a TaskProducer with a stubbed boto3 SQS client.

    boto3.client is called in __init__; patching it avoids a real AWS
    credential lookup in the test environment.
    """
    from ai_driven.queue.producer import TaskProducer

    with patch("ai_driven.queue.producer.boto3.client", MagicMock()):
        return TaskProducer()


@pytest.mark.asyncio
async def test_enqueue_coding_schedules_shadow_orchestrator() -> None:
    """enqueue_task('coding', ...) must kick off OrchestratorAgent in background."""
    producer = _make_producer()
    task_id = "task-abc"
    payload = {"message": "fix typo", "context": {}}

    # Patch debug to force the inline-fallback path (keeps the test hermetic
    # — no real SQS interaction). The shadow-orchestrator branch runs BEFORE
    # the queue_url lookup, so it fires regardless of debug mode.
    with patch("ai_driven.queue.producer._process_task_inline" if False else
               "ai_driven.api.chat.router._process_task_inline",
               AsyncMock(return_value=None)), \
         patch("ai_driven.queue.producer.get_settings") as mock_get_settings:
        s = MagicMock()
        s.debug = True
        s.orchestrator_mode = "shadow"
        mock_get_settings.return_value = s

        orch_instance = MagicMock()
        orch_instance.orchestrate = AsyncMock(return_value={"tier": "fast_path"})

        with patch(
            "ai_driven.agents.orchestrator.agent.OrchestratorAgent",
            return_value=orch_instance,
        ) as mock_cls, patch(
            "asyncio.create_task"
        ) as mock_create_task:
            await producer.enqueue_task(task_id, "coding", payload)

            # OrchestratorAgent() was constructed exactly once for the shadow fan-out
            assert mock_cls.call_count == 1
            # asyncio.create_task was invoked at least once (once for the
            # shadow orchestrator, and possibly once for _process_task_inline).
            assert mock_create_task.call_count >= 1
            # At least one of the scheduled tasks must be the _run_shadow coroutine
            # wrapping the mocked orchestrator.
            scheduled = [c.args[0] for c in mock_create_task.call_args_list]
            shadow_names = [getattr(co, "__name__", "") for co in scheduled]
            assert "_run_shadow" in shadow_names


@pytest.mark.asyncio
async def test_enqueue_non_coding_does_not_schedule_orchestrator() -> None:
    """enqueue_task('review', ...) must NOT spawn the orchestrator."""
    producer = _make_producer()

    with patch("ai_driven.api.chat.router._process_task_inline",
               AsyncMock(return_value=None)), \
         patch("ai_driven.queue.producer.get_settings") as mock_get_settings:
        s = MagicMock()
        s.debug = True
        s.orchestrator_mode = "shadow"
        mock_get_settings.return_value = s

        with patch(
            "ai_driven.agents.orchestrator.agent.OrchestratorAgent",
        ) as mock_cls:
            await producer.enqueue_task("t-1", "review", {"message": "x"})
            mock_cls.assert_not_called()


@pytest.mark.asyncio
async def test_orchestrator_mode_off_disables_scheduling() -> None:
    """orchestrator_mode='off' must disable shadow scheduling even for coding."""
    producer = _make_producer()

    with patch("ai_driven.api.chat.router._process_task_inline",
               AsyncMock(return_value=None)), \
         patch("ai_driven.queue.producer.get_settings") as mock_get_settings:
        s = MagicMock()
        s.debug = True
        s.orchestrator_mode = "off"
        mock_get_settings.return_value = s

        with patch(
            "ai_driven.agents.orchestrator.agent.OrchestratorAgent",
        ) as mock_cls:
            await producer.enqueue_task("t-2", "coding", {"message": "x"})
            mock_cls.assert_not_called()


@pytest.mark.asyncio
async def test_shadow_orchestrator_failure_does_not_break_enqueue() -> None:
    """If OrchestratorAgent raises on construction, enqueue_task still succeeds."""
    producer = _make_producer()

    with patch("ai_driven.api.chat.router._process_task_inline",
               AsyncMock(return_value=None)), \
         patch("ai_driven.queue.producer.get_settings") as mock_get_settings:
        s = MagicMock()
        s.debug = True
        s.orchestrator_mode = "shadow"
        mock_get_settings.return_value = s

        with patch(
            "ai_driven.agents.orchestrator.agent.OrchestratorAgent",
            side_effect=RuntimeError("boom"),
        ):
            # Must not raise — exception is logged and swallowed.
            msg_id = await producer.enqueue_task("t-3", "coding", {"message": "x"})
            assert msg_id.startswith("inline-")
