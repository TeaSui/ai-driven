"""T24 unit tests for src/ai_driven/background/reapers.py.

The reapers query ``async_session_factory`` directly, so tests patch
that module-level symbol with a fake context manager that returns a
mocked AsyncSession. We seed the ``SELECT`` call to return a list of
task objects, then assert the per-task CAS update happens for stuck
ones and is skipped for fresh ones.

Cases per brief §5:
- stuck orchestrator   → reaped + audit + counter
- fresh orchestrator   → untouched
- stuck awaiting_qa    → reaped
- stuck running (hb old)       → reaped
- stuck running (hb NULL, old created_at) → reaped
- DB error on one task does not crash the loop
- CAS miss (rowcount=0) skips audit row
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_task(
    *,
    status: str,
    output_data: dict | None = None,
    last_heartbeat_at: datetime | None = None,
    created_at: datetime | None = None,
) -> SimpleNamespace:
    return SimpleNamespace(
        id=uuid.uuid4(),
        status=status,
        output_data=output_data or {},
        last_heartbeat_at=last_heartbeat_at,
        created_at=created_at or datetime.now(timezone.utc),
    )


class _FakeSessionFactory:
    """Reusable fake ``async_session_factory`` — supports multiple
    sequential calls (SELECT sweep + per-task CAS UPDATE).

    ``select_tasks`` is returned on the FIRST session.execute call in a
    given ``__aenter__`` block. Subsequent calls in the same block
    return ``update_rowcount`` as rowcount.
    """

    def __init__(self, select_tasks, *, update_rowcount: int = 1):
        self._select_tasks = select_tasks
        self._update_rowcount = update_rowcount
        self.committed_sessions: list[MagicMock] = []
        self.added_audits: list = []

    def __call__(self):
        return _FakeSessionCtx(self)


class _FakeSessionCtx:
    def __init__(self, factory: _FakeSessionFactory):
        self._factory = factory
        self.session = MagicMock()
        self.session.add = MagicMock(
            side_effect=lambda obj: self._factory.added_audits.append(obj)
        )
        self.session.commit = AsyncMock()
        self.session.rollback = AsyncMock()
        self._call_idx = 0

    async def __aenter__(self):
        # Dispatch on statement type: SELECT returns candidates,
        # UPDATE returns a mock with the configured rowcount. Matching
        # by type rather than call order so _reap_one's fresh session
        # (which only does UPDATE) is handled correctly.
        from sqlalchemy.sql import Select, Update

        async def _execute(stmt):
            if isinstance(stmt, Select):
                scalars_mock = MagicMock()
                scalars_mock.all = MagicMock(return_value=self._factory._select_tasks)
                result_mock = MagicMock()
                result_mock.scalars = MagicMock(return_value=scalars_mock)
                return result_mock
            if isinstance(stmt, Update):
                update_result = MagicMock()
                update_result.rowcount = self._factory._update_rowcount
                return update_result
            raise AssertionError(f"unexpected statement type: {type(stmt)}")

        self.session.execute = AsyncMock(side_effect=_execute)
        return self.session

    async def __aexit__(self, *_):
        self._factory.committed_sessions.append(self.session)
        return None


def _iso_minutes_ago(n: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(minutes=n)).isoformat()


# ---------------------------------------------------------------------------
# Orchestrator reaper
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stuck_orchestrator_is_reaped():
    from ai_driven.background import reapers

    stuck = _make_task(
        status="orchestrating",
        output_data={"started_at": _iso_minutes_ago(45)},
    )
    factory = _FakeSessionFactory([stuck])

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter") as emit:
        n = await reapers.reap_stuck_orchestrators()

    assert n == 1
    # Audit row written
    from ai_driven.db.postgres.models import TaskOverrideAudit
    assert any(isinstance(a, TaskOverrideAudit) for a in factory.added_audits)
    audit = next(a for a in factory.added_audits if isinstance(a, TaskOverrideAudit))
    assert audit.action == "reap"
    assert audit.from_status == "orchestrating"
    assert audit.to_status == "failed"
    assert audit.overridden_by_member_id == reapers.SYSTEM_MEMBER_ID
    # Counter
    assert any(
        call.args[0] == "reaper.orchestrator.reaped"
        for call in emit.call_args_list
    )


@pytest.mark.asyncio
async def test_fresh_orchestrator_untouched():
    from ai_driven.background import reapers

    fresh = _make_task(
        status="orchestrating",
        output_data={"started_at": _iso_minutes_ago(5)},
        created_at=datetime.now(timezone.utc) - timedelta(minutes=5),
    )
    factory = _FakeSessionFactory([fresh])

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter"):
        n = await reapers.reap_stuck_orchestrators()

    assert n == 0
    assert factory.added_audits == []


@pytest.mark.asyncio
async def test_orchestrator_missing_started_falls_back_to_created_at():
    """Orchestrator row with no started_at but created_at > 30 min ago
    is still reaped — covers worker crash before first marker write."""
    from ai_driven.background import reapers

    old_created = datetime.now(timezone.utc) - timedelta(minutes=45)
    task = _make_task(status="orchestrating", output_data={}, created_at=old_created)
    factory = _FakeSessionFactory([task])

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter"):
        n = await reapers.reap_stuck_orchestrators()
    assert n == 1


# ---------------------------------------------------------------------------
# QA reaper
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stuck_awaiting_qa_is_reaped():
    from ai_driven.background import reapers

    task = _make_task(
        status="awaiting_qa",
        output_data={"qa_enqueued_at": _iso_minutes_ago(60)},
    )
    factory = _FakeSessionFactory([task])

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter") as emit:
        n = await reapers.reap_stuck_qa()
    assert n == 1
    assert any(
        call.args[0] == "reaper.qa.reaped"
        for call in emit.call_args_list
    )


@pytest.mark.asyncio
async def test_awaiting_qa_with_no_enqueued_at_left_alone():
    from ai_driven.background import reapers

    task = _make_task(status="awaiting_qa", output_data={})
    factory = _FakeSessionFactory([task])

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter"):
        n = await reapers.reap_stuck_qa()
    assert n == 0


# ---------------------------------------------------------------------------
# Running reaper
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stuck_running_old_heartbeat_is_reaped():
    from ai_driven.background import reapers

    task = _make_task(
        status="running",
        last_heartbeat_at=datetime.now(timezone.utc) - timedelta(minutes=45),
    )
    factory = _FakeSessionFactory([task])

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter"):
        n = await reapers.reap_stuck_running()
    assert n == 1


@pytest.mark.asyncio
async def test_running_null_heartbeat_but_old_created_is_reaped():
    from ai_driven.background import reapers

    task = _make_task(
        status="running",
        last_heartbeat_at=None,
        created_at=datetime.now(timezone.utc) - timedelta(minutes=45),
    )
    factory = _FakeSessionFactory([task])

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter"):
        n = await reapers.reap_stuck_running()
    assert n == 1


@pytest.mark.asyncio
async def test_running_recent_heartbeat_untouched():
    from ai_driven.background import reapers

    task = _make_task(
        status="running",
        last_heartbeat_at=datetime.now(timezone.utc) - timedelta(minutes=2),
    )
    factory = _FakeSessionFactory([task])

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter"):
        n = await reapers.reap_stuck_running()
    assert n == 0


# ---------------------------------------------------------------------------
# Resilience: DB error on one task must not crash the loop
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_db_error_on_one_task_does_not_abort_sweep():
    """If _reap_one raises on one candidate, the sweep continues to the
    next. Simulated by patching _reap_one to raise on first call and
    succeed on second."""
    from ai_driven.background import reapers

    t1 = _make_task(
        status="running",
        last_heartbeat_at=datetime.now(timezone.utc) - timedelta(minutes=45),
    )
    t2 = _make_task(
        status="running",
        last_heartbeat_at=datetime.now(timezone.utc) - timedelta(minutes=45),
    )
    factory = _FakeSessionFactory([t1, t2])

    # Patch _reap_one: raise first time, return True second time.
    calls = {"n": 0}

    async def fake_reap_one(task, **_kw):
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("db blew up")
        return True

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter"), \
         patch("ai_driven.background.reapers._reap_one", side_effect=fake_reap_one):
        n = await reapers.reap_stuck_running()

    # Only one success counted — the failing one is swallowed.
    assert n == 1
    assert calls["n"] == 2


# ---------------------------------------------------------------------------
# CAS miss: another writer beat us
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_reap_cas_miss_skips_audit_row():
    from ai_driven.background import reapers

    task = _make_task(
        status="running",
        last_heartbeat_at=datetime.now(timezone.utc) - timedelta(minutes=45),
    )
    factory = _FakeSessionFactory([task], update_rowcount=0)

    with patch("ai_driven.background.reapers.async_session_factory", factory), \
         patch("ai_driven.background.reapers.emit_counter"):
        n = await reapers.reap_stuck_running()
    assert n == 0
    # No audit row was added because the CAS returned rowcount=0.
    from ai_driven.db.postgres.models import TaskOverrideAudit
    assert not any(isinstance(a, TaskOverrideAudit) for a in factory.added_audits)
