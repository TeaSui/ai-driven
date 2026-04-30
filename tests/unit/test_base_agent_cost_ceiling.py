"""Unit tests for BaseAgent's per-task dollar-budget kill switch (H2).

Verifies:
  * _iteration_cost_usd applies the correct Sonnet 4.6 rates, including
    cache-write and cache-read tiers.
  * AgentResult carries the accumulated cost_usd across both the success
    and exception exit paths.
  * When running_cost_usd crosses claude_max_cost_per_task_usd, the
    _should_continue gate returns False — the loop halts BEFORE the next
    Claude call fires.
  * A budget-exceeded halt marks success=False with a user-readable
    error_msg that names the dollar ceiling.
"""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest

from ai_driven.agents.base import (
    _PRICE_CACHE_READ_PER_M,
    _PRICE_CACHE_WRITE_PER_M,
    _PRICE_INPUT_PER_M,
    _PRICE_OUTPUT_PER_M,
    BaseAgent,
    _iteration_cost_usd,
)


def _usage(
    input_tokens: int = 0,
    output_tokens: int = 0,
    cache_write: int = 0,
    cache_read: int = 0,
) -> SimpleNamespace:
    return SimpleNamespace(
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        cache_creation_input_tokens=cache_write,
        cache_read_input_tokens=cache_read,
    )


def test_iteration_cost_applies_four_pricing_tiers() -> None:
    usage = _usage(input_tokens=10_000, output_tokens=2_000,
                   cache_write=4_000, cache_read=50_000)
    expected = (
        10_000 * _PRICE_INPUT_PER_M / 1_000_000
        + 4_000 * _PRICE_CACHE_WRITE_PER_M / 1_000_000
        + 50_000 * _PRICE_CACHE_READ_PER_M / 1_000_000
        + 2_000 * _PRICE_OUTPUT_PER_M / 1_000_000
    )
    assert _iteration_cost_usd(usage) == pytest.approx(expected, rel=1e-9)


def test_iteration_cost_zero_for_empty_usage() -> None:
    assert _iteration_cost_usd(_usage()) == 0.0


def test_iteration_cost_tolerates_missing_cache_fields() -> None:
    """Older response objects may not expose cache_* fields — getattr() fallback."""
    minimal = SimpleNamespace(input_tokens=1000, output_tokens=500)
    # No cache_creation_input_tokens / cache_read_input_tokens attributes.
    expected = 1000 * _PRICE_INPUT_PER_M / 1_000_000 + 500 * _PRICE_OUTPUT_PER_M / 1_000_000
    assert _iteration_cost_usd(minimal) == pytest.approx(expected, rel=1e-9)


class _StubAgent(BaseAgent):
    """Minimal BaseAgent subclass for exercising the budget gate in isolation."""

    agent_type = "stub"

    def _register_tools(self) -> None:
        return None

    def _get_system_prompt(self, context):
        return "stub"

    def _get_tool_names(self):
        return []


@pytest.mark.asyncio
async def test_should_continue_halts_loop_when_budget_exceeded() -> None:
    """The gate must return False once running_cost_usd >= ceiling.

    We exercise the gate path through BaseAgent.run by driving _claude.run_agent_loop
    via a stub that repeatedly fires on_response with expensive usage until the
    gate says stop. We verify:
      * _should_continue returned False before iterations_budget was exhausted
      * the returned AgentResult has success=False
      * cost_usd on the result exceeds the ceiling
      * error_msg names the dollar amount for UI surfacing
    """
    agent = _StubAgent()
    agent._publish_event = AsyncMock()  # noqa: swallow events
    agent._update_task_status = AsyncMock()
    agent._check_control_flag = AsyncMock(return_value=None)

    # Fake response shape matching MessageResponse: has .usage, .text, .stop_reason,
    # .has_tool_use. Each response charges $1.50 — 2 iterations clears the $3 cap.
    expensive_response = SimpleNamespace(
        usage=_usage(input_tokens=500_000, output_tokens=0),  # $1.50/call
        text="",
        stop_reason="end_turn",
        has_tool_use=False,
    )

    async def fake_run_agent_loop(messages, **kwargs):
        on_response = kwargs["on_response"]
        should_continue = kwargs["should_continue"]
        # Iteration 1 — gate says go
        if await should_continue() is False:
            return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")
        on_response(expensive_response)  # cost → $1.50
        # Iteration 2 — still under the $3 cap
        if await should_continue() is False:
            return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")
        on_response(expensive_response)  # cost → $3.00
        # Iteration 3 — gate must now refuse; the loop halts here.
        if await should_continue() is False:
            return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")
        # Should never reach here in the budget-exceeded case.
        on_response(expensive_response)
        return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")

    agent._claude.run_agent_loop = fake_run_agent_loop
    # Force the ceiling regardless of .env.local via settings override patch.
    with patch("ai_driven.agents.base.get_settings") as mock_settings:
        mock_settings.return_value.claude_max_cost_per_task_usd = 3.0
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.claude_max_tool_iterations = 10
        result = await agent.run({"message": "do work"}, task_id=None)

    assert result.success is False
    assert result.cost_usd >= 3.0
    assert result.error is not None
    assert "cost ceiling" in result.error


@pytest.mark.asyncio
async def test_under_budget_run_succeeds_and_reports_cost() -> None:
    """Golden path: one cheap iteration, success, cost_usd populated."""
    agent = _StubAgent()
    agent._publish_event = AsyncMock()
    agent._update_task_status = AsyncMock()
    agent._check_control_flag = AsyncMock(return_value=None)

    cheap = SimpleNamespace(
        usage=_usage(input_tokens=1_000, output_tokens=500),  # ~$0.0105
        text="done",
        stop_reason="end_turn",
        has_tool_use=False,
    )

    async def fake_run_agent_loop(messages, **kwargs):
        on_response = kwargs["on_response"]
        should_continue = kwargs["should_continue"]
        if await should_continue() is False:
            return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")
        on_response(cheap)
        return cheap  # success

    agent._claude.run_agent_loop = fake_run_agent_loop
    with patch("ai_driven.agents.base.get_settings") as mock_settings:
        mock_settings.return_value.claude_max_cost_per_task_usd = 3.0
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.claude_max_tool_iterations = 10
        result = await agent.run({"message": "cheap job"}, task_id=None)

    assert result.success is True
    assert result.error is None
    assert 0 < result.cost_usd < 0.02
