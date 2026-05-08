"""Stuck-task reapers (T24).

Three reapers sweep the tasks table every 5 min and fail any task that
has been in a non-terminal state longer than STUCK_THRESHOLD_MINUTES
without activity:

- ``reap_stuck_orchestrators`` — status='orchestrating' with
  ``output_data.started_at`` (or ``created_at`` when unset) older than
  the threshold.
- ``reap_stuck_qa`` — status='awaiting_qa' with
  ``output_data.qa_enqueued_at`` older than the threshold.
- ``reap_stuck_running`` — status='running' with ``last_heartbeat_at``
  (or ``created_at`` when never heartbeat) older than the threshold.

Each reaper writes a ``task_override_audit`` row per task it reaps with
``action='reap'`` and ``overridden_by_member_id = SYSTEM_MEMBER_ID``.
Emits ``reaper.{orchestrator,qa,running}.reaped`` counters — sustained
> 10/hr should page the oncall (brief §observability).

All three are idempotent and safe to run concurrently: the CAS update
in ``_reap_one`` ensures two reapers racing on the same task can never
both succeed.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from sqlalchemy import select, update

from ai_driven.db.postgres.models import Task, TaskOverrideAudit
from ai_driven.db.postgres.session import async_session_factory
from ai_driven.observability.metrics import emit_counter

logger = logging.getLogger(__name__)


STUCK_THRESHOLD_MINUTES = 30

# System team_member UUID seeded by migration f6a7b8c9d0e1. Background
# reapers write this as ``overridden_by_member_id`` so every audit row
# has a valid FK even though no human was involved. Duplicated here
# from auth/middleware.SYSTEM_MEMBER_ID so the reaper module has no
# dependency on the auth layer.
SYSTEM_MEMBER_ID = uuid.UUID("00000000-0000-0000-0000-000000000001")


def _parse_iso_ts(value: Any) -> datetime | None:
    """Parse a stringified ISO-8601 timestamp from output_data.

    Timestamps in output_data are written by various call sites (QA
    worker, orchestrator) and are always strings. A bad value gets
    treated as 'never set' — deliberately permissive so a malformed
    timestamp doesn't mask a stuck task indefinitely.
    """
    if not value or not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None


def _threshold_cutoff(now: datetime) -> datetime:
    return now - timedelta(minutes=STUCK_THRESHOLD_MINUTES)


async def _reap_one(
    task: Task,
    *,
    new_failure_reason: str,
    metric_name: str,
) -> bool:
    """Transition a single task to 'failed' with an audit row.

    Uses CAS on status to avoid a race with a late-arriving heartbeat
    or the admin override endpoint. Returns True if we reaped it, False
    if somebody else beat us.

    Runs in its own session so a failure on one task never taints the
    whole sweep.
    """
    task_id = task.id
    expected_status = task.status
    now = datetime.now(timezone.utc)

    merged_output = dict(task.output_data or {})
    merged_output["failure_reason"] = new_failure_reason
    merged_output["reaped_at"] = now.isoformat()

    async with async_session_factory() as session:
        result = await session.execute(
            update(Task)
            .where(Task.id == task_id, Task.status == expected_status)
            .values(
                status="failed",
                output_data=merged_output,
                completed_at=now,
            )
        )
        if (result.rowcount or 0) == 0:
            # Another writer (admin override, concurrent reaper) moved
            # the task between our read and our write. Not an error.
            return False

        audit = TaskOverrideAudit(
            id=uuid.uuid4(),
            task_id=task_id,
            overridden_by_member_id=SYSTEM_MEMBER_ID,
            from_status=expected_status,
            to_status="failed",
            action="reap",
            reason=new_failure_reason,
            created_at=now,
        )
        session.add(audit)
        await session.commit()

    emit_counter(
        metric_name,
        tags=[f"from:{expected_status}", f"reason:{new_failure_reason}"],
    )
    logger.warning(
        "reaped task=%s from_status=%s reason=%s",
        task_id, expected_status, new_failure_reason,
    )
    return True


async def reap_stuck_orchestrators() -> int:
    """Reap ``orchestrating`` tasks stuck past the threshold.

    A task is stuck if ``output_data.started_at`` is older than the
    threshold, OR ``started_at`` is unset and the row's ``created_at``
    is past the threshold (covers orchestrator crashes before the first
    marker write).

    Returns the number of tasks reaped.
    """
    now = datetime.now(timezone.utc)
    cutoff = _threshold_cutoff(now)

    async with async_session_factory() as session:
        # JSON extraction differs between Postgres (JSONB) and SQLite
        # (TEXT). To stay portable we pull the candidate rows into
        # Python and filter there — the result set is small (only
        # non-terminal orchestrating tasks) so the cost is negligible.
        result = await session.execute(
            select(Task).where(Task.status == "orchestrating")
        )
        candidates = list(result.scalars().all())

    reaped = 0
    for task in candidates:
        started = _parse_iso_ts((task.output_data or {}).get("started_at"))
        stuck = (
            (started is not None and started < cutoff)
            or (started is None and task.created_at is not None and task.created_at < cutoff)
        )
        if not stuck:
            continue
        try:
            if await _reap_one(
                task,
                new_failure_reason="orchestrator_stuck_auto_reaped",
                metric_name="reaper.orchestrator.reaped",
            ):
                reaped += 1
        except Exception:
            # Never let a single bad row crash the loop. Log and move on
            # — the next sweep will pick it up if it's really stuck.
            logger.exception("reap_stuck_orchestrators: task=%s failed", task.id)
    return reaped


async def reap_stuck_qa() -> int:
    """Reap ``awaiting_qa`` tasks whose QA enqueue is older than the
    threshold. Returns the number reaped."""
    now = datetime.now(timezone.utc)
    cutoff = _threshold_cutoff(now)

    async with async_session_factory() as session:
        result = await session.execute(
            select(Task).where(Task.status == "awaiting_qa")
        )
        candidates = list(result.scalars().all())

    reaped = 0
    for task in candidates:
        enq = _parse_iso_ts((task.output_data or {}).get("qa_enqueued_at"))
        if enq is None or enq >= cutoff:
            continue
        try:
            if await _reap_one(
                task,
                new_failure_reason="qa_stuck_auto_reaped",
                metric_name="reaper.qa.reaped",
            ):
                reaped += 1
        except Exception:
            logger.exception("reap_stuck_qa: task=%s failed", task.id)
    return reaped


async def reap_stuck_running() -> int:
    """Reap ``running`` tasks whose worker has gone silent.

    A task is stuck if ``last_heartbeat_at`` is older than the threshold
    OR ``last_heartbeat_at`` is NULL and ``created_at`` is past the
    threshold (covers workers that crashed before the first heartbeat
    write — typically 5 min into the run).
    """
    now = datetime.now(timezone.utc)
    cutoff = _threshold_cutoff(now)

    async with async_session_factory() as session:
        result = await session.execute(
            select(Task).where(Task.status == "running")
        )
        candidates = list(result.scalars().all())

    reaped = 0
    for task in candidates:
        hb = task.last_heartbeat_at
        created = task.created_at
        stuck = (
            (hb is not None and hb < cutoff)
            or (hb is None and created is not None and created < cutoff)
        )
        if not stuck:
            continue
        try:
            if await _reap_one(
                task,
                new_failure_reason="worker_stuck_auto_reaped",
                metric_name="reaper.running.reaped",
            ):
                reaped += 1
        except Exception:
            logger.exception("reap_stuck_running: task=%s failed", task.id)
    return reaped


async def reap_stuck_awaiting_input() -> int:
    """Reap tasks stuck in ``awaiting_input`` past the HITL timeout.

    Threshold is configured via
    ``Settings.hitl_awaiting_input_timeout_hours`` (default 4h, per
    HITL-v1 design). A task in ``awaiting_input`` is stuck when its
    ``task_specialist_state.updated_at`` is older than the cutoff AND
    it still has a ``pending_question``.

    On reap: transitions to ``failed`` with audit row + clears the
    ``task_specialist_state`` row so a retrigger (override rerun or
    a fresh /respond with a stale round_id) can't accidentally revive
    a dead conversation.
    """
    from ai_driven.config import get_settings as _get_settings
    from ai_driven.db.postgres.models import TaskSpecialistState
    from sqlalchemy import delete as _delete

    settings = _get_settings()
    hours = int(settings.hitl_awaiting_input_timeout_hours or 4)
    cutoff = datetime.now(timezone.utc) - timedelta(hours=hours)

    async with async_session_factory() as session:
        result = await session.execute(
            select(TaskSpecialistState).where(
                TaskSpecialistState.pending_question.isnot(None),
                TaskSpecialistState.updated_at < cutoff,
            )
        )
        stuck_rows = list(result.scalars().all())

    reaped = 0
    for row in stuck_rows:
        # Load the task — skip if no longer in awaiting_input (user
        # may have cancelled or pressed resume concurrently).
        async with async_session_factory() as session:
            task = await session.get(Task, row.task_id)
            if task is None or task.status != "awaiting_input":
                # Stale state — still clean the row so we don't scan
                # it again next tick.
                await session.execute(
                    _delete(TaskSpecialistState).where(
                        TaskSpecialistState.task_id == row.task_id,
                        TaskSpecialistState.role == row.role,
                    )
                )
                await session.commit()
                continue
        try:
            if await _reap_one(
                task,
                new_failure_reason="awaiting_input_timeout",
                metric_name="reaper.awaiting_input.reaped",
            ):
                reaped += 1
        except Exception:
            logger.exception(
                "reap_stuck_awaiting_input: task=%s role=%s failed",
                row.task_id, row.role,
            )
            continue
        # Clean up the specialist_state row post-reap.
        async with async_session_factory() as session:
            await session.execute(
                _delete(TaskSpecialistState).where(
                    TaskSpecialistState.task_id == row.task_id,
                    TaskSpecialistState.role == row.role,
                )
            )
            await session.commit()
    return reaped
