"""Unit tests for ReviewAgent's bounded JSON-repair loop (lesson 56).

Verifies:
  * valid output → no repair calls, no escalation
  * invalid output → repair retried up to _MAX_JSON_REPAIR_ROUNDS, succeeds when
    a round returns valid JSON
  * persistently invalid → escalation flag set after the cap, no third call
"""

from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.reviewer.agent import (
    _MAX_JSON_REPAIR_ROUNDS,
    ReviewAgent,
)


def _wrap(payload: dict[str, Any]) -> str:
    return "```json\n" + json.dumps(payload) + "\n```"


def _make_agent() -> ReviewAgent:
    """Construct a ReviewAgent with no SCM and a mocked Claude client.

    BaseAgent's __init__ creates a ClaudeClient, but we replace `chat` per-test.
    """
    agent = ReviewAgent(scm_client=None)
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._update_task_status = AsyncMock()  # type: ignore[method-assign]
    return agent


def _result(text: str) -> SimpleNamespace:
    return SimpleNamespace(output=text, metadata={})


def test_valid_output_short_circuits() -> None:
    agent = _make_agent()
    agent._claude.chat = AsyncMock()  # would blow up if called
    valid = _wrap({"verdict": "approve", "summary": "ok", "findings": []})
    res = _result(valid)

    import asyncio
    asyncio.run(agent._repair_or_escalate_output(res, model="claude", task_id=None))

    agent._claude.chat.assert_not_called()
    assert res.output == valid
    assert res.metadata.get("escalated") is not True


def test_repair_succeeds_on_first_round() -> None:
    agent = _make_agent()
    repaired = json.dumps({"verdict": "approve", "summary": "ok", "findings": []})
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(text=repaired[1:] + "\n"))
    res = _result("not json at all")

    import asyncio
    asyncio.run(agent._repair_or_escalate_output(res, model="claude", task_id="t-1"))

    agent._claude.chat.assert_awaited_once()
    assert "```json" in res.output
    assert res.metadata.get("escalated") is not True


def test_persistent_invalidity_escalates_after_cap() -> None:
    agent = _make_agent()
    # Always return rubbish — repair will never succeed.
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(text="still not json"))
    res = _result("garbage")

    import asyncio
    asyncio.run(agent._repair_or_escalate_output(res, model="claude", task_id="t-2"))

    # Hard cap respected.
    assert agent._claude.chat.await_count == _MAX_JSON_REPAIR_ROUNDS
    assert res.metadata["escalated"] is True
    assert "after" in res.metadata["escalation_reason"]
    agent._update_task_status.assert_awaited_with("needs_human_review")


def test_escalation_event_published() -> None:
    agent = _make_agent()
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(text="garbage"))
    res = _result("garbage")

    import asyncio
    asyncio.run(agent._repair_or_escalate_output(res, model="claude", task_id="t-3"))

    event_types = [call.args[0] for call in agent._publish_event.await_args_list]
    assert "review_escalation" in event_types
    # And one repair event per attempt.
    assert event_types.count("review_output_repair") == _MAX_JSON_REPAIR_ROUNDS


@pytest.mark.parametrize("missing_field", ["verdict", "findings_and_summary"])
def test_validator_rejects_incomplete_payloads(missing_field: str) -> None:
    if missing_field == "verdict":
        bad = _wrap({"summary": "x", "findings": []})
    else:
        bad = _wrap({"verdict": "approve"})  # no findings/comments/issues AND no summary
    assert not ReviewAgent._output_is_valid_review_json(bad)


def test_validator_accepts_alias_field_names() -> None:
    # `comments` is an accepted alias for `findings` per _persist_review_row.
    payload = _wrap({"verdict": "comment", "comments": []})
    assert ReviewAgent._output_is_valid_review_json(payload)
    payload2 = _wrap({"verdict": "comment", "issues": []})
    assert ReviewAgent._output_is_valid_review_json(payload2)
