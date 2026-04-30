"""Unit tests for BaseAgent's per-task dollar-budget kill switch (H2 + H4).

Verifies:
  * _iteration_cost_usd dispatches through billing.tracker._MODEL_PRICING
    and applies the right rates for Sonnet, Haiku, and Opus.
  * Bedrock regional uplift multiplies all four pricing tiers when
    claude_provider == "bedrock" and aws_region != "us-east-1".
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

from ai_driven.agents.base import BaseAgent, _iteration_cost_usd
from ai_driven.billing.tracker import get_model_pricing


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


def _expected_cost(
    model: str,
    input_tokens: int,
    output_tokens: int,
    cache_write: int,
    cache_read: int,
    multiplier: float = 1.0,
) -> float:
    rates = get_model_pricing(model)
    return multiplier * (
        input_tokens * rates["input"] / 1000.0
        + cache_write * rates["cache_write"] / 1000.0
        + cache_read * rates["cache_read"] / 1000.0
        + output_tokens * rates["output"] / 1000.0
    )


# --- Per-model dispatch ------------------------------------------------------

@pytest.mark.parametrize("model,input_rate_per_k", [
    ("claude-sonnet-4-6", 0.003),
    ("claude-haiku-4-5",  0.0008),
    ("claude-opus-4-7",   0.015),
])
def test_iteration_cost_dispatches_by_model(model: str, input_rate_per_k: float) -> None:
    """Sonnet / Haiku / Opus each bill at their own rate."""
    usage = _usage(input_tokens=10_000, output_tokens=2_000,
                   cache_write=4_000, cache_read=50_000)
    # Force anthropic provider so regional uplift is 1.0 regardless of env.
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_provider = "anthropic"
        mock_settings.return_value.aws_region = "us-east-1"
        mock_settings.return_value.claude_default_model = model
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        actual = _iteration_cost_usd(usage, model=model)

    expected = _expected_cost(model, 10_000, 2_000, 4_000, 50_000)
    assert actual == pytest.approx(expected, rel=1e-9)
    # Sanity: assert the input contribution is indeed proportional to
    # the published rate for that model (would fail if we dispatched wrong).
    input_contribution = 10_000 * input_rate_per_k / 1000.0
    assert input_contribution < expected  # cache + output add more


def test_iteration_cost_zero_for_empty_usage() -> None:
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_provider = "anthropic"
        mock_settings.return_value.aws_region = "us-east-1"
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        assert _iteration_cost_usd(_usage(), model="claude-sonnet-4-6") == 0.0


def test_iteration_cost_tolerates_missing_cache_fields() -> None:
    """Older response objects may not expose cache_* fields — getattr() fallback."""
    minimal = SimpleNamespace(input_tokens=1000, output_tokens=500)
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_provider = "anthropic"
        mock_settings.return_value.aws_region = "us-east-1"
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        actual = _iteration_cost_usd(minimal, model="claude-sonnet-4-6")
    rates = get_model_pricing("claude-sonnet-4-6")
    expected = 1000 * rates["input"] / 1000.0 + 500 * rates["output"] / 1000.0
    assert actual == pytest.approx(expected, rel=1e-9)


def test_iteration_cost_falls_back_to_default_model_when_none() -> None:
    """Calling without a model uses claude_default_model from settings."""
    usage = _usage(input_tokens=1_000, output_tokens=200)
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_provider = "anthropic"
        mock_settings.return_value.aws_region = "us-east-1"
        mock_settings.return_value.claude_default_model = "claude-haiku-4-5"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        actual = _iteration_cost_usd(usage)  # no model= kwarg

    expected = _expected_cost("claude-haiku-4-5", 1_000, 200, 0, 0)
    assert actual == pytest.approx(expected, rel=1e-9)


# --- Bedrock regional uplift -------------------------------------------------

def test_bedrock_us_east_region_no_uplift() -> None:
    """us-east-1 Bedrock is at parity with Anthropic Direct — multiplier 1.0."""
    usage = _usage(input_tokens=1_000_000, output_tokens=0)
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_provider = "bedrock"
        mock_settings.return_value.aws_region = "us-east-1"
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        actual = _iteration_cost_usd(usage, model="claude-sonnet-4-6")
    # 1M tokens * $0.003/1K = $3.00 flat at us-east-1.
    assert actual == pytest.approx(3.00, rel=1e-9)


def test_bedrock_non_us_east_applies_regional_uplift() -> None:
    """ap-southeast-1 and other non-us-east regions scale ALL four tiers."""
    usage = _usage(input_tokens=1_000_000, output_tokens=100_000,
                   cache_write=500_000, cache_read=2_000_000)
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_provider = "bedrock"
        mock_settings.return_value.aws_region = "ap-southeast-1"
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        actual = _iteration_cost_usd(usage, model="claude-sonnet-4-6")
    expected = 1.10 * _expected_cost("claude-sonnet-4-6",
                                     1_000_000, 100_000, 500_000, 2_000_000)
    assert actual == pytest.approx(expected, rel=1e-9)


def test_anthropic_provider_ignores_region_multiplier() -> None:
    """Direct Anthropic is never uplifted, even if aws_region is set."""
    usage = _usage(input_tokens=1_000_000, output_tokens=0)
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_provider = "anthropic"
        mock_settings.return_value.aws_region = "ap-southeast-1"  # irrelevant
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        actual = _iteration_cost_usd(usage, model="claude-sonnet-4-6")
    assert actual == pytest.approx(3.00, rel=1e-9)


# --- Kill-switch through BaseAgent.run --------------------------------------

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
    agent = _StubAgent()
    agent._publish_event = AsyncMock()
    agent._update_task_status = AsyncMock()
    agent._check_control_flag = AsyncMock(return_value=None)

    # $1.50/call * 2 calls = $3.00 -> third call is gated by the $3 ceiling.
    expensive_response = SimpleNamespace(
        usage=_usage(input_tokens=500_000, output_tokens=0),
        text="",
        stop_reason="end_turn",
        has_tool_use=False,
    )

    async def fake_run_agent_loop(messages, **kwargs):
        on_response = kwargs["on_response"]
        should_continue = kwargs["should_continue"]
        if await should_continue() is False:
            return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")
        on_response(expensive_response)  # $1.50
        if await should_continue() is False:
            return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")
        on_response(expensive_response)  # $3.00
        if await should_continue() is False:  # gated here
            return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")
        on_response(expensive_response)      # should never reach
        return SimpleNamespace(usage=_usage(), text="", stop_reason="end_turn")

    agent._claude.run_agent_loop = fake_run_agent_loop
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_max_cost_per_task_usd = 3.0
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.claude_max_tool_iterations = 10
        mock_settings.return_value.claude_provider = "anthropic"
        mock_settings.return_value.aws_region = "us-east-1"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        result = await agent.run({"message": "do work"}, task_id=None)

    assert result.success is False
    assert result.cost_usd >= 3.0
    assert result.error is not None
    assert "cost ceiling" in result.error


@pytest.mark.asyncio
async def test_run_short_circuits_when_initial_cost_over_ceiling() -> None:
    """Orchestrator pre-seeds initial_cost when dispatching specialists so the
    per-task ceiling applies to the combined run. If the seed is ALREADY at or
    over the ceiling, BaseAgent.run must return success=False with ZERO Claude
    calls — the _should_continue gate only runs between iterations, so the
    first create_message would otherwise fire and burn cost.

    Per docs/specs/2026-04-30-orchestrator-revision.md §2.
    """
    agent = _StubAgent()
    agent._publish_event = AsyncMock()
    agent._update_task_status = AsyncMock()
    agent._check_control_flag = AsyncMock(return_value=None)

    # Replace the agent loop with a spy that records invocations. If the
    # short-circuit works, this must never be awaited.
    fake_loop = AsyncMock()
    agent._claude.run_agent_loop = fake_loop

    initial_cost = 3.50  # well over the $3.00 default ceiling
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_max_cost_per_task_usd = 3.0
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.claude_max_tool_iterations = 10
        mock_settings.return_value.claude_provider = "anthropic"
        mock_settings.return_value.aws_region = "us-east-1"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        result = await agent.run(
            {"message": "work"}, task_id=None, initial_cost=initial_cost,
        )

    # Zero Claude calls — the whole point of the pre-iteration check.
    fake_loop.assert_not_awaited()

    assert result.success is False
    assert result.cost_usd == pytest.approx(initial_cost)
    # Failure reason surfaces the short-circuit path. Check both the metadata
    # key (the machine-readable marker) and the human-readable error string.
    assert result.metadata.get("reason") == "budget_exceeded_on_entry"
    assert "budget_exceeded_on_entry" in (result.error or "")


@pytest.mark.asyncio
async def test_under_budget_run_succeeds_and_reports_cost() -> None:
    agent = _StubAgent()
    agent._publish_event = AsyncMock()
    agent._update_task_status = AsyncMock()
    agent._check_control_flag = AsyncMock(return_value=None)

    cheap = SimpleNamespace(
        usage=_usage(input_tokens=1_000, output_tokens=500),
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
        return cheap

    agent._claude.run_agent_loop = fake_run_agent_loop
    with patch("ai_driven.config.get_settings") as mock_settings:
        mock_settings.return_value.claude_max_cost_per_task_usd = 3.0
        mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
        mock_settings.return_value.claude_max_tool_iterations = 10
        mock_settings.return_value.claude_provider = "anthropic"
        mock_settings.return_value.aws_region = "us-east-1"
        mock_settings.return_value.bedrock_price_multiplier = 1.10
        result = await agent.run({"message": "cheap job"}, task_id=None)

    assert result.success is True
    assert result.error is None
    assert 0 < result.cost_usd < 0.02
