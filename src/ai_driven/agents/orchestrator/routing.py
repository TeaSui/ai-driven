"""Tier-based routing rules — pure Python, code-reviewable.

Per-team customization is deferred to spec C (teams.settings_json
overrides). This module is the single source of truth for which
specialists run for each tier.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class TierRule:
    mandatory: list[str]
    candidates: list[str]    # picked by Layer 3 (LLM picker)
    skip_to_pipeline: bool
    final_gate: str | None = None


TIER_RULES: dict[str, TierRule] = {
    "fast_path": TierRule(
        mandatory=[], candidates=[], skip_to_pipeline=True, final_gate=None,
    ),
    "full_workflow": TierRule(
        mandatory=["business_analyst", "stack_analyst", "security"],
        candidates=["tech_lead", "ux_designer"],
        skip_to_pipeline=False,
        final_gate="qa",
    ),
    "multi_domain": TierRule(
        mandatory=["business_analyst", "stack_analyst", "tech_lead", "security"],
        candidates=["ux_designer", "api_test"],
        skip_to_pipeline=False,
        final_gate="qa",
    ),
}


@dataclass
class SpecialistPlan:
    tier: str
    mandatory: list[str]
    candidates: list[str]
    skip_to_pipeline: bool
    final_gate: str | None
    picked: list[str] = field(default_factory=list)

    def full_sequence(self) -> list[str]:
        """Mandatory + picked + final_gate, in run order."""
        seq = list(self.mandatory) + list(self.picked)
        if self.final_gate is not None:
            seq.append(self.final_gate)
        return seq


def plan_specialist_sequence(
    tier: str,
    task_context: dict[str, Any],
) -> SpecialistPlan:
    """Return the SpecialistPlan for a tier. Picker fills in `picked` later."""
    rule = TIER_RULES.get(tier)
    if rule is None:
        raise ValueError(f"Unknown orchestration tier: {tier}")
    return SpecialistPlan(
        tier=tier,
        mandatory=list(rule.mandatory),
        candidates=list(rule.candidates),
        skip_to_pipeline=rule.skip_to_pipeline,
        final_gate=rule.final_gate,
    )
