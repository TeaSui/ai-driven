"""Fan-out rules — which prior artifacts each specialist sees.

Pure function: relevant_artifacts_for(role, all_artifacts) -> dict[str, Artifact].

Hard-coded per the matrix in
docs/specs/2026-04-29-orchestrator-specialists-design.md.
"""
from __future__ import annotations

from ai_driven.agents.orchestrator.artifacts import Artifact

_FAN_OUT: dict[str, list[str]] = {
    "business_analyst": [],
    "stack_analyst":   ["business_analyst"],
    "security":        ["business_analyst", "stack_analyst"],
    "tech_lead":       ["business_analyst", "stack_analyst", "security"],
    "ux_designer":     ["business_analyst", "stack_analyst"],
    "api_test":        ["stack_analyst", "tech_lead", "security"],
    "qa":              ["business_analyst", "stack_analyst", "security",
                        "tech_lead", "ux_designer", "api_test"],
}


def relevant_artifacts_for(
    role: str,
    all_artifacts: dict[str, Artifact],
) -> dict[str, Artifact]:
    """Return the subset of all_artifacts the given role is allowed to read."""
    allowed = _FAN_OUT.get(role)
    if allowed is None:
        raise ValueError(f"Unknown specialist role: {role}")
    return {r: all_artifacts[r] for r in allowed if r in all_artifacts}


def should_run_ux_designer(stack_analyst_payload: dict) -> bool:
    """UX runs only for frontend or mobile work. Used by the picker stage."""
    stacks = set(stack_analyst_payload.get("stacks", []))
    return bool(stacks & {"frontend", "mobile"})
