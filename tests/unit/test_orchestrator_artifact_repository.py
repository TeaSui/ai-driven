"""T-A2: ArtifactRepository round-trips cost_usd end-to-end.

Unit scope — the session is mocked. The invariant we lock is that
``upsert`` writes ``cost_usd`` on both the INSERT and UPDATE branches and
``list_for_task`` reconstructs it when reading back. Without these the
``task_artifacts.cost_usd`` column stays at its zero default and the
cost-attribution dashboard shows a flat $0 for every specialist regardless
of actual spend.
"""
from __future__ import annotations

import uuid
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.orchestrator.repository import ArtifactRepository


def _session_returning(existing: object | None) -> AsyncMock:
    session = AsyncMock()
    session.execute = AsyncMock(
        return_value=MagicMock(
            scalar_one_or_none=MagicMock(return_value=existing),
        )
    )
    session.add = MagicMock()
    session.flush = AsyncMock()
    session.commit = AsyncMock()
    return session


@pytest.mark.asyncio
async def test_upsert_insert_persists_cost_usd() -> None:
    """INSERT branch: the new TaskArtifact row carries artifact.cost_usd."""
    session = _session_returning(None)
    repo = ArtifactRepository(session)

    cost = Decimal("0.012345")
    art = Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning="ok",
        payload={"ambiguities_resolved": [], "acceptance_criteria": [],
                 "out_of_scope": [], "blocking_questions": []},
        model_used="claude-sonnet-4-6",
        input_tokens=1000,
        output_tokens=500,
        cost_usd=cost,
    )
    await repo.upsert(uuid.uuid4(), art)

    session.add.assert_called_once()
    row = session.add.call_args.args[0]
    assert row.cost_usd == cost
    session.flush.assert_awaited_once()


@pytest.mark.asyncio
async def test_upsert_update_writes_cost_usd() -> None:
    """UPDATE branch: an existing row's cost_usd is overwritten."""
    existing = MagicMock()
    existing.cost_usd = Decimal("0")
    session = _session_returning(existing)
    repo = ArtifactRepository(session)

    cost = Decimal("0.004200")
    art = Artifact(
        role="security",
        verdict="block",
        reasoning="critical threat",
        payload={"threat_model": [], "implementation_rules": [],
                 "compliance_notes": [], "blocking_concerns": ["x"]},
        model_used="claude-sonnet-4-6",
        input_tokens=800,
        output_tokens=400,
        cost_usd=cost,
    )
    await repo.upsert(uuid.uuid4(), art)

    assert existing.cost_usd == cost
    session.flush.assert_awaited_once()


@pytest.mark.asyncio
async def test_list_for_task_reads_cost_usd_back_into_artifact() -> None:
    """Readers of ``list_for_task`` (dashboard, fan-out, audit) must see
    the persisted cost, not a zero default. Regression guard against the
    T0-baseline bug where the column existed but readers never populated
    the in-memory Artifact with it.
    """
    row = MagicMock()
    row.role = "qa"
    row.verdict = "pass"
    row.reasoning = "ok"
    row.payload = {"checks_performed": [], "test_coverage_gap": [],
                   "regression_risk": "low"}
    row.model_used = "claude-sonnet-4-6"
    row.input_tokens = 200
    row.output_tokens = 100
    row.cost_usd = Decimal("0.002100")

    session = AsyncMock()
    scalars = MagicMock()
    scalars.__iter__ = MagicMock(return_value=iter([row]))
    session.execute = AsyncMock(
        return_value=MagicMock(scalars=MagicMock(return_value=scalars))
    )
    repo = ArtifactRepository(session)

    out = await repo.list_for_task(uuid.uuid4())

    assert "qa" in out
    assert out["qa"].cost_usd == Decimal("0.002100")
    assert isinstance(out["qa"].cost_usd, Decimal)


@pytest.mark.asyncio
async def test_list_rows_for_task_returns_orm_rows_with_id_and_created_at() -> None:
    """T25 added ``list_rows_for_task`` so the admin Specialists UI can
    render per-artifact ``id`` + ``created_at`` without re-wrapping into
    the ``Artifact`` dataclass (which intentionally omits those)."""
    import datetime as _dt

    row_a = MagicMock()
    row_a.id = uuid.uuid4()
    row_a.role = "business_analyst"
    row_a.created_at = _dt.datetime(2026, 5, 1, 10, 0, 0, tzinfo=_dt.timezone.utc)

    row_b = MagicMock()
    row_b.id = uuid.uuid4()
    row_b.role = "security"
    row_b.created_at = _dt.datetime(2026, 5, 1, 10, 5, 0, tzinfo=_dt.timezone.utc)

    scalars_all = MagicMock()
    scalars_all.all = MagicMock(return_value=[row_a, row_b])
    session = AsyncMock()
    session.execute = AsyncMock(
        return_value=MagicMock(scalars=MagicMock(return_value=scalars_all))
    )
    repo = ArtifactRepository(session)

    rows = await repo.list_rows_for_task(uuid.uuid4())

    assert rows == [row_a, row_b]
    # Sanity: caller gets real ORM rows, not dataclasses.
    assert rows[0] is row_a
    assert rows[1] is row_b
