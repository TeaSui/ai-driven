"""Unit tests for routing rules — pure functions, no mocks needed."""
from __future__ import annotations

import pytest

from ai_driven.agents.orchestrator.routing import (
    TIER_RULES,
    SpecialistPlan,
    plan_specialist_sequence,
)


def test_tier_rules_has_three_tiers() -> None:
    assert set(TIER_RULES) == {"fast_path", "full_workflow", "multi_domain"}


def test_fast_path_skips_to_pipeline_with_no_specialists() -> None:
    plan = plan_specialist_sequence("fast_path", task_context={})
    assert plan.skip_to_pipeline is True
    assert plan.mandatory == []
    assert plan.candidates == []
    assert plan.final_gate is None


def test_full_workflow_runs_ba_then_stack_analyst_then_security() -> None:
    plan = plan_specialist_sequence("full_workflow", task_context={})
    assert plan.mandatory == ["business_analyst", "stack_analyst", "security"]
    # T22: QA moved post-coding — no pre-coding final_gate.
    assert plan.final_gate is None
    assert plan.skip_to_pipeline is False


def test_multi_domain_includes_tech_lead_in_mandatory() -> None:
    plan = plan_specialist_sequence("multi_domain", task_context={})
    assert plan.mandatory == [
        "business_analyst", "stack_analyst", "tech_lead", "security",
    ]
    assert "ux_designer" in plan.candidates
    assert "api_test" in plan.candidates
    # T22: QA moved post-coding — no pre-coding final_gate.
    assert plan.final_gate is None


def test_unknown_tier_raises() -> None:
    with pytest.raises(ValueError):
        plan_specialist_sequence("nonsense", task_context={})


def test_specialist_plan_full_sequence_no_qa_in_pre_coding() -> None:
    """T22: QA is post-coding. No tier's pre-coding sequence may include it."""
    for tier in ("fast_path", "full_workflow", "multi_domain"):
        plan = plan_specialist_sequence(tier, task_context={})
        assert "qa" not in plan.full_sequence(), (
            f"tier={tier}: QA must not appear in pre-coding full_sequence "
            "(T22 moved QA to the post-coding worker)."
        )
