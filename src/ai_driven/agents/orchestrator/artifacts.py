"""Pydantic payload schemas + Artifact dataclass for specialist outputs.

Every specialist returns an Artifact whose `payload` is validated against
the role-specific Pydantic model from PAYLOAD_SCHEMAS. The orchestrator
persists Artifacts to the task_artifacts table after validation.

Two validation stances:
  * Top-level payload schemas inherit _Tolerant (extra='ignore') — real
    LLM output occasionally decorates payloads with extra fields like
    'confidence'; we validate required shape but drop unknown keys on
    model_dump().
  * Nested sub-models inherit _Strict (extra='forbid') — structural drift
    inside a ThreatEntry or AcceptanceCriterion IS a real bug we want
    to surface.

Schemas mirror the shapes in
docs/specs/2026-04-29-orchestrator-specialists-design.md and the DB
CHECK constraints in
alembic/versions/a1b2c3d4e5fa_orchestration_artifacts.py.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict

Role = Literal[
    "business_analyst", "stack_analyst", "security",
    "qa", "tech_lead", "ux_designer", "api_test",
]
Verdict = Literal["pass", "pass_with_notes", "block", "error", "not_implemented"]
Stack = Literal["backend", "frontend", "mobile", "devops", "data", "ai", "aws"]
Severity = Literal["low", "medium", "high", "critical"]
RegressionRisk = Literal["low", "medium", "high"]


# --- Sub-models -------------------------------------------------------------

class _Strict(BaseModel):
    """Base for nested sub-models — forbid extras to catch structural drift."""
    model_config = ConfigDict(extra="forbid")


class _Tolerant(BaseModel):
    """Base for top-level payloads — ignore unknown top-level keys."""
    model_config = ConfigDict(extra="ignore")


class AmbiguityResolution(_Strict):
    question: str
    resolution: str


class AcceptanceCriterion(_Strict):
    given: str
    when: str
    then: str


class ThreatEntry(_Strict):
    stride_category: str
    threat: str
    severity: Severity
    mitigation: str


class QaCheck(_Strict):
    check: str
    passed: bool
    detail: str


class SelectedTech(_Strict):
    choice: str
    rationale: str


class UserFlowStep(_Strict):
    step: int
    action: str
    screen: str


class EndpointTested(_Strict):
    path: str
    method: str
    status: int
    p95_ms: float


# --- Per-role payload schemas ----------------------------------------------

class BusinessAnalystPayload(_Tolerant):
    ambiguities_resolved: list[AmbiguityResolution]
    acceptance_criteria: list[AcceptanceCriterion]
    out_of_scope: list[str]
    blocking_questions: list[str]


class StackAnalystPayload(_Tolerant):
    stacks: list[Stack]
    primary_stack: Stack
    cross_cutting_concerns: list[str]
    reasoning: str


class SecurityPayload(_Tolerant):
    threat_model: list[ThreatEntry]
    implementation_rules: list[str]
    compliance_notes: list[str]
    blocking_concerns: list[str]


class QaPayload(_Tolerant):
    checks_performed: list[QaCheck]
    test_coverage_gap: list[str]
    regression_risk: RegressionRisk


class TechLeadPayload(_Tolerant):
    adr_summary: str
    # TODO(phase-2): model api_contracts as typed OpenAPI fragments when the
    # TechLead specialist moves out of stub status.
    api_contracts: list[dict]
    selected_tech: list[SelectedTech]
    delegation_plan: list[str]


class UxDesignerPayload(_Tolerant):
    wireframe_refs: list[str]
    user_flows: list[UserFlowStep]
    accessibility_notes: list[str]


class ApiTestPayload(_Tolerant):
    endpoints_tested: list[EndpointTested]
    # TODO(phase-2): model security_smoke_results once the APITest specialist
    # moves out of stub status and its result shape is stable.
    security_smoke_results: list[dict]
    failures: list[str]


PAYLOAD_SCHEMAS: dict[Role, type[BaseModel]] = {
    "business_analyst": BusinessAnalystPayload,
    "stack_analyst": StackAnalystPayload,
    "security": SecurityPayload,
    "qa": QaPayload,
    "tech_lead": TechLeadPayload,
    "ux_designer": UxDesignerPayload,
    "api_test": ApiTestPayload,
}


def validate_payload(role: str, raw: dict[str, Any]) -> BaseModel:
    """Validate `raw` against the schema for `role`. Raises ValidationError."""
    schema = PAYLOAD_SCHEMAS.get(role)
    if schema is None:
        raise ValueError(f"Unknown specialist role: {role}")
    return schema.model_validate(raw)


# --- Artifact dataclass ----------------------------------------------------

@dataclass
class Artifact:
    """Pre-persistence in-memory record of one specialist run.

    `id` and `created_at` are assigned by ArtifactRepository on insert.
    Callers MUST validate `payload` via `validate_payload()` before
    constructing an Artifact — the dataclass is a pure carrier and does
    not re-validate on its own.
    """

    role: Role
    verdict: Verdict
    reasoning: str | None
    payload: dict[str, Any]
    model_used: str | None
    input_tokens: int
    output_tokens: int
    # Per-artifact USD cost of the specialist call. ``Decimal`` (not float)
    # because the DB column is ``NUMERIC(10,6)`` and summing many small
    # specialist costs under binary float drifts within a few micro-dollars
    # per task — large enough to break strict-gating ROI analysis at scale.
    # Default is ``Decimal("0")`` so stubs, error paths, and legacy callers
    # that construct ``Artifact`` without cost_usd continue to work.
    cost_usd: Decimal = field(default_factory=lambda: Decimal("0"))
