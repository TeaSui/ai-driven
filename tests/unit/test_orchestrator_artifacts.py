"""Unit tests for the orchestrator artifact layer.

This file is seeded in Task 2 (ORM model) and extended by Tasks 3 and 4
(Pydantic schemas, repository). Keep new tests appended in task order so
the file reads top-to-bottom matching the plan.
"""
from __future__ import annotations

import uuid
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
        "model_used", "input_tokens", "output_tokens", "created_at",
    }


def test_task_artifact_unique_index_on_task_role() -> None:
    indexes = {idx.name: list(idx.columns.keys()) for idx in TaskArtifact.__table__.indexes}
    assert "idx_task_artifacts_task_role" in indexes
    assert indexes["idx_task_artifacts_task_role"] == ["task_id", "role"]


def test_task_has_orchestration_tier_column() -> None:
    cols = {c.name for c in Task.__table__.columns}
    assert "orchestration_tier" in cols


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
    QaPayload(
        checks_performed=[{"check": "c", "passed": True, "detail": "d"}],
        test_coverage_gap=[],
        regression_risk="low",
    )
    with pytest.raises(ValidationError):
        QaPayload(
            checks_performed=[],
            test_coverage_gap=[],
            regression_risk="apocalyptic",
        )


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
