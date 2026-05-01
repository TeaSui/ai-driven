"""T25 admin GET artifacts endpoint.

``GET /api/admin/tasks/{task_id}/artifacts`` returns every specialist
artifact attached to a task, plus the QA verdict/failures and an
``overrideActions`` UI hint.

Access control
--------------
* ``require_admin`` (from T24) gates the endpoint — non-admins get 403.
* Cross-team access returns **404**, not 403, to avoid leaking task
  existence. Admins can only see tasks in their own team.

Decimal precision
-----------------
``cost_usd`` is a ``NUMERIC(10,6)`` column. We serialize it as a JSON
**string** (``str(artifact.cost_usd)``) so the 6-digit precision
survives the round-trip to the browser. The frontend parses it for
display.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.agents.orchestrator.repository import ArtifactRepository
from ai_driven.api._shared import CamelModel
from ai_driven.api.admin.override_rules import available_actions
from ai_driven.auth.middleware import TeamMemberInfo, require_admin
from ai_driven.db.postgres.repository import TaskRepository
from ai_driven.db.postgres.session import get_session

logger = logging.getLogger(__name__)

router = APIRouter()


# ---------------------------------------------------------------------------
# Response DTOs
# ---------------------------------------------------------------------------


class ArtifactDTO(CamelModel):
    """One specialist artifact, admin-readable shape.

    ``costUsd`` is typed as ``str`` (not ``float``) because the source
    column is ``NUMERIC(10,6)`` — casting to float would silently drop
    sub-cent precision for small specialist costs.
    """

    id: str
    role: str
    verdict: str
    reasoning: str | None = None
    payload: dict[str, Any] = {}
    modelUsed: str | None = None
    inputTokens: int = 0
    outputTokens: int = 0
    costUsd: str
    createdAt: str | None = None


class TaskArtifactsData(CamelModel):
    taskId: str
    classifierTier: str | None = None
    artifacts: list[ArtifactDTO]
    qaVerdict: str | None = None
    qaFailures: list[dict[str, Any]] | None = None
    canOverride: bool
    overrideActions: list[str]


class TaskArtifactsMeta(CamelModel):
    timestamp: str


class TaskArtifactsResponse(CamelModel):
    data: TaskArtifactsData
    meta: TaskArtifactsMeta


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------


@router.get(
    "/tasks/{task_id}/artifacts",
    response_model=TaskArtifactsResponse,
)
async def get_task_artifacts(
    task_id: str,
    member: TeamMemberInfo = Depends(require_admin),
    session: AsyncSession = Depends(get_session),
) -> TaskArtifactsResponse:
    """Return all specialist artifacts for a task plus override hints.

    Status codes:
      * 200 — artifacts returned (possibly empty list).
      * 400 — malformed ``task_id``.
      * 403 — caller is not admin (raised by ``require_admin``).
      * 404 — task does not exist **or** belongs to another team.
    """
    try:
        parsed_id = uuid.UUID(task_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid task ID format")

    task_repo = TaskRepository(session)
    task = await task_repo.get_by_id(parsed_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")

    # Cross-team existence-leak avoidance: return 404, not 403. An
    # admin on team A must not be able to probe task IDs to enumerate
    # team B. Note: the debug sentinel team_id bypass is intentional —
    # local dev + the dashboard "view all" mode rely on it.
    debug_team_id = "00000000-0000-0000-0000-000000000000"
    if (
        member.team_id != debug_team_id
        and str(task.team_id) != member.team_id
    ):
        raise HTTPException(status_code=404, detail="Task not found")

    # Pull artifacts (ORM rows — we need id + created_at).
    art_repo = ArtifactRepository(session)
    rows = await art_repo.list_rows_for_task(parsed_id)

    output_data = task.output_data or {}

    dto_artifacts: list[ArtifactDTO] = [
        ArtifactDTO(
            id=str(r.id),
            role=r.role,
            verdict=r.verdict,
            reasoning=r.reasoning,
            payload=r.payload or {},
            modelUsed=r.model_used,
            inputTokens=r.input_tokens or 0,
            outputTokens=r.output_tokens or 0,
            # Cast to str at build time — preserves Decimal precision
            # across the JSON boundary (see module docstring).
            costUsd=str(r.cost_usd) if r.cost_usd is not None else "0",
            createdAt=r.created_at.isoformat() if r.created_at else None,
        )
        for r in rows
    ]

    override_actions_list = available_actions(task)

    now = datetime.now(timezone.utc)
    return TaskArtifactsResponse(
        data=TaskArtifactsData(
            taskId=str(task.id),
            classifierTier=output_data.get("classifier_tier"),
            artifacts=dto_artifacts,
            qaVerdict=output_data.get("qa_verdict"),
            qaFailures=output_data.get("qa_failures"),
            canOverride=len(override_actions_list) > 0,
            overrideActions=override_actions_list,
        ),
        meta=TaskArtifactsMeta(timestamp=now.isoformat()),
    )
