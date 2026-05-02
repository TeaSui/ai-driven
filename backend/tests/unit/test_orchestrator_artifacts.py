"""Unit tests for the orchestrator artifact layer.

This file is seeded in Task 2 (ORM model) and extended by Tasks 3 and 4
(Pydantic schemas, repository). Keep new tests appended in task order so
the file reads top-to-bottom matching the plan.
"""
from __future__ import annotations

import uuid
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock

import pytest
from pydantic import ValidationError

from ai_driven.agents.orchestrator.artifacts import (
    PAYLOAD_SCHEMAS,
    Artifact,
    BusinessAnalystPayload,
    QaPayload,
    SecurityPayload,
    StackAnalystPayload,
    validate_payload,
)
from ai_driven.agents.orchestrator.repository import ArtifactRepository
from ai_driven.db.postgres.models import Task, TaskArtifact


def test_task_artifact_model_columns() -> None:
    cols = {c.name for c in TaskArtifact.__table__.columns}
    assert cols == {
        "id", "task_id", "role", "payload", "verdict", "reasoning",
        "model_used", "input_tokens", "output_tokens", "cost_usd", "created_at",
    }


def test_task_artifact_unique_index_on_task_role() -> None:
    indexes = {idx.name: list(idx.columns.keys()) for idx in TaskArtifact.__table__.indexes}
    assert "idx_task_artifacts_task_role" in indexes
    assert indexes["idx_task_artifacts_task_role"] == ["task_id", "role"]


def test_task_has_orchestration_tier_column() -> None:
    cols = {c.name for c in Task.__table__.columns}
    assert "orchestration_tier" in cols


def test_task_artifact_cost_usd_defaults_to_zero() -> None:
    """Per-revision §2.2: new TaskArtifact rows must start at $0 so the
    cost-attribution dashboard can sum orchestration spend safely.
    Regression guard against accidentally dropping the default or the
    NOT NULL constraint.

    SQLAlchemy `default=` fires on flush (not __init__), so assert the
    column metadata directly — that's what SQLAlchemy will apply and what
    the migration's server_default mirrors for existing-row backfill."""
    col = TaskArtifact.__table__.columns["cost_usd"]
    assert col.default is not None
    assert col.default.arg == Decimal("0")
    assert col.nullable is False


def test_business_analyst_payload_accepts_full_shape() -> None:
    p = BusinessAnalystPayload(
        ambiguities_resolved=[{"question": "Q?", "resolution": "R."}],
        acceptance_criteria=[{"given": "g", "when": "w", "then": "t"}],
        out_of_scope=["x"],
        blocking_questions=[],
    )
    assert p.acceptance_criteria[0].given == "g"


def test_stack_analyst_payload_rejects_unknown_stack() -> None:
    with pytest.raises(ValidationError):
        StackAnalystPayload(
            stacks=["backend", "spaceship"],  # "spaceship" not in fixed set
            primary_stack="backend",
            cross_cutting_concerns=[],
            reasoning="r",
        )


def test_security_payload_severity_enum() -> None:
    with pytest.raises(ValidationError):
        SecurityPayload(
            threat_model=[{
                "stride_category": "S",
                "threat": "t",
                "severity": "ULTRA",  # invalid
                "mitigation": "m",
            }],
            implementation_rules=[],
            compliance_notes=[],
            blocking_concerns=[],
        )


def test_qa_payload_regression_risk_enum() -> None:
    # QaPayload is a discriminated-union TypeAdapter after T22 — must call
    # validate_python (not the bare constructor). Pre-coding / legacy
    # payloads omit ``schema_version``; validate_payload infers v1.
    QaPayload.validate_python({
        "schema_version": "v1",
        "checks_performed": [{"check": "c", "passed": True, "detail": "d"}],
        "test_coverage_gap": [],
        "regression_risk": "low",
    })
    with pytest.raises(ValidationError):
        QaPayload.validate_python({
            "schema_version": "v1",
            "checks_performed": [],
            "test_coverage_gap": [],
            "regression_risk": "apocalyptic",
        })


def test_qa_payload_v1_missing_schema_version_is_inferred_via_validate_payload() -> None:
    """Back-compat: pre-T22 rows in task_artifacts omit schema_version.

    validate_payload fills in ``schema_version='v1'`` so legacy rows keep
    parsing. The TypeAdapter itself refuses a missing discriminator —
    the inference is at the validate_payload layer only.
    """
    raw = {
        "checks_performed": [{"check": "c", "passed": True, "detail": "d"}],
        "test_coverage_gap": [],
        "regression_risk": "low",
    }
    # Legacy-shape parses via validate_payload.
    parsed = validate_payload("qa", raw)
    assert parsed.schema_version == "v1"
    # Same raw via the bare TypeAdapter → ValidationError.
    with pytest.raises(ValidationError):
        QaPayload.validate_python(raw)


def test_qa_payload_v2_requires_schema_version_key() -> None:
    """Post-coding QA (v2) MUST set schema_version explicitly — no default.

    A v2-intent payload that forgets the key gets classified as v1 by the
    validate_payload fallback (so pre-T22 rows still parse). The wrapper
    belt-and-braces check (qa.post_coding.schema_drift) catches the case
    where the specialist meant v2 but silently produced v1.
    """
    v2_payload = {
        "schema_version": "v2",
        "checks_performed": [],
        "acceptance_criteria_verification": [
            {
                "criterion": "AC1",
                "satisfied": True,
                "evidence": "src/foo.py:42",
                "reason": "implemented",
            },
        ],
        "security_rules_verification": [
            {
                "rule": "argon2id",
                "followed": True,
                "evidence": "src/auth.py:12",
                "severity": "high",
            },
        ],
        "test_coverage_gap": [],
        "regression_risk": "low",
    }
    parsed = QaPayload.validate_python(v2_payload)
    assert parsed.schema_version == "v2"
    # Nested verification sub-models parsed:
    assert parsed.acceptance_criteria_verification[0].satisfied is True
    assert parsed.security_rules_verification[0].severity == "high"

    # Missing the required v2 sub-arrays → ValidationError.
    with pytest.raises(ValidationError):
        QaPayload.validate_python({
            "schema_version": "v2",
            "checks_performed": [],
            "test_coverage_gap": [],
            "regression_risk": "low",
        })


def test_qa_payload_v2_security_severity_enum() -> None:
    """severity MUST be one of low/medium/high/critical (Severity enum)."""
    with pytest.raises(ValidationError):
        QaPayload.validate_python({
            "schema_version": "v2",
            "checks_performed": [],
            "acceptance_criteria_verification": [],
            "security_rules_verification": [
                {
                    "rule": "r",
                    "followed": False,
                    "evidence": "",
                    "severity": "catastrophic",  # invalid
                },
            ],
            "test_coverage_gap": [],
            "regression_risk": "low",
        })


def test_payload_schemas_registry_has_seven_roles() -> None:
    assert set(PAYLOAD_SCHEMAS) == {
        "business_analyst", "stack_analyst", "security", "qa",
        "tech_lead", "ux_designer", "api_test",
    }
    # Spot-check wiring to guard against paste-o mistakes:
    assert PAYLOAD_SCHEMAS["business_analyst"] is BusinessAnalystPayload
    assert PAYLOAD_SCHEMAS["security"] is SecurityPayload
    assert PAYLOAD_SCHEMAS["qa"] is QaPayload


def test_validate_payload_dispatches_by_role() -> None:
    out = validate_payload("business_analyst", {
        "ambiguities_resolved": [],
        "acceptance_criteria": [],
        "out_of_scope": [],
        "blocking_questions": [],
    })
    assert isinstance(out, BusinessAnalystPayload)


def test_payload_ignores_unknown_top_level_keys() -> None:
    """Live LLM output may decorate the payload with 'confidence' etc.
    These must be ignored, not rejected — validating the required shape
    is what matters; stray keys are dropped on model_dump()."""
    out = validate_payload("business_analyst", {
        "ambiguities_resolved": [],
        "acceptance_criteria": [],
        "out_of_scope": [],
        "blocking_questions": [],
        "confidence": 0.92,
        "notes": "extra field the model added",
    })
    dumped = out.model_dump()
    assert "confidence" not in dumped
    assert "notes" not in dumped


def test_nested_submodels_still_forbid_extras() -> None:
    """Structural drift in a nested entry IS a real bug — catch it."""
    with pytest.raises(ValidationError):
        validate_payload("security", {
            "threat_model": [{
                "stride_category": "Spoofing",
                "threat": "t", "severity": "high", "mitigation": "m",
                "bogus_extra": True,
            }],
            "implementation_rules": [],
            "compliance_notes": [],
            "blocking_concerns": [],
        })


def test_artifact_dataclass_carries_all_fields() -> None:
    a = Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning="ok",
        payload={"ambiguities_resolved": [], "acceptance_criteria": [],
                 "out_of_scope": [], "blocking_questions": []},
        model_used="claude-sonnet-4-6",
        input_tokens=100,
        output_tokens=50,
    )
    assert a.role == "business_analyst" and a.input_tokens == 100


def test_artifact_dataclass_cost_usd_defaults_to_decimal_zero() -> None:
    """Legacy callers that don't pass ``cost_usd`` (stubs, error paths,
    existing tests) must still produce a well-formed Artifact with
    ``cost_usd == Decimal('0')``. The type MUST be ``Decimal`` (not float)
    because the DB column is ``NUMERIC(10,6)`` and summing many small
    float costs drifts at the micro-dollar level.
    """
    a = Artifact(
        role="security",
        verdict="pass",
        reasoning="no issues",
        payload={"threat_model": [], "implementation_rules": [],
                 "compliance_notes": [], "blocking_concerns": []},
        model_used="claude-sonnet-4-6",
        input_tokens=0,
        output_tokens=0,
    )
    assert a.cost_usd == Decimal("0")
    assert isinstance(a.cost_usd, Decimal)


def test_artifact_dataclass_cost_usd_round_trips_through_asdict() -> None:
    """``dataclasses.asdict`` is used by ``_artifact_to_dict`` to inline
    prior artifacts into the XML envelope. The ``cost_usd`` field must
    survive that serialization so downstream specialists see the billed
    cost of their precursors (informational only today; dashboards +
    per-role cost attribution rely on the same path).
    """
    from dataclasses import asdict as _asdict
    a = Artifact(
        role="qa",
        verdict="pass",
        reasoning="ok",
        payload={"checks_performed": [], "test_coverage_gap": [],
                 "regression_risk": "low"},
        model_used="claude-sonnet-4-6",
        input_tokens=100,
        output_tokens=50,
        cost_usd=Decimal("0.001050"),
    )
    dumped = _asdict(a)
    assert dumped["cost_usd"] == Decimal("0.001050")
    assert "cost_usd" in dumped


@pytest.mark.asyncio
async def test_artifact_repository_upsert_inserts_new_row() -> None:
    """When no row exists for (task_id, role), upsert calls session.add()
    and flushes but does NOT commit — the caller owns the transaction."""
    session = AsyncMock()
    session.execute = AsyncMock(
        return_value=MagicMock(scalar_one_or_none=MagicMock(return_value=None))
    )
    session.add = MagicMock()
    session.flush = AsyncMock()
    session.commit = AsyncMock()

    repo = ArtifactRepository(session)
    art = Artifact(
        role="security",
        verdict="pass",
        reasoning="no issues",
        payload={"threat_model": [], "implementation_rules": [],
                 "compliance_notes": [], "blocking_concerns": []},
        model_used="claude-sonnet-4-6",
        input_tokens=120,
        output_tokens=80,
    )
    await repo.upsert(uuid.uuid4(), art)
    session.add.assert_called_once()
    session.flush.assert_awaited_once()
    session.commit.assert_not_called()


@pytest.mark.asyncio
async def test_artifact_repository_upsert_updates_existing_row() -> None:
    """When a row exists for (task_id, role), upsert mutates it instead of inserting."""
    existing = MagicMock()
    session = AsyncMock()
    session.execute = AsyncMock(
        return_value=MagicMock(scalar_one_or_none=MagicMock(return_value=existing))
    )
    session.add = MagicMock()
    session.flush = AsyncMock()
    session.commit = AsyncMock()

    repo = ArtifactRepository(session)
    art = Artifact(
        role="security",
        verdict="block",
        reasoning="blocker",
        payload={"threat_model": [], "implementation_rules": [],
                 "compliance_notes": [], "blocking_concerns": ["x"]},
        model_used="claude-sonnet-4-6",
        input_tokens=1,
        output_tokens=1,
    )
    await repo.upsert(uuid.uuid4(), art)

    session.add.assert_not_called()
    assert existing.verdict == "block"
    assert existing.reasoning == "blocker"
    assert existing.payload == art.payload
    session.flush.assert_awaited_once()
    session.commit.assert_not_called()
