"""T19 — CodingAgent wires prompt_composer into its system-prompt path.

Covers the sync/async bridge (artifacts loaded async in run(), composed sync
in _get_system_prompt), the orchestrator_mode gate, task_id=None skipping,
and graceful fallback when the DB read fails or artifacts are absent.

No real Claude calls — run_agent_loop is mocked so we can inspect the
`system=` keyword the system prompt is passed with. Default settings have
``orchestrator_mode="shadow"`` so we only patch settings in the "off" case.
"""
from __future__ import annotations

import logging
import uuid
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.coding.agent import CodingAgent
from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.claude.prompts.coding_agent import CODING_AGENT_SYSTEM_PROMPT
from ai_driven.config import get_settings


# -------------------- helpers --------------------


def _make_ba_artifact(criteria: list[dict[str, str]]) -> Artifact:
    return Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning=None,
        payload={"acceptance_criteria": criteria},
        model_used="test-model",
        input_tokens=0,
        output_tokens=0,
        cost_usd=Decimal("0"),
    )


def _fake_response() -> MagicMock:
    resp = MagicMock()
    resp.text = "done"
    resp.has_tool_use = False
    resp.usage = MagicMock(input_tokens=1, output_tokens=1)
    resp.stop_reason = "end_turn"
    return resp


def _make_agent_with_mock_claude() -> tuple[CodingAgent, AsyncMock]:
    """Construct a CodingAgent whose run_agent_loop is a captured AsyncMock."""
    agent = CodingAgent()
    loop_mock = AsyncMock(return_value=_fake_response())
    agent._claude.run_agent_loop = loop_mock  # type: ignore[method-assign]
    return agent, loop_mock


def _fake_session_ctx() -> MagicMock:
    """Return a factory mock compatible with ``async with factory() as s:``."""
    session = MagicMock()
    ctx = MagicMock()
    ctx.__aenter__ = AsyncMock(return_value=session)
    ctx.__aexit__ = AsyncMock(return_value=None)
    factory = MagicMock(return_value=ctx)
    return factory


def _settings_with_mode(mode: str) -> object:
    """Return a settings copy with orchestrator_mode overridden.

    Using the real Settings model means rag_auto_enable / mcp_enabled /
    claude_max_cost_per_task_usd / etc. keep their defaults — we don't
    accidentally turn on side-effectful paths in base.py.
    """
    return get_settings().model_copy(update={"orchestrator_mode": mode})


# -------------------- tests --------------------


@pytest.mark.asyncio
async def test_empty_artifacts_uses_base_prompt() -> None:
    """list_for_task → {} ⇒ system prompt stays at the static constant."""
    agent, loop_mock = _make_agent_with_mock_claude()
    task_id = str(uuid.uuid4())

    repo_mock = MagicMock()
    repo_mock.list_for_task = AsyncMock(return_value={})
    factory = _fake_session_ctx()

    with (
        patch("ai_driven.agents.coding.agent.async_session_factory", factory),
        patch(
            "ai_driven.agents.coding.agent.ArtifactRepository",
            return_value=repo_mock,
        ),
    ):
        await agent.run({"jira_key": "TEST-1"}, task_id=task_id)

    repo_mock.list_for_task.assert_awaited_once()
    loop_mock.assert_awaited_once()
    system_arg = loop_mock.await_args.kwargs["system"]
    assert system_arg == CODING_AGENT_SYSTEM_PROMPT
    assert "<acceptance_criteria>" not in system_arg


@pytest.mark.asyncio
async def test_artifacts_trigger_composition() -> None:
    """Two ACs from business_analyst ⇒ both land inside <acceptance_criteria>."""
    agent, loop_mock = _make_agent_with_mock_claude()
    task_id = str(uuid.uuid4())

    artifact = _make_ba_artifact(
        [
            {
                "given": "a logged-in user",
                "when": "they click logout",
                "then": "the session ends",
            },
            {
                "given": "an anonymous visitor",
                "when": "they hit /dashboard",
                "then": "they are redirected to /login",
            },
        ]
    )
    repo_mock = MagicMock()
    repo_mock.list_for_task = AsyncMock(
        return_value={"business_analyst": artifact}
    )
    factory = _fake_session_ctx()

    with (
        patch("ai_driven.agents.coding.agent.async_session_factory", factory),
        patch(
            "ai_driven.agents.coding.agent.ArtifactRepository",
            return_value=repo_mock,
        ),
    ):
        await agent.run({"jira_key": "TEST-2"}, task_id=task_id)

    system_arg = loop_mock.await_args.kwargs["system"]
    assert "<acceptance_criteria>" in system_arg
    assert (
        "Given a logged-in user, when they click logout, then the session ends"
        in system_arg
    )
    assert (
        "Given an anonymous visitor, when they hit /dashboard, then they are redirected to /login"
        in system_arg
    )


@pytest.mark.asyncio
async def test_task_id_none_skips_lookup() -> None:
    """Without a task_id there is nothing to look up — repository untouched."""
    agent, loop_mock = _make_agent_with_mock_claude()

    repo_mock = MagicMock()
    repo_mock.list_for_task = AsyncMock(return_value={})
    factory = _fake_session_ctx()

    with (
        patch("ai_driven.agents.coding.agent.async_session_factory", factory),
        patch(
            "ai_driven.agents.coding.agent.ArtifactRepository",
            return_value=repo_mock,
        ),
    ):
        await agent.run({"jira_key": "TEST-3"}, task_id=None)

    repo_mock.list_for_task.assert_not_awaited()
    factory.assert_not_called()
    system_arg = loop_mock.await_args.kwargs["system"]
    assert system_arg == CODING_AGENT_SYSTEM_PROMPT


@pytest.mark.asyncio
async def test_db_error_falls_back_to_base(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """A DB failure must not crash the run — base prompt, WARNING logged."""
    agent, loop_mock = _make_agent_with_mock_claude()
    task_id = str(uuid.uuid4())

    repo_mock = MagicMock()
    repo_mock.list_for_task = AsyncMock(side_effect=RuntimeError("db down"))
    factory = _fake_session_ctx()

    with (
        caplog.at_level(logging.WARNING, logger="ai_driven.agents.coding.agent"),
        patch("ai_driven.agents.coding.agent.async_session_factory", factory),
        patch(
            "ai_driven.agents.coding.agent.ArtifactRepository",
            return_value=repo_mock,
        ),
    ):
        result = await agent.run({"jira_key": "TEST-4"}, task_id=task_id)

    # Did NOT crash — the outer run returned an AgentResult.
    assert result is not None
    loop_mock.assert_awaited_once()
    system_arg = loop_mock.await_args.kwargs["system"]
    assert system_arg == CODING_AGENT_SYSTEM_PROMPT
    assert any(
        "load_prior_artifacts_failed" in rec.message for rec in caplog.records
    ), "expected a warning with load_prior_artifacts_failed"


@pytest.mark.asyncio
async def test_orchestrator_mode_off_skips_lookup() -> None:
    """orchestrator_mode='off' ⇒ even with a task_id, no artifact lookup."""
    agent, loop_mock = _make_agent_with_mock_claude()
    task_id = str(uuid.uuid4())

    repo_mock = MagicMock()
    repo_mock.list_for_task = AsyncMock(return_value={})
    factory = _fake_session_ctx()

    with (
        patch("ai_driven.agents.coding.agent.async_session_factory", factory),
        patch(
            "ai_driven.agents.coding.agent.ArtifactRepository",
            return_value=repo_mock,
        ),
        patch(
            "ai_driven.agents.coding.agent.get_settings",
            return_value=_settings_with_mode("off"),
        ),
    ):
        await agent.run({"jira_key": "TEST-5"}, task_id=task_id)

    repo_mock.list_for_task.assert_not_awaited()
    factory.assert_not_called()
    system_arg = loop_mock.await_args.kwargs["system"]
    assert system_arg == CODING_AGENT_SYSTEM_PROMPT
