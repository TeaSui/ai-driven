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
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter

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


class AcceptanceCriterionVerification(_Strict):
    """One row per BusinessAnalyst acceptance_criterion in post-coding QA.

    Added in T22 so the post-coding QA specialist can cite concrete
    evidence from the PR diff (satisfied=True) or a concrete reason the
    criterion is unmet (satisfied=False). T23 will gate status
    transitions on ``satisfied=false``.
    """
    criterion: str
    satisfied: bool
    evidence: str
    reason: str


class SecurityRuleVerification(_Strict):
    """One row per Security implementation_rule in post-coding QA.

    Added in T22. ``severity`` (low/medium/high/critical) gates T23's
    blocking decision — only high/critical + followed=False will halt
    the pipeline. Low / medium downgrade to pass_with_notes.
    """
    rule: str
    followed: bool
    evidence: str
    severity: Severity


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


class QaPayloadV1(_Tolerant):
    """Legacy pre-coding QA payload (schema_version='v1', default).

    Kept for backward compatibility with any QA artifacts written before
    T22. The pre-coding final_gate path has been removed in T22, but
    existing rows in task_artifacts may still contain v1 payloads and
    MUST continue to parse.
    """
    schema_version: Literal["v1"] = "v1"
    checks_performed: list[QaCheck]
    test_coverage_gap: list[str]
    regression_risk: RegressionRisk


class QaPayloadV2(_Tolerant):
    """Post-coding QA payload (schema_version='v2', REQUIRED — no default).

    Added in T22. The post-coding QA specialist MUST emit this shape:
    ``schema_version: "v2"`` is required (no default) so a missing key
    becomes a hard validation error — the specialist's JSON repair loop
    then reinforces the schema. Belt-and-braces: the consumer wrapper
    checks ``schema_version == "v2"`` post-validation and forces
    ``verdict='error'`` + emits ``qa.post_coding.schema_drift`` if it
    slipped through.

    T23 will gate status transitions off v2-only — v1 (legacy pre-
    coding) is invisible to the T23 transition hook.
    """
    schema_version: Literal["v2"]  # NO default — required
    checks_performed: list[QaCheck]
    acceptance_criteria_verification: list[AcceptanceCriterionVerification]
    security_rules_verification: list[SecurityRuleVerification]
    test_coverage_gap: list[str]
    regression_risk: RegressionRisk


# Discriminated union over ``schema_version`` so pydantic dispatches to
# the right variant. ``Annotated[Union, Field(discriminator=...)]`` is
# NOT a ``BaseModel`` subclass — it has no ``.model_validate`` — so we
# wrap it in a ``TypeAdapter``. ``PAYLOAD_SCHEMAS`` stores the adapter
# directly; ``validate_payload`` dispatches on ``isinstance(schema,
# TypeAdapter)`` vs ``BaseModel`` subclass (see B-v3-1 in the T22 brief).
_QaPayloadUnion = Annotated[
    QaPayloadV1 | QaPayloadV2, Field(discriminator="schema_version")
]
QaPayload = TypeAdapter(_QaPayloadUnion)


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


PAYLOAD_SCHEMAS: dict[Role, type[BaseModel] | TypeAdapter[Any]] = {
    "business_analyst": BusinessAnalystPayload,
    "stack_analyst": StackAnalystPayload,
    "security": SecurityPayload,
    "qa": QaPayload,
    "tech_lead": TechLeadPayload,
    "ux_designer": UxDesignerPayload,
    "api_test": ApiTestPayload,
}


def validate_payload(role: str, raw: dict[str, Any]) -> BaseModel:
    """Validate `raw` against the schema for `role`. Raises ValidationError.

    ``PAYLOAD_SCHEMAS`` entries are either ``type[BaseModel]`` (the normal
    case) OR a pydantic ``TypeAdapter`` (used for discriminated unions
    like the QA v1/v2 payload). The two expose DIFFERENT validation
    methods — ``BaseModel.model_validate`` vs ``TypeAdapter.validate_python``
    — so we dispatch on the concrete type. See B-v3-1 in the T22 brief.
    """
    schema = PAYLOAD_SCHEMAS.get(role)
    if schema is None:
        raise ValueError(f"Unknown specialist role: {role}")
    if isinstance(schema, TypeAdapter):
        # Pydantic's tagged-union validator refuses a payload that is
        # missing the discriminator key — it cannot tell which variant
        # to dispatch to. For the QA v1/v2 union we treat a missing
        # ``schema_version`` as the legacy v1 shape so pre-T22 callers
        # keep working. Post-coding QA (v2) MUST set ``schema_version``
        # explicitly; the specialist wrapper additionally asserts
        # ``== "v2"`` after validation and flags schema drift.
        if (
            role == "qa"
            and isinstance(raw, dict)
            and "schema_version" not in raw
        ):
            raw = {**raw, "schema_version": "v1"}
        return schema.validate_python(raw)
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
