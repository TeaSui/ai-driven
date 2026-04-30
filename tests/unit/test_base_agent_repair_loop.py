"""Unit tests for BaseAgent._repair_or_escalate_json (lifted from reviewer).

The helper is a general-purpose two-round JSON-repair loop usable by any
BaseAgent subclass. It:
  * short-circuits when the validator accepts the current output,
  * otherwise retries up to MAX_JSON_REPAIR_ROUNDS (2) with a schema reminder,
  * on persistent failure, flags result.metadata['escalated'] and emits an event,
  * NEVER writes task.status — escalation is signal-only so the helper
    remains side-effect-free for specialists running in shadow mode.
"""
from __future__ import annotations

import json
import re
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.base import BaseAgent


class _StubAgent(BaseAgent):
    """Minimal BaseAgent subclass for exercising the helper in isolation."""

    agent_type = "stub"

    def _register_tools(self) -> None:
        return None

    def _get_system_prompt(self, context):
        return "you are a stub"

    def _get_tool_names(self):
        return []


def _make_agent() -> _StubAgent:
    agent = _StubAgent()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._update_task_status = AsyncMock()  # type: ignore[method-assign]
    return agent


def _validator(text: str) -> bool:
    """Accept anything that parses as a dict with key 'verdict'.

    Uses the same {.*} regex + json.loads pattern that production validators
    (ReviewAgent._output_is_valid_review_json, SpecialistBase._extract_json)
    will use. Keeping the test validator in shape-lockstep with production
    ensures the helper's round-trip actually matches real-world unwrap.
    """
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        return False
    try:
        obj = json.loads(match.group(0))
    except (json.JSONDecodeError, ValueError):
        return False
    return isinstance(obj, dict) and "verdict" in obj


@pytest.mark.asyncio
async def test_valid_output_short_circuits() -> None:
    agent = _make_agent()
    agent._claude.chat = AsyncMock()
    res = SimpleNamespace(output='```json\n{"verdict":"pass"}\n```', metadata={})
    await agent._repair_or_escalate_json(
        res, validator=_validator, schema_reminder="...",
        system_prompt="sys", model="m", task_id=None,
    )
    agent._claude.chat.assert_not_called()
    assert res.metadata.get("escalated") is not True


@pytest.mark.asyncio
async def test_repair_succeeds_on_first_round() -> None:
    agent = _make_agent()
    # First repair call returns valid-when-concatenated-with-the-prefill JSON
    agent._claude.chat = AsyncMock(
        return_value=SimpleNamespace(text='"verdict":"pass"}')
    )
    res = SimpleNamespace(output="garbage", metadata={})
    await agent._repair_or_escalate_json(
        res, validator=_validator, schema_reminder="...",
        system_prompt="sys", model="m", task_id="t",
    )
    agent._claude.chat.assert_awaited_once()
    assert res.metadata.get("escalated") is not True


@pytest.mark.asyncio
async def test_persistent_invalidity_escalates_after_two_rounds() -> None:
    agent = _make_agent()
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(text="still bad"))
    res = SimpleNamespace(output="garbage", metadata={})
    await agent._repair_or_escalate_json(
        res, validator=_validator, schema_reminder="...",
        system_prompt="sys", model="m", task_id="t",
    )
    assert agent._claude.chat.await_count == 2
    assert res.metadata.get("escalated") is True
    assert "2 repair rounds" in res.metadata.get("escalation_reason", "")


@pytest.mark.asyncio
async def test_escalation_does_not_touch_task_status() -> None:
    """Shadow-mode invariant: the generic repair helper NEVER writes task.status.

    'needs_human_review' isn't in the tasks.status CHECK constraint; and
    specialists running alongside the coding pipeline must stay side-effect-free.
    """
    agent = _make_agent()
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(text="still bad"))
    res = SimpleNamespace(output="garbage", metadata={})
    await agent._repair_or_escalate_json(
        res, validator=_validator, schema_reminder="...",
        system_prompt="sys", model="m", task_id="t",
    )
    agent._update_task_status.assert_not_called()
