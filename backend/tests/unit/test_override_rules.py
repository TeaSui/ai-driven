"""T25 unit tests for ``available_actions`` (admin/override_rules.py).

This is the UI-hint policy used by GET /tasks/{id}/artifacts. It mirrors
T24's inline validator but is strictly read-only — it decides which
override buttons the Specialists tab renders.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

from ai_driven.api.admin.override_rules import (
    OVERRIDEABLE_ACTIONS,
    available_actions,
)


def _task(
    *,
    status: str,
    output_data: dict | None = None,
    created_at: datetime | None = None,
    last_heartbeat_at: datetime | None = None,
) -> SimpleNamespace:
    return SimpleNamespace(
        status=status,
        output_data=output_data or {},
        created_at=created_at or datetime.now(timezone.utc),
        last_heartbeat_at=last_heartbeat_at,
    )


def test_overrideable_actions_table_shape():
    # Sanity: the UI relies on these exact status keys.
    assert set(OVERRIDEABLE_ACTIONS.keys()) == {
        "blocked", "qa_failed", "orchestrating", "awaiting_qa", "running",
    }
    assert OVERRIDEABLE_ACTIONS["blocked"] == ["rerun_coding"]
    assert OVERRIDEABLE_ACTIONS["qa_failed"] == ["rerun_qa", "force_pass"]


def test_blocked_offers_rerun_coding():
    actions = available_actions(_task(status="blocked"))
    assert actions == ["rerun_coding"]


def test_qa_failed_offers_rerun_and_force_pass():
    actions = available_actions(_task(status="qa_failed"))
    assert actions == ["rerun_qa", "force_pass"]


def test_completed_task_has_no_actions():
    assert available_actions(_task(status="completed")) == []


def test_queued_task_has_no_actions():
    assert available_actions(_task(status="queued")) == []


def test_failed_task_has_no_actions():
    assert available_actions(_task(status="failed")) == []


# ---------------------------------------------------------------------------
# Stuck-threshold logic for reap actions
# ---------------------------------------------------------------------------


def test_fresh_orchestrating_does_not_offer_reap():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    started = (now - timedelta(minutes=5)).isoformat()
    task = _task(
        status="orchestrating",
        output_data={"started_at": started},
        created_at=now - timedelta(minutes=5),
    )
    assert available_actions(task, now=now) == []


def test_stuck_orchestrating_offers_reap():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    started = (now - timedelta(minutes=45)).isoformat()
    task = _task(
        status="orchestrating",
        output_data={"started_at": started},
        created_at=now - timedelta(minutes=45),
    )
    assert available_actions(task, now=now) == ["reap"]


def test_orchestrating_falls_back_to_created_at_when_no_started_at():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    # No started_at set → fallback is created_at.
    task_fresh = _task(
        status="orchestrating",
        output_data={},
        created_at=now - timedelta(minutes=5),
    )
    assert available_actions(task_fresh, now=now) == []

    task_stuck = _task(
        status="orchestrating",
        output_data={},
        created_at=now - timedelta(minutes=45),
    )
    assert available_actions(task_stuck, now=now) == ["reap"]


def test_fresh_awaiting_qa_does_not_offer_reap():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    enq = (now - timedelta(minutes=5)).isoformat()
    task = _task(
        status="awaiting_qa",
        output_data={"qa_enqueued_at": enq},
    )
    assert available_actions(task, now=now) == []


def test_stuck_awaiting_qa_offers_reap():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    enq = (now - timedelta(minutes=45)).isoformat()
    task = _task(
        status="awaiting_qa",
        output_data={"qa_enqueued_at": enq},
    )
    assert available_actions(task, now=now) == ["reap"]


def test_awaiting_qa_without_enq_ts_does_not_fallback():
    # Intentional: awaiting_qa has no created_at fallback in the T24
    # validator — the queue timestamp is the only stuck signal. Keep the
    # UI hint in sync.
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    task = _task(status="awaiting_qa", output_data={})
    assert available_actions(task, now=now) == []


def test_fresh_running_does_not_offer_reap():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    task = _task(
        status="running",
        last_heartbeat_at=now - timedelta(minutes=2),
        created_at=now - timedelta(minutes=5),
    )
    assert available_actions(task, now=now) == []


def test_stuck_running_by_heartbeat_offers_reap():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    task = _task(
        status="running",
        last_heartbeat_at=now - timedelta(minutes=45),
        created_at=now - timedelta(minutes=60),
    )
    assert available_actions(task, now=now) == ["reap"]


def test_running_falls_back_to_created_at_when_no_heartbeat():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    task = _task(
        status="running",
        last_heartbeat_at=None,
        created_at=now - timedelta(minutes=45),
    )
    assert available_actions(task, now=now) == ["reap"]
