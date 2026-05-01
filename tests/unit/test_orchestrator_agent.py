"""Unit tests for OrchestratorAgent — full flow with mocked specialists."""
from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.agents.orchestrator.agent import OrchestratorAgent
from ai_driven.agents.orchestrator.artifacts import Artifact


def _ba_artifact(verdict: str = "pass") -> Artifact:
    return Artifact(
        role="business_analyst", verdict=verdict, reasoning="ok",
        payload={"ambiguities_resolved": [], "acceptance_criteria": [],
                 "out_of_scope": [], "blocking_questions": [] if verdict != "block" else ["?"]},
        model_used="m", input_tokens=10, output_tokens=10,
    )


def _other_pass(role: str) -> Artifact:
    return Artifact(role=role, verdict="pass", reasoning="ok",
                    payload={}, model_used="m", input_tokens=1, output_tokens=1)


@pytest.mark.asyncio
async def test_fast_path_skips_specialists_and_logs_only() -> None:
    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="fast_path")
    agent._persist_tier = AsyncMock()
    agent._dispatch_specialist = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)

    assert result["tier"] == "fast_path"
    assert result["specialists_run"] == []
    agent._dispatch_specialist.assert_not_called()


@pytest.mark.asyncio
async def test_full_workflow_runs_mandatory_specialists_in_order() -> None:
    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="full_workflow")
    agent._persist_tier = AsyncMock()
    agent._persist_artifact = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)

    # Stub dispatcher: returns role-keyed Artifact
    async def fake_dispatch(role, ctx):
        if role == "business_analyst":
            return _ba_artifact()
        return _other_pass(role)
    agent._dispatch_specialist = AsyncMock(side_effect=fake_dispatch)
    agent._pick_optional_specialists = AsyncMock(return_value=[])

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)

    assert result["tier"] == "full_workflow"
    # Order matters: BA -> stack_analyst -> security -> qa
    assert [c.args[0] for c in agent._dispatch_specialist.call_args_list] == [
        "business_analyst", "stack_analyst", "security", "qa",
    ]


@pytest.mark.asyncio
async def test_blocker_verdict_halts_remaining_specialists() -> None:
    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="full_workflow")
    agent._persist_tier = AsyncMock()
    agent._persist_artifact = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)

    async def fake_dispatch(role, ctx):
        if role == "business_analyst":
            return _ba_artifact("block")
        return _other_pass(role)
    agent._dispatch_specialist = AsyncMock(side_effect=fake_dispatch)
    agent._pick_optional_specialists = AsyncMock(return_value=[])

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)

    # BA blocked -> stack_analyst, security, qa skipped
    called = [c.args[0] for c in agent._dispatch_specialist.call_args_list]
    assert called == ["business_analyst"]
    assert result["halted_on"] == "business_analyst"


def _error_artifact(role: str) -> Artifact:
    return Artifact(role=role, verdict="error", reasoning="infra flake",
                    payload={}, model_used="m", input_tokens=0, output_tokens=0)


@pytest.mark.asyncio
async def test_fallback_error_treated_as_pass_when_flag_true(monkeypatch) -> None:
    """T-A1 fallback: BA errors, StackAnalyst still runs with empty BA prior.

    Verifies:
      * pipeline does NOT halt on verdict='error',
      * downstream fan_out source excludes the errored role so StackAnalyst's
        ``prior_artifacts`` dict is empty for business_analyst,
      * the errored artifact is still persisted (audit trail preserved).
    """
    from ai_driven.config import get_settings
    get_settings.cache_clear()
    monkeypatch.setenv("AI_DRIVEN_ORCHESTRATOR_TREAT_ERRORS_AS_PASS", "true")

    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="full_workflow")
    agent._persist_tier = AsyncMock()
    agent._persist_artifact = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)
    agent._pick_optional_specialists = AsyncMock(return_value=[])

    observed_priors: dict[str, dict] = {}

    async def fake_dispatch(role, ctx):
        observed_priors[role] = dict(ctx.prior_artifacts)
        if role == "business_analyst":
            return _error_artifact("business_analyst")
        return _other_pass(role)

    agent._dispatch_specialist = AsyncMock(side_effect=fake_dispatch)

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)

    called = [c.args[0] for c in agent._dispatch_specialist.call_args_list]
    assert called == ["business_analyst", "stack_analyst", "security", "qa"]
    assert result["halted_on"] is None
    # StackAnalyst saw no BA prior because the errored upstream was dropped.
    assert "business_analyst" not in observed_priors["stack_analyst"]
    # The error artifact was still persisted (audit trail).
    persist_calls = agent._persist_artifact.call_args_list
    ba_persist = next(c for c in persist_calls if c.args[1].role == "business_analyst")
    assert ba_persist.args[1].verdict == "error"
    assert "business_analyst" in result["fallback_activated"]

    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_fallback_error_halts_when_flag_false(monkeypatch) -> None:
    """T-A1 fallback: with the flag off, error halts the pipeline like block."""
    from ai_driven.config import get_settings
    get_settings.cache_clear()
    monkeypatch.setenv("AI_DRIVEN_ORCHESTRATOR_TREAT_ERRORS_AS_PASS", "false")

    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="full_workflow")
    agent._persist_tier = AsyncMock()
    agent._persist_artifact = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)
    agent._pick_optional_specialists = AsyncMock(return_value=[])

    async def fake_dispatch(role, ctx):
        if role == "business_analyst":
            return _error_artifact("business_analyst")
        return _other_pass(role)
    agent._dispatch_specialist = AsyncMock(side_effect=fake_dispatch)

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)

    called = [c.args[0] for c in agent._dispatch_specialist.call_args_list]
    assert called == ["business_analyst"]
    assert result["halted_on"] == "business_analyst"
    assert result["fallback_activated"] == []

    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_fallback_metric_increments_on_error(monkeypatch) -> None:
    """Every fallback activation emits orchestrator.specialist.fallback_activated."""
    from ai_driven.config import get_settings
    get_settings.cache_clear()
    monkeypatch.setenv("AI_DRIVEN_ORCHESTRATOR_TREAT_ERRORS_AS_PASS", "true")

    calls: list[tuple[str, int, list[str]]] = []

    import ai_driven.agents.orchestrator.agent as agent_mod

    def fake_emit(role: str) -> None:
        calls.append(("orchestrator.specialist.fallback_activated", 1, [f"role:{role}"]))

    monkeypatch.setattr(agent_mod, "emit_specialist_fallback_activated", fake_emit)

    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="full_workflow")
    agent._persist_tier = AsyncMock()
    agent._persist_artifact = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)
    agent._pick_optional_specialists = AsyncMock(return_value=[])

    async def fake_dispatch(role, ctx):
        # Security errors, everything else passes.
        if role == "security":
            return _error_artifact("security")
        if role == "business_analyst":
            return _ba_artifact()
        return _other_pass(role)
    agent._dispatch_specialist = AsyncMock(side_effect=fake_dispatch)

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    await agent.orchestrate(payload)

    assert any("role:security" in c[2] for c in calls), (
        f"expected fallback_activated counter for security role, got {calls}"
    )

    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_shadow_mode_does_not_signal_pipeline_change() -> None:
    """Phase 1 invariant: orchestrator never returns gate-pass/fail signals
    that would change downstream behavior. result['shadow'] must be True."""
    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="fast_path")
    agent._persist_tier = AsyncMock()
    agent._dispatch_specialist = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)
    assert result["shadow"] is True
