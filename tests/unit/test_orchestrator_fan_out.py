"""Unit tests for fan-out — what each specialist sees."""
from __future__ import annotations

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.orchestrator.fan_out import relevant_artifacts_for


def _art(role: str, payload: dict | None = None) -> Artifact:
    return Artifact(
        role=role, verdict="pass", reasoning="",
        payload=payload or {}, model_used="m",
        input_tokens=1, output_tokens=1,
    )


def _all() -> dict[str, Artifact]:
    return {
        "business_analyst": _art("business_analyst"),
        "stack_analyst": _art("stack_analyst",
                              {"stacks": ["frontend"], "primary_stack": "frontend",
                               "cross_cutting_concerns": [], "reasoning": ""}),
        "security": _art("security"),
        "tech_lead": _art("tech_lead"),
    }


def test_business_analyst_sees_nothing() -> None:
    assert relevant_artifacts_for("business_analyst", _all()) == {}


def test_stack_analyst_sees_only_ba() -> None:
    out = relevant_artifacts_for("stack_analyst", _all())
    assert set(out) == {"business_analyst"}


def test_security_sees_ba_and_stack_analyst() -> None:
    out = relevant_artifacts_for("security", _all())
    assert set(out) == {"business_analyst", "stack_analyst"}


def test_qa_sees_all_prior() -> None:
    out = relevant_artifacts_for("qa", _all())
    assert set(out) == {"business_analyst", "stack_analyst", "security", "tech_lead"}


def test_ux_designer_only_runs_when_frontend_or_mobile_in_stacks() -> None:
    artifacts = _all()
    # frontend → UX runs; sees BA + stack_analyst
    out = relevant_artifacts_for("ux_designer", artifacts)
    assert set(out) == {"business_analyst", "stack_analyst"}

    # backend-only stack_analyst → UX should not have been scheduled, but
    # if it was, it still receives BA + stack_analyst (filter is at the
    # picker stage, not here).
    artifacts["stack_analyst"] = _art("stack_analyst",
        {"stacks": ["backend"], "primary_stack": "backend",
         "cross_cutting_concerns": [], "reasoning": ""})
    out = relevant_artifacts_for("ux_designer", artifacts)
    assert set(out) == {"business_analyst", "stack_analyst"}


def test_unknown_role_raises() -> None:
    with pytest.raises(ValueError):
        relevant_artifacts_for("unicorn", _all())
