"""T24 admin override endpoint.

POST /api/admin/tasks/{task_id}/override-block — admin escape hatch to
transition a stuck task (blocked / qa_failed / stuck orchestrating /
stuck awaiting_qa / stuck running) to a recoverable state. Every
transition writes a task_override_audit row in the same session as the
status change, so the audit trail is atomically consistent with the
status machine.

Concurrency: uses a compare-and-set UPDATE
(``WHERE id = :id AND status = :expected``). If another admin beat
us to it, rowcount == 0 and we return 409 Conflict. No version column
needed — the status itself is the CAS token.

Marker clearing: ``rerun_coding`` and ``rerun_qa`` CLEAR the
idempotency markers in output_data before re-enqueueing. Without this,
the orchestrator/QA worker short-circuits on the already-set marker
and the "rerun" becomes a no-op (see brief §B4, §B5).
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import Field
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.api._shared import CamelModel
from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.db.postgres.models import Task, TaskOverrideAudit
from ai_driven.db.postgres.repository import TaskRepository
from ai_driven.db.postgres.session import get_session
from ai_driven.observability.metrics import emit_counter
from ai_driven.queue.producer import TaskProducer

logger = logging.getLogger(__name__)

router = APIRouter()


# ---------------------------------------------------------------------------
# Stuck thresholds
# ---------------------------------------------------------------------------
# The override endpoint refuses to 'reap' a task that hasn't been stuck
# long enough. Same 30-min threshold the background reapers use, kept
# as a single constant so dashboard / runbook / reaper / endpoint can
# never drift apart.
STUCK_THRESHOLD_MINUTES = 30

# Keys cleared from output_data when action == 'rerun_coding'. Matches
# the orchestrator's idempotency short-circuits: if any of these survive
# into the re-enqueued run, the orchestrator worker will observe its
# previous result and skip. See brief §B4.
_RERUN_CODING_CLEAR_KEYS: tuple[str, ...] = (
    "orchestrator_completed_at",
    "orchestrator_halted_on",
    "classifier_tier",
    "blocked_by",
    "blocking_reasons",
    "blocker_artifact_id",
)

# Keys cleared from output_data when action == 'rerun_qa'. Matches the
# QA worker's idempotency markers. See brief §B5.
_RERUN_QA_CLEAR_KEYS: tuple[str, ...] = (
    "qa_completed_at",
    "qa_verdict",
    "qa_artifact_id",
    "qa_enqueued_at",
    "qa_failures",
)


# ---------------------------------------------------------------------------
# Request / response DTOs
# ---------------------------------------------------------------------------


class OverrideRequest(CamelModel):
    """Admin override payload.

    ``action`` picks the transition; ``reason`` is required for audit.
    Reason is capped at 500 chars because the audit table stores it as
    unbounded TEXT but a runaway field is just noise.
    """

    action: Literal["rerun_coding", "rerun_qa", "force_pass", "reap"]
    reason: str = Field(..., min_length=1, max_length=500)


class OverrideResponseData(CamelModel):
    taskId: str
    fromStatus: str
    toStatus: str
    action: str
    auditId: str


class OverrideResponseMeta(CamelModel):
    timestamp: str


class OverrideResponse(CamelModel):
    data: OverrideResponseData
    meta: OverrideResponseMeta


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _parse_iso_ts(value: Any) -> datetime | None:
    """Parse a stringified ISO-8601 timestamp from output_data. Returns
    None for absent/invalid values so callers can treat "never set" and
    "malformed" the same way."""
    if not value or not isinstance(value, str):
        return None
    try:
        # datetime.fromisoformat accepts '2026-05-01T12:00:00+00:00' but
        # not the trailing 'Z' some producers emit. Normalize first.
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None


def _is_stuck(
    ts: datetime | None, fallback: datetime | None, now: datetime
) -> bool:
    """True if either ``ts`` is older than the threshold, or (ts is None
    and ``fallback`` is older than the threshold). Mirrors the stuck
    definition in brief §reapers."""
    threshold = now - _stuck_delta()
    if ts is not None:
        return ts < threshold
    if fallback is not None:
        return fallback < threshold
    return False


def _stuck_delta():
    from datetime import timedelta
    return timedelta(minutes=STUCK_THRESHOLD_MINUTES)


def _clear_output_keys(
    current: dict[str, Any], keys: tuple[str, ...]
) -> dict[str, Any]:
    """Return a shallow copy of ``current`` with ``keys`` removed.

    Used to strip idempotency markers before re-enqueueing. We copy
    rather than mutate so the SQLAlchemy change tracker sees the
    new dict as a different value (JSON column equality is by identity
    in some backends — safer to assign a new object).
    """
    out = dict(current or {})
    for k in keys:
        out.pop(k, None)
    return out


def _validate_action_for_status(
    status: str, action: str, output_data: dict[str, Any], *,
    last_heartbeat_at: datetime | None,
    created_at: datetime | None,
    now: datetime,
) -> tuple[bool, str | None]:
    """Return (ok, detail). ``ok=False`` means the action is not legal
    for the current status — caller returns 400 with ``detail``."""
    # rerun_coding only from blocked
    if action == "rerun_coding":
        if status != "blocked":
            return (
                False,
                f"Action 'rerun_coding' is only valid for status 'blocked', "
                f"current status is '{status}'",
            )
        return True, None

    # rerun_qa and force_pass only from qa_failed
    if action == "rerun_qa":
        if status != "qa_failed":
            return (
                False,
                f"Action 'rerun_qa' is only valid for status 'qa_failed', "
                f"current status is '{status}'",
            )
        return True, None
    if action == "force_pass":
        if status != "qa_failed":
            return (
                False,
                f"Action 'force_pass' is only valid for status 'qa_failed', "
                f"current status is '{status}'",
            )
        return True, None

    # reap: only from stuck statuses AND only if actually stuck
    if action == "reap":
        if status == "orchestrating":
            started = _parse_iso_ts(output_data.get("started_at"))
            if not _is_stuck(started, created_at, now):
                return (
                    False,
                    f"Task is not stuck long enough to reap "
                    f"(threshold: {STUCK_THRESHOLD_MINUTES} min)",
                )
            return True, None
        if status == "awaiting_qa":
            enq = _parse_iso_ts(output_data.get("qa_enqueued_at"))
            if not _is_stuck(enq, None, now):
                return (
                    False,
                    f"Task is not stuck long enough to reap "
                    f"(threshold: {STUCK_THRESHOLD_MINUTES} min)",
                )
            return True, None
        if status == "running":
            if not _is_stuck(last_heartbeat_at, created_at, now):
                return (
                    False,
                    f"Task is not stuck long enough to reap "
                    f"(threshold: {STUCK_THRESHOLD_MINUTES} min)",
                )
            return True, None
        return (
            False,
            f"Action 'reap' is only valid for statuses "
            f"('orchestrating', 'awaiting_qa', 'running'), "
            f"current status is '{status}'",
        )

    return False, f"Unknown action '{action}'"


async def _cas_update_status(
    session: AsyncSession,
    task_id: uuid.UUID,
    expected_status: str,
    new_status: str,
    output_data_patch: dict[str, Any] | None = None,
    completed_at: datetime | None = None,
) -> bool:
    """Compare-and-set status update.

    Returns True if the row was updated (status was still
    ``expected_status``), False if another writer beat us (409).

    Using UPDATE ... WHERE id = :id AND status = :expected is the
    right primitive here: no version column needed, the status itself
    is the compare token, and the DB guarantees atomicity without a
    read-modify-write window. A read-modify-write in the same session
    would still race with a concurrent writer because SQLAlchemy's
    default isolation won't re-read the row between the get and the
    update.
    """
    values: dict[str, Any] = {"status": new_status}
    if output_data_patch is not None:
        values["output_data"] = output_data_patch
    if completed_at is not None:
        values["completed_at"] = completed_at

    result = await session.execute(
        update(Task)
        .where(Task.id == task_id, Task.status == expected_status)
        .values(**values)
    )
    # rowcount is reliably populated for UPDATE in asyncpg / psycopg;
    # on SQLite (tests) it's also populated. Treat 0 as a CAS miss.
    return (result.rowcount or 0) > 0


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------


@router.post(
    "/tasks/{task_id}/override-block",
    response_model=OverrideResponse,
)
async def override_block(
    task_id: str,
    body: OverrideRequest,
    request: Request,
    member: TeamMemberInfo = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> OverrideResponse:
    """Admin escape hatch for stuck / blocked / qa_failed tasks.

    See module docstring for behavior matrix.

    Response:
      * 200 — override applied, audit row written
      * 400 — action not legal for current status OR task not stuck enough
      * 403 — caller is not admin/owner (from ``require_admin``)
      * 404 — task does not exist
      * 409 — concurrent override (CAS lost)
      * 422 — missing/empty reason (from pydantic)
    """
    try:
        parsed_id = uuid.UUID(task_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid task ID format")

    task_repo = TaskRepository(session)
    task = await task_repo.get_by_id(parsed_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")

    from_status = task.status
    output_data = dict(task.output_data or {})
    now = datetime.now(timezone.utc)

    ok, detail = _validate_action_for_status(
        status=from_status,
        action=body.action,
        output_data=output_data,
        last_heartbeat_at=task.last_heartbeat_at,
        created_at=task.created_at,
        now=now,
    )
    if not ok:
        raise HTTPException(status_code=400, detail=detail)

    # --- Compute new status + output_data patch per action ---
    new_status: str
    output_patch: dict[str, Any] | None = None
    completed_at: datetime | None = None
    needs_enqueue_agent: str | None = None

    if body.action == "rerun_coding":
        new_status = "queued"
        output_patch = _clear_output_keys(output_data, _RERUN_CODING_CLEAR_KEYS)
        needs_enqueue_agent = "coding"
    elif body.action == "rerun_qa":
        new_status = "awaiting_qa"
        output_patch = _clear_output_keys(output_data, _RERUN_QA_CLEAR_KEYS)
        needs_enqueue_agent = "qa"
    elif body.action == "force_pass":
        new_status = "completed"
        # Record that this was an admin override so downstream consumers
        # (dashboards, billing) can distinguish from a genuine pass.
        merged = dict(output_data)
        merged["admin_force_pass"] = True
        merged["admin_force_pass_at"] = now.isoformat()
        output_patch = merged
        completed_at = now
    elif body.action == "reap":
        new_status = "failed"
        merged = dict(output_data)
        # Reason string is tied to which stuck state we're reaping from.
        # Matches the reaper's output for symmetry.
        reason_key = {
            "orchestrating": "orchestrator_stuck_reaped",
            "awaiting_qa": "qa_stuck_reaped",
            "running": "worker_stuck_reaped",
        }.get(from_status, "stuck_reaped")
        merged["failure_reason"] = reason_key
        output_patch = merged
        completed_at = now
    else:  # pragma: no cover — validator above rejects unknown actions
        raise HTTPException(status_code=400, detail="Unknown action")

    # --- CAS update + audit row in same session/commit ---
    updated = await _cas_update_status(
        session=session,
        task_id=parsed_id,
        expected_status=from_status,
        new_status=new_status,
        output_data_patch=output_patch,
        completed_at=completed_at,
    )
    if not updated:
        # Another admin (or the orchestrator) moved the task between
        # our read and our write. Fail closed with 409; the caller can
        # refresh and retry.
        raise HTTPException(
            status_code=409,
            detail=(
                "Task status changed concurrently; refresh and retry."
            ),
        )

    audit = TaskOverrideAudit(
        id=uuid.uuid4(),
        task_id=parsed_id,
        overridden_by_member_id=uuid.UUID(member.member_id),
        from_status=from_status,
        to_status=new_status,
        action=body.action,
        reason=body.reason,
        created_at=now,
    )
    session.add(audit)
    await session.commit()

    # Observability counter. Dimensionality is intentional — the brief
    # calls out auditing patterns like
    # ``admin.override.qa_failed.force_pass`` for weekly review.
    emit_counter(
        f"admin.override.{from_status}.{body.action}",
        tags=[f"from:{from_status}", f"to:{new_status}", f"action:{body.action}"],
    )

    # Re-enqueue downstream task if the action requires it. We do this
    # AFTER commit so the audit/status change is persisted even if the
    # producer hiccups — better to have a stuck re-enqueue with a clean
    # status than a silent half-move.
    if needs_enqueue_agent:
        try:
            producer = TaskProducer()
            payload = (task.input_data or {}).get("payload") or task.input_data or {}
            await producer.enqueue_task(
                task_id=str(parsed_id),
                agent_type=needs_enqueue_agent,
                payload=payload,
            )
        except Exception as ex:
            # Don't roll back — the audit row records intent, the
            # operator can re-issue with a new override if enqueue
            # truly failed. Log loudly so the dashboard picks it up.
            logger.warning(
                "override_enqueue_failed task=%s agent=%s err=%s",
                task_id, needs_enqueue_agent, ex,
            )
            emit_counter(
                "admin.override.enqueue_failed",
                tags=[f"agent:{needs_enqueue_agent}"],
            )

    return OverrideResponse(
        data=OverrideResponseData(
            taskId=str(parsed_id),
            fromStatus=from_status,
            toStatus=new_status,
            action=body.action,
            auditId=str(audit.id),
        ),
        meta=OverrideResponseMeta(timestamp=now.isoformat()),
    )
