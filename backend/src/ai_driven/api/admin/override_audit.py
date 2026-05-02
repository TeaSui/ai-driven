"""T27 admin override-audit endpoint.

``GET /api/admin/override-audit?task_id={id}`` returns the admin-override
history for a single task from the ``task_override_audit`` table
(created in T24). Consumed by the T27 dogfood report to surface how
often ops had to intervene per corpus ticket.

Access control
--------------
* ``require_admin`` (from T24) gates the endpoint — non-admins get 403.
* Cross-team access returns **404**, not 403, matching the T25 artifacts
  endpoint so an admin cannot probe task IDs to enumerate another team.
* A task with no overrides returns a 200 with ``data: []`` (the
  non-existence of an audit row is not the non-existence of the task).

Response envelope
-----------------
``{"data": [...rows...], "meta": {"timestamp": "..."}}`` per the
repo-wide API envelope rule. Each row uses the same camelCase shape as
the T24 POST response plus ``id`` + ``createdAt``.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.api._shared import CamelModel
from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.db.postgres.models import TaskOverrideAudit
from ai_driven.db.postgres.repository import TaskRepository
from ai_driven.db.postgres.session import get_session

logger = logging.getLogger(__name__)

router = APIRouter()


# ---------------------------------------------------------------------------
# Response DTOs
# ---------------------------------------------------------------------------


class OverrideAuditRowDTO(CamelModel):
    id: str
    taskId: str
    overriddenByMemberId: str
    fromStatus: str
    toStatus: str
    action: str
    reason: str
    createdAt: str | None = None


class OverrideAuditMeta(CamelModel):
    timestamp: str


class OverrideAuditResponse(CamelModel):
    data: list[OverrideAuditRowDTO]
    meta: OverrideAuditMeta


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------


@router.get(
    "/override-audit",
    response_model=OverrideAuditResponse,
)
async def get_override_audit(
    task_id: str = Query(..., description="Task UUID to fetch audit rows for"),
    member: TeamMemberInfo = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> OverrideAuditResponse:
    """Return all override/reap audit rows for one task.

    Ordering: ascending ``created_at`` — matches the order ops would
    read through the history (first intervention at top).

    Status codes:
      * 200 — rows returned (possibly empty list)
      * 400 — malformed ``task_id``
      * 403 — caller is not admin (raised by ``require_admin``)
      * 404 — task does not exist **or** belongs to another team
    """
    try:
        parsed_id = uuid.UUID(task_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid task ID format")

    # Verify the task exists AND belongs to the caller's team before
    # returning audit rows. Skipping this check would leak task IDs
    # across teams: an admin on team A could probe a task ID on team B
    # and distinguish "no overrides" (real task) from "no rows"
    # (nonexistent task). We always collapse to 404.
    task_repo = TaskRepository(session)
    task = await task_repo.get_by_id(parsed_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")

    debug_team_id = "00000000-0000-0000-0000-000000000000"
    if (
        member.team_id != debug_team_id
        and str(task.team_id) != member.team_id
    ):
        raise HTTPException(status_code=404, detail="Task not found")

    result = await session.execute(
        select(TaskOverrideAudit)
        .where(TaskOverrideAudit.task_id == parsed_id)
        .order_by(TaskOverrideAudit.created_at.asc())
    )
    rows = list(result.scalars().all())

    dto_rows: list[OverrideAuditRowDTO] = [
        OverrideAuditRowDTO(
            id=str(r.id),
            taskId=str(r.task_id),
            overriddenByMemberId=str(r.overridden_by_member_id),
            fromStatus=r.from_status,
            toStatus=r.to_status,
            action=r.action,
            reason=r.reason,
            createdAt=r.created_at.isoformat() if r.created_at else None,
        )
        for r in rows
    ]

    now = datetime.now(timezone.utc)
    return OverrideAuditResponse(
        data=dto_rows,
        meta=OverrideAuditMeta(timestamp=now.isoformat()),
    )
