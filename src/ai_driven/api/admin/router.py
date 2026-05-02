"""Admin API — team and configuration management."""

from __future__ import annotations

import logging
import re
import secrets
import uuid
from datetime import date as date_type, datetime, timedelta, timezone
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, field_validator
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.auth.middleware import hash_api_key
from ai_driven.billing.tracker import BudgetStatus, CostTracker, DailyUsage
from ai_driven.db.postgres.repository import RepoRepository, TaskRepository, TeamRepository
from ai_driven.db.postgres.session import get_session
from ai_driven.domain.enums import IncidentSeverity

logger = logging.getLogger(__name__)
router = APIRouter()

# T24: admin override endpoint. Defined in its own module for length
# (override.py) but mounted onto the same /api/admin router so callers
# see a single admin surface.
from ai_driven.api.admin.override import router as _override_router  # noqa: E402
router.include_router(_override_router)

# T25: admin GET artifacts endpoint. Same pattern — separate module,
# mounted here so the whole admin surface is one router.
from ai_driven.api.admin.artifacts import router as _artifacts_router  # noqa: E402
router.include_router(_artifacts_router)

# T27: admin GET override-audit endpoint for dogfood report consumption.
# Same mount pattern — the dogfood harness queries this per ticket to
# count how often ops had to intervene.
from ai_driven.api.admin.override_audit import router as _override_audit_router  # noqa: E402
router.include_router(_override_audit_router)

# ---- Validation constants ----
_FULL_NAME_PATTERN = re.compile(r"^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$")
_VALID_SCM_TYPES = {"github", "bitbucket"}

# ---- Task-status buckets (T23) ----
# Shared membership sets for admin success-rate math and bucket
# filters. Post-T23 live-mode, orchestrated tasks flow through
# ``awaiting_qa`` before settling on either ``completed`` (success)
# or ``qa_failed`` (failure). The pre-T23 code filtered on
# ``status == "completed"`` / ``status == "failed"`` only, which
# silently under-counted the failure bucket and put any task in
# flight for QA into neither bucket.
#
# TERMINAL_STATUSES — every status that means the pipeline stopped.
#   "completed"  — success (pass / pass_with_notes / lenient error)
#   "failed"     — infra / agent-level failure
#   "blocked"    — strict-mode pre-coding block
#   "qa_failed"  — live-mode post-coding block (T23)
#
# IN_FLIGHT_STATUSES — not yet terminal; excluded from success-rate
# math and included in dashboards' "active" buckets.
#   "queued"       — on SQS, not yet picked up
#   "running"      — coding agent executing
#   "awaiting_qa"  — post-coding QA in flight (T22)
#
# SUCCESS_STATUSES is deliberately a one-element set (not a union)
# so success-rate math is obvious: success / (success + failures).
SUCCESS_STATUSES = frozenset({"completed"})
FAILURE_STATUSES = frozenset({"failed", "blocked", "qa_failed"})
TERMINAL_STATUSES = SUCCESS_STATUSES | FAILURE_STATUSES
IN_FLIGHT_STATUSES = frozenset({"queued", "running", "awaiting_qa"})


from ai_driven.api._shared import CamelModel, to_camel as _to_camel  # noqa: F401


# ---- Team DTOs ----


class TeamCreate(CamelModel):
    name: str
    claudeProvider: str = "anthropic"
    maxConcurrentTasks: int = 5


class TeamResponse(CamelModel):
    id: str
    name: str
    claudeProvider: str
    maxConcurrentTasks: int
    apiKey: str | None = None


class TaskListResponse(BaseModel):
    tasks: list[dict[str, Any]]
    total: int


# ---- Repository DTOs ----


class RepoCreate(CamelModel):
    fullName: str
    scmType: str = "github"
    defaultBranch: str = "main"

    @field_validator("fullName")
    @classmethod
    def validate_full_name(cls, v: str) -> str:
        if not _FULL_NAME_PATTERN.match(v):
            raise ValueError("fullName must be in 'org/repo' format using alphanumeric, dot, dash, or underscore characters")
        return v

    @field_validator("scmType")
    @classmethod
    def validate_scm_type(cls, v: str) -> str:
        if v not in _VALID_SCM_TYPES:
            raise ValueError(f"scmType must be one of: {', '.join(sorted(_VALID_SCM_TYPES))}")
        return v


class RepoUpdate(CamelModel):
    defaultBranch: str | None = None
    settingsJson: dict[str, Any] | None = None
    # Allow reactivating a soft-deleted repository. Setting isActive=False
    # is also allowed but is_equivalent to DELETE /repos/{id} (which is the
    # preferred path for the UI).
    isActive: bool | None = None


class RepoResponse(CamelModel):
    id: str
    teamId: str
    fullName: str
    scmType: str
    defaultBranch: str
    isActive: bool
    createdAt: str | None = None


class RepoListResponse(BaseModel):
    data: list[RepoResponse]
    total: int


# ---- Usage DTOs ----


class UsageResponse(BaseModel):
    teamId: str
    date: str
    inputTokens: int
    outputTokens: int
    totalCostUsd: float
    budget: BudgetInfo


class BudgetInfo(BaseModel):
    withinBudget: bool
    usagePercent: float
    dailyBudgetUsd: float
    spentUsd: float
    warning: bool


# ---- Dashboard / Usage History DTOs ----


class ActiveAgentsResponse(CamelModel):
    coding: int = 0
    review: int = 0
    workflow: int = 0
    devops: int = 0


class TotalTokensResponse(CamelModel):
    input: int = 0
    output: int = 0


class DashboardSummaryResponse(CamelModel):
    tasksToday: int
    tasksYesterday: int
    successRate: float
    activeAgents: ActiveAgentsResponse
    totalTokens: TotalTokensResponse
    costToday: float
    dailyBudget: float
    budgetUsagePercent: float


class UsageHistoryItem(CamelModel):
    date: str
    inputTokens: int
    outputTokens: int
    costUsd: float


class UsageByAgentItem(CamelModel):
    agentType: str
    totalTokens: int
    totalCost: float
    taskCount: int


class TeamListItem(CamelModel):
    id: str
    name: str
    repositories: list[RepoResponse]


class TeamListResponse(BaseModel):
    data: list[TeamListItem]
    total: int


@router.get("/teams", response_model=list[TeamListItem])
async def list_teams(
    session: AsyncSession = Depends(get_session),
) -> list[TeamListItem]:
    """List all active teams with their repositories."""
    team_repo = TeamRepository(session)
    repo_repo = RepoRepository(session)
    teams = await team_repo.list_all()

    result = []
    for team in teams:
        repos = await repo_repo.list_for_team(team.id)
        result.append(TeamListItem(
            id=str(team.id),
            name=team.name,
            repositories=[_repo_to_response(r) for r in repos],
        ))
    return result


@router.post("/teams", response_model=TeamResponse)
async def create_team(
    body: TeamCreate,
    session: AsyncSession = Depends(get_session),
) -> TeamResponse:
    """Create a new team and generate an API key."""
    api_key = f"aidrv_{secrets.token_urlsafe(32)}"

    repo = TeamRepository(session)
    team = await repo.create(
        name=body.name,
        api_key_hash=hash_api_key(api_key),
        claude_provider=body.claudeProvider,
        max_concurrent_tasks=body.maxConcurrentTasks,
    )

    return TeamResponse(
        id=str(team.id),
        name=team.name,
        claudeProvider=team.claude_provider,
        maxConcurrentTasks=team.max_concurrent_tasks,
        apiKey=api_key,  # Only returned on creation
    )


@router.delete("/teams/{team_id}", status_code=204)
async def delete_team(
    team_id: str,
    session: AsyncSession = Depends(get_session),
) -> None:
    """Soft-delete a team (set is_active=False)."""
    try:
        parsed_id = uuid.UUID(team_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid team ID format")

    team_repo = TeamRepository(session)
    team = await team_repo.get_by_id(parsed_id)
    if not team or not team.is_active:
        raise HTTPException(status_code=404, detail="Team not found")

    await team_repo.soft_delete(parsed_id)
    await session.commit()


class KeyRotateResponse(CamelModel):
    apiKey: str
    oldKeyExpiresAt: str
    teamId: str


@router.post("/teams/{team_id}/rotate-key", response_model=KeyRotateResponse)
async def rotate_api_key(
    request: Request,
    team_id: str,
    session: AsyncSession = Depends(get_session),
) -> KeyRotateResponse:
    """Rotate a team's API key. Old key gets a 48h grace period."""
    team = await _get_team_from_request(request, session)
    if str(team.id) != team_id:
        raise HTTPException(status_code=404, detail="Team not found")

    import redis.asyncio as aioredis
    from datetime import timedelta

    # Generate new key
    new_api_key = f"aidrv_{secrets.token_urlsafe(32)}"
    old_hash = team.api_key_hash
    new_hash = hash_api_key(new_api_key)

    # Store old hash in Redis with 48h grace period
    from ai_driven.config import get_settings
    grace_ttl = int(timedelta(hours=48).total_seconds())
    settings = get_settings()
    try:
        redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
        await redis_client.set(f"grace_key:{old_hash}", str(team.id), ex=grace_ttl)
        logger.info(
            "API key rotated for team %s — old key prefix %s has 48h grace",
            team.id, old_hash[:8],
        )
    except Exception:
        logger.warning("Redis unavailable — grace period will not work for old key")

    # Update team with new hash
    from sqlalchemy import update as sql_update
    from ai_driven.db.postgres.models import Team as TeamModel
    await session.execute(
        sql_update(TeamModel).where(TeamModel.id == team.id).values(api_key_hash=new_hash)
    )
    await session.commit()

    expires_at = (datetime.now(timezone.utc) + timedelta(hours=48)).isoformat()

    return KeyRotateResponse(
        apiKey=new_api_key,
        oldKeyExpiresAt=expires_at,
        teamId=str(team.id),
    )


@router.get("/tasks", response_model=TaskListResponse)
async def list_tasks(
    request: Request,
    limit: int = 50,
    offset: int = 0,
    session: AsyncSession = Depends(get_session),
) -> TaskListResponse:
    """List tasks for the authenticated team (or all tasks in debug mode)."""
    from ai_driven.config import get_settings

    task_repo = TaskRepository(session)
    team_id_str = getattr(request.state, "team_id", None)

    # In debug mode with the sentinel team ID, list all tasks
    settings = get_settings()
    if settings.debug and team_id_str == "00000000-0000-0000-0000-000000000000":
        from sqlalchemy import select
        from ai_driven.db.postgres.models import Task as TaskModel

        result = await session.execute(
            select(TaskModel).order_by(TaskModel.created_at.desc()).limit(limit).offset(offset)
        )
        tasks = list(result.scalars().all())
    else:
        # Resolve team — auth middleware has set either (a) team_id via the
        # session-token path (browser /login) or (b) api_key_hash via the
        # X-API-Key header path (agents, webhooks, scripts). Try session
        # path first, then fall back to api_key_hash.
        team_id_uuid: uuid.UUID | None = None
        if team_id_str:
            try:
                team_id_uuid = uuid.UUID(team_id_str)
            except ValueError:
                team_id_uuid = None

        if team_id_uuid is None:
            api_key_hash = getattr(request.state, "api_key_hash", None)
            if not api_key_hash:
                return TaskListResponse(tasks=[], total=0)

            team_repo = TeamRepository(session)
            team = await team_repo.get_by_api_key_hash(api_key_hash)
            if not team:
                return TaskListResponse(tasks=[], total=0)
            team_id_uuid = team.id

        tasks = await task_repo.list_for_team(team_id_uuid, limit=limit, offset=offset)

    def _task_duration_ms(t: Any) -> int | None:
        if t.started_at and t.completed_at:
            return int((t.completed_at - t.started_at).total_seconds() * 1000)
        return None

    return TaskListResponse(
        tasks=[
            {
                "id": str(t.id),
                "jiraKey": t.jira_key,
                "agentType": t.agent_type,
                "status": t.status,
                "title": t.title,
                "createdAt": t.created_at.isoformat() if t.created_at else None,
                "durationMs": _task_duration_ms(t),
                "tokenUsage": t.total_tokens or None,
                "costUsd": round(t.total_cost_usd, 4) if t.total_cost_usd else None,
                "iterations": t.iterations or None,
            }
            for t in tasks
        ],
        total=len(tasks),
    )


@router.get("/dashboard", response_model=DashboardSummaryResponse)
@router.get("/dashboard/summary", response_model=DashboardSummaryResponse)
async def get_dashboard_summary(
    session: AsyncSession = Depends(get_session),
) -> DashboardSummaryResponse:
    """Return KPI data for the admin dashboard."""
    from ai_driven.db.postgres.models import Task as TaskModel

    now_utc = datetime.now(timezone.utc)
    today_start = now_utc.replace(hour=0, minute=0, second=0, microsecond=0)
    yesterday_start = today_start - timedelta(days=1)

    # --- Today's task counts ---
    today_total_result = await session.execute(
        select(func.count(TaskModel.id)).where(TaskModel.created_at >= today_start)
    )
    tasks_today = today_total_result.scalar() or 0

    # --- Yesterday's task count ---
    yesterday_total_result = await session.execute(
        select(func.count(TaskModel.id)).where(
            TaskModel.created_at >= yesterday_start,
            TaskModel.created_at < today_start,
        )
    )
    tasks_yesterday = yesterday_total_result.scalar() or 0

    # --- Success rate for today ---
    # T23: bucket on SUCCESS_STATUSES vs FAILURE_STATUSES. Pre-T23 code
    # compared only to "completed" / "failed"; post-T23 live-mode adds
    # qa_failed (block verdict) and blocked (strict pre-coding) to the
    # failure bucket. awaiting_qa is in-flight — deliberately excluded.
    completed_result = await session.execute(
        select(func.count(TaskModel.id)).where(
            TaskModel.created_at >= today_start,
            TaskModel.status.in_(SUCCESS_STATUSES),
        )
    )
    completed_count = completed_result.scalar() or 0

    failed_result = await session.execute(
        select(func.count(TaskModel.id)).where(
            TaskModel.created_at >= today_start,
            TaskModel.status.in_(FAILURE_STATUSES),
        )
    )
    failed_count = failed_result.scalar() or 0

    total_terminal = completed_count + failed_count
    success_rate = round((completed_count / total_terminal) * 100, 1) if total_terminal > 0 else 0.0

    # --- Active agents (tasks in active statuses, grouped by agent_type) ---
    # T23: include "awaiting_qa" via IN_FLIGHT_STATUSES so tasks in the
    # post-coding QA gate appear in the "active" dashboard rather than
    # silently dropping off. Legacy names ("planning", "coding",
    # "testing") are kept as a no-op safety net — they were never
    # emitted by the current orchestrator but appeared in pre-T22
    # fixtures; dropping them would be an unrelated cleanup.
    active_statuses = tuple(IN_FLIGHT_STATUSES) + ("planning", "coding", "testing")
    active_result = await session.execute(
        select(TaskModel.agent_type, func.count(TaskModel.id))
        .where(TaskModel.status.in_(active_statuses))
        .group_by(TaskModel.agent_type)
    )
    active_counts: dict[str, int] = {}
    for agent_type, count in active_result.all():
        active_counts[agent_type] = count

    active_agents = ActiveAgentsResponse(
        coding=active_counts.get("coding", 0),
        review=active_counts.get("review", 0),
        workflow=active_counts.get("workflow", 0),
        devops=active_counts.get("devops", 0),
    )

    # --- Token totals from DB for today ---
    token_sum_result = await session.execute(
        select(func.coalesce(func.sum(TaskModel.total_tokens), 0)).where(
            TaskModel.created_at >= today_start
        )
    )
    _db_total_tokens = token_sum_result.scalar() or 0

    # --- Cost and budget from CostTracker (Redis) ---
    debug_team_id = "00000000-0000-0000-0000-000000000000"
    tracker = CostTracker()
    usage = await tracker.get_daily_usage(debug_team_id)
    budget = await tracker.check_budget(debug_team_id)

    total_tokens = TotalTokensResponse(
        input=usage.input_tokens,
        output=usage.output_tokens,
    )

    return DashboardSummaryResponse(
        tasksToday=tasks_today,
        tasksYesterday=tasks_yesterday,
        successRate=success_rate,
        activeAgents=active_agents,
        totalTokens=total_tokens,
        costToday=round(budget.spent_usd, 2),
        dailyBudget=budget.daily_budget_usd,
        budgetUsagePercent=round(budget.usage_percent, 1),
    )


@router.get("/usage/history", response_model=list[UsageHistoryItem])
async def get_usage_history(
    days: int = Query(default=7, ge=1, le=30),
) -> list[UsageHistoryItem]:
    """Return daily token/cost data for the last N days (default 7, max 30)."""
    debug_team_id = "00000000-0000-0000-0000-000000000000"
    tracker = CostTracker()

    today = date_type.today()
    result: list[UsageHistoryItem] = []

    for i in range(days - 1, -1, -1):
        day = today - timedelta(days=i)
        day_str = day.isoformat()
        usage = await tracker.get_daily_usage(debug_team_id, date=day_str)
        result.append(
            UsageHistoryItem(
                date=day_str,
                inputTokens=usage.input_tokens,
                outputTokens=usage.output_tokens,
                costUsd=round(usage.total_cost_usd, 4),
            )
        )

    return result


@router.get("/usage/by-agent", response_model=list[UsageByAgentItem])
async def get_usage_by_agent(
    session: AsyncSession = Depends(get_session),
) -> list[UsageByAgentItem]:
    """Return cost breakdown by agent type for today's tasks."""
    from ai_driven.db.postgres.models import Task as TaskModel

    today_start = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)

    result = await session.execute(
        select(
            TaskModel.agent_type,
            func.coalesce(func.sum(TaskModel.total_tokens), 0),
            func.coalesce(func.sum(TaskModel.total_cost_usd), 0.0),
            func.count(TaskModel.id),
        )
        .where(TaskModel.created_at >= today_start)
        .group_by(TaskModel.agent_type)
    )

    items: list[UsageByAgentItem] = []
    for agent_type, total_tokens, total_cost, task_count in result.all():
        items.append(
            UsageByAgentItem(
                agentType=agent_type,
                totalTokens=int(total_tokens),
                totalCost=round(float(total_cost), 4),
                taskCount=int(task_count),
            )
        )

    return items


@router.get("/tasks/{task_id}")
async def get_task(
    task_id: str,
    session: AsyncSession = Depends(get_session),
) -> dict[str, Any]:
    """Get a single task by ID with full details."""
    try:
        parsed_id = uuid.UUID(task_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid task ID format")

    task_repo = TaskRepository(session)
    task = await task_repo.get_by_id(parsed_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    output = task.output_data or {}

    duration_ms = None
    if task.started_at and task.completed_at:
        duration_ms = int((task.completed_at - task.started_at).total_seconds() * 1000)

    return {
        "id": str(task.id),
        "jiraKey": task.jira_key,
        "agentType": task.agent_type,
        "status": task.status,
        "title": task.title,
        "createdAt": task.created_at.isoformat() if task.created_at else None,
        "durationMs": duration_ms,
        "tokenUsage": task.total_tokens,
        "costUsd": task.total_cost_usd,
        "iterations": task.iterations or output.get("iterations"),
        "prUrl": output.get("pr_url"),
        "jiraUrl": _build_jira_url(task.jira_key),
        "conversationLog": [],
    }


@router.get("/tasks/{task_id}/evaluations")
async def get_task_evaluations(
    task_id: str,
    session: AsyncSession = Depends(get_session),
) -> list[dict[str, Any]]:
    """Get evaluation results for a task."""
    try:
        parsed_id = uuid.UUID(task_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid task ID format")

    task_repo = TaskRepository(session)
    task = await task_repo.get_by_id(parsed_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    from ai_driven.evaluation.models import TaskEvaluation

    result = await session.execute(
        select(TaskEvaluation)
        .where(TaskEvaluation.task_id == parsed_id)
        .order_by(TaskEvaluation.created_at)
    )
    evaluations = list(result.scalars().all())

    return [
        {
            "id": str(e.id),
            "dimension": e.dimension,
            "score": e.score,
            "reasoning": e.reasoning,
            "strengths": e.strengths or [],
            "weaknesses": e.weaknesses or [],
            "grader_model": e.grader_model,
            "created_at": e.created_at.isoformat() if e.created_at else None,
        }
        for e in evaluations
    ]


@router.post("/tasks/{task_id}/evaluate")
async def trigger_evaluation(
    task_id: str,
    session: AsyncSession = Depends(get_session),
) -> list[dict[str, Any]]:
    """Manually trigger evaluation for a task."""
    try:
        parsed_id = uuid.UUID(task_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid task ID format")

    task_repo = TaskRepository(session)
    task = await task_repo.get_by_id(parsed_id)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    if task.status != "completed":
        raise HTTPException(
            status_code=400,
            detail="Task must be in 'completed' status to evaluate",
        )

    import json as _json

    task_input_str = _json.dumps(task.input_data) if task.input_data else ""
    agent_output_str = _json.dumps(task.output_data) if task.output_data else ""

    if not task_input_str or not agent_output_str:
        raise HTTPException(
            status_code=400,
            detail="Task has no input or output data to evaluate",
        )

    from ai_driven.evaluation.runner import EvaluationRunner

    runner = EvaluationRunner(session)
    results = await runner.evaluate_task(parsed_id, task_input_str, agent_output_str)
    await session.commit()

    return [
        {
            "dimension": r.dimension,
            "score": r.score,
            "reasoning": r.reasoning,
            "strengths": r.strengths,
            "weaknesses": r.weaknesses,
            "grader_model": r.grader_model,
        }
        for r in results
    ]


@router.get("/tasks/{task_id}/events")
async def get_task_events(task_id: str) -> list[dict[str, Any]]:
    """Return stored agent events for a task from Redis."""
    import json as _json

    import redis.asyncio as aioredis
    from ai_driven.config import get_settings

    settings = get_settings()
    try:
        client = aioredis.from_url(settings.redis_url, decode_responses=True)
        raw_events = await client.lrange(f"task:{task_id}:event_log", 0, -1)
        return [_json.loads(e) for e in raw_events]
    except Exception:
        return []


async def _load_task_events(task_id: str) -> list[dict[str, Any]]:
    """Load all persisted agent events for a task from Redis."""
    import json as _json

    import redis.asyncio as aioredis
    from ai_driven.config import get_settings

    settings = get_settings()
    client = aioredis.from_url(settings.redis_url, decode_responses=True)
    try:
        raw_events = await client.lrange(f"task:{task_id}:event_log", 0, -1)
    except Exception:
        return []
    out: list[dict[str, Any]] = []
    for raw in raw_events:
        try:
            out.append(_json.loads(raw))
        except Exception:
            continue
    return out


async def _resolve_task_scm_type(task_id: str) -> str:
    """Return ``"github"`` or ``"bitbucket"`` for a task.

    Priority:
      1. The ``Repository.scm_type`` row the task is linked to in Postgres
         via ``Task.repo_id``.
      2. If ``Task.repo_id`` is null (older tasks), look up
         ``Task.input_data["repo_full_name"]`` and match it to a registered
         ``Repository`` row.
      3. Fallback ``"github"`` for backwards compatibility.
    """
    try:
        parsed = uuid.UUID(task_id)
    except ValueError:
        return "github"
    from ai_driven.db.postgres.session import async_session_factory
    from ai_driven.db.postgres.models import Task as TaskRow, Repository as RepoRow

    async with async_session_factory() as session:
        # (1) Direct repo_id join
        res = await session.execute(
            select(RepoRow.scm_type).join(TaskRow, TaskRow.repo_id == RepoRow.id)
            .where(TaskRow.id == parsed)
        )
        row = res.scalar_one_or_none()
        if row:
            return row

        # (2) Fallback: resolve via repo_full_name pulled from the task's
        # input_data. Covers tasks created before chat router linked repo_id.
        res2 = await session.execute(
            select(TaskRow.input_data).where(TaskRow.id == parsed)
        )
        task_input = res2.scalar_one_or_none() or {}
        repo_full_name = (task_input or {}).get("repo_full_name", "")
        if repo_full_name:
            res3 = await session.execute(
                select(RepoRow.scm_type).where(RepoRow.full_name == repo_full_name)
            )
            row2 = res3.scalar_one_or_none()
            if row2:
                return row2
    return "github"


def _extract_repo_and_ref_for_path(
    events: list[dict[str, Any]], path: str
) -> tuple[str | None, str | None]:
    """Walk events backwards, find the most recent file.write for `path`."""
    import json as _json

    repo_full_name: str | None = None
    ref: str | None = None
    for e in reversed(events):
        if e.get("type") != "tool_call_start":
            continue
        d = e.get("data", {}) or {}
        tool = d.get("tool") or d.get("tool_name") or ""
        raw_input = d.get("input_data") or "{}"
        try:
            inp = _json.loads(raw_input) if isinstance(raw_input, str) else raw_input
        except Exception:
            inp = {}
        if tool in ("file.write", "repo.write_file") and inp.get("path") == path:
            repo_full_name = inp.get("repo_full_name") or repo_full_name
            ref = inp.get("branch") or ref
            if repo_full_name and ref:
                break
        if tool in ("git.create_branch", "create_branch") and not ref:
            ref = inp.get("branch_name") or inp.get("branch")
        if tool in ("file.write", "repo.write_file") and not repo_full_name:
            repo_full_name = inp.get("repo_full_name")
    return repo_full_name, ref


def _extract_repo_and_pr(events: list[dict[str, Any]]) -> tuple[str | None, int | None]:
    """Walk events, find the repo + PR number. Handles github + bitbucket tool names."""
    import json as _json

    repo_full_name: str | None = None
    pr_number: int | None = None
    pr_tools = {
        "github.create_pr", "create_pr", "bitbucket.create_pr", "scm.create_pr",
    }
    for e in reversed(events):
        d = e.get("data", {}) or {}
        tool = d.get("tool") or d.get("tool_name") or ""
        if e.get("type") == "tool_call_end" and tool in pr_tools:
            try:
                r = _json.loads(d.get("result_data") or "{}")
                pr_number = r.get("pr_number") or r.get("number") or r.get("id") or pr_number
            except Exception:
                pass
        if e.get("type") == "tool_call_start":
            try:
                inp = _json.loads(d.get("input_data") or "{}")
            except Exception:
                inp = {}
            repo_full_name = inp.get("repo_full_name") or repo_full_name
        if repo_full_name and pr_number:
            break
    return repo_full_name, pr_number


@router.get("/tasks/{task_id}/diff")
async def get_task_diff(
    task_id: str,
    path: str,
) -> dict[str, Any]:
    """Return the unified diff patch for `path` in this task's PR.

    Picks GitHub or Bitbucket based on the linked `Repository.scm_type`.
    Falls back to GitHub for tasks without a repo row (legacy).
    """
    from ai_driven.scm.factory import get_scm_client_from_settings

    events = await _load_task_events(task_id)
    repo_full_name, pr_number = _extract_repo_and_pr(events)

    if not repo_full_name or not pr_number:
        raise HTTPException(status_code=404, detail="Could not determine repo/PR for this task")

    scm_type = await _resolve_task_scm_type(task_id)
    try:
        scm = get_scm_client_from_settings(scm_type)
    except Exception as ex:
        raise HTTPException(status_code=503, detail=str(ex))

    try:
        diff = await scm.get_pr_diff(repo_full_name, pr_number)
    except Exception as ex:
        raise HTTPException(
            status_code=404,
            detail=f"{scm_type} get_pr_diff failed: {type(ex).__name__}: {str(ex)[:200]}",
        )
    finally:
        await scm.close()

    match = next((f for f in diff.files if f.path == path), None)
    if match is None:
        raise HTTPException(status_code=404, detail=f"file {path} not in PR #{pr_number}")

    return {
        "path": match.path,
        "status": match.status,
        "additions": match.additions,
        "deletions": match.deletions,
        "patch": match.patch,
        "repo": repo_full_name,
        "prNumber": pr_number,
        "provider": scm_type,
    }


@router.get("/tasks/{task_id}/file")
async def get_task_file(
    task_id: str,
    path: str,
) -> dict[str, Any]:
    """Return the live contents of a file the agent touched.

    Reads from the actual SCM provider the task's repo is registered under
    (GitHub or Bitbucket Cloud). For legacy tasks with no `Repository` row,
    falls back to GitHub.
    """
    from ai_driven.scm.factory import get_scm_client_from_settings

    events = await _load_task_events(task_id)
    repo_full_name, ref = _extract_repo_and_ref_for_path(events, path)
    if not repo_full_name or not ref:
        raise HTTPException(
            status_code=404,
            detail="Could not determine repo/branch for this task from event log",
        )

    scm_type = await _resolve_task_scm_type(task_id)
    try:
        scm = get_scm_client_from_settings(scm_type)
    except Exception as ex:
        raise HTTPException(status_code=503, detail=str(ex))

    try:
        file_content = await scm.read_file(repo_full_name, path, ref=ref)
    except Exception as ex:
        raise HTTPException(
            status_code=404,
            detail=f"{scm_type} read_file failed: {type(ex).__name__}: {str(ex)[:200]}",
        )
    finally:
        await scm.close()

    return {
        "path": file_content.path,
        "content": file_content.content,
        "size": file_content.size,
        "repo": repo_full_name,
        "ref": ref,
        "provider": scm_type,
    }


# ---- Session / identity ----


class MeResponse(CamelModel):
    teamId: str
    teamName: str


# ---- Data Export / Deletion (GDPR / compliance) ----


class DataExportResponse(CamelModel):
    teamId: str
    exportedAt: str
    data: dict


class DataPurgeResponse(CamelModel):
    teamId: str
    purgedAt: str
    counts: dict


@router.get("/teams/{team_id}/export", response_model=DataExportResponse)
async def export_team_data(
    request: Request,
    team_id: str,
    session: AsyncSession = Depends(get_session),
) -> DataExportResponse:
    """Export all data for a team (GDPR data portability)."""
    team = await _get_team_from_request(request, session)
    if str(team.id) != team_id:
        raise HTTPException(status_code=404, detail="Team not found")

    from ai_driven.db.postgres.data_purge import export_team_data as _export

    data = await _export(session, team.id)
    return DataExportResponse(
        teamId=str(team.id),
        exportedAt=datetime.now(timezone.utc).isoformat(),
        data=data,
    )


@router.delete("/teams/{team_id}/data", response_model=DataPurgeResponse)
async def purge_team_data(
    request: Request,
    team_id: str,
    session: AsyncSession = Depends(get_session),
) -> DataPurgeResponse:
    """Permanently delete all data for a team (GDPR right to erasure).

    WARNING: This is irreversible. Deletes tasks, repos, incidents, reviews,
    conversations, embeddings, and the team record itself.
    """
    team = await _get_team_from_request(request, session)
    if str(team.id) != team_id:
        raise HTTPException(status_code=404, detail="Team not found")

    from ai_driven.db.postgres.data_purge import purge_team_data as _purge

    counts = await _purge(session, team.id)
    await session.commit()

    logger.warning("Team %s data purged: %s", team_id, counts)

    return DataPurgeResponse(
        teamId=team_id,
        purgedAt=datetime.now(timezone.utc).isoformat(),
        counts=counts,
    )


@router.get("/me", response_model=MeResponse)
async def get_me(
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> MeResponse:
    """Return the authenticated team identity."""
    team = await _get_team_from_request(request, session)
    return MeResponse(teamId=str(team.id), teamName=team.name)


# ---- Helpers ----


def _build_jira_url(jira_key: str | None) -> str | None:
    if not jira_key:
        return None
    from ai_driven.config import get_settings
    base = get_settings().jira_base_url
    return f"{base}/browse/{jira_key}" if base else None


async def _get_team_from_request(
    request: Request, session: AsyncSession
) -> Any:
    """Resolve the authenticated team from the request.

    Three auth paths set something on request.state (from auth/middleware.py):
      1. Debug mode          → team_id (no api_key_hash)
      2. Session token (UI)  → team_id + session_token (no api_key_hash)
      3. X-API-Key (agents)  → api_key_hash + team_id

    We accept any of these. Only fail when NONE of them is present, which means
    middleware didn't authenticate the request at all.
    """
    from ai_driven.config import get_settings

    team_repo = TeamRepository(session)

    # Path 1 + 2: middleware has already resolved a team_id for us (debug or
    # session-token). Trust it — middleware only sets team_id after a
    # successful lookup in DB or Redis.
    team_id_str = getattr(request.state, "team_id", None)
    settings = get_settings()
    if team_id_str:
        # Skip the lookup in debug mode if the hash path didn't run, to match
        # the previous behavior that bypassed DB checks for dev ergonomics.
        team = await team_repo.get_by_id(uuid.UUID(team_id_str))
        if team:
            return team
        if settings.debug:
            raise HTTPException(status_code=401, detail="Invalid team_id")

    # Path 3: API-key header. Middleware has already hashed it.
    api_key_hash = getattr(request.state, "api_key_hash", None)
    if not api_key_hash:
        raise HTTPException(status_code=401, detail="Missing API key")

    team = await team_repo.get_by_api_key_hash(api_key_hash)
    if not team:
        raise HTTPException(status_code=401, detail="Invalid API key")
    return team


def _repo_to_response(repo: Any) -> RepoResponse:
    return RepoResponse(
        id=str(repo.id),
        teamId=str(repo.team_id),
        fullName=repo.full_name,
        scmType=repo.scm_type,
        defaultBranch=repo.default_branch,
        isActive=repo.is_active,
        createdAt=repo.created_at.isoformat() if repo.created_at else None,
    )


# ---- Repository CRUD ----


def _clone_url_for(scm_type: str, full_name: str) -> str:
    """Derive a clone URL from the SCM type and repository full name."""
    if scm_type == "bitbucket":
        return f"https://bitbucket.org/{full_name}.git"
    return f"https://github.com/{full_name}.git"


@router.post("/repos", response_model=RepoResponse, status_code=201)
async def create_repo(
    request: Request,
    body: RepoCreate,
    session: AsyncSession = Depends(get_session),
) -> RepoResponse:
    """Register a repository for the authenticated team."""
    team = await _get_team_from_request(request, session)

    repo_repo = RepoRepository(session)

    # Prevent duplicate registration of the same repo name for the team
    existing = await repo_repo.get_by_full_name(body.fullName)
    if existing and existing.team_id == team.id:
        raise HTTPException(
            status_code=409,
            detail=f"Repository '{body.fullName}' is already registered for this team",
        )

    # Reachability probe: verify the repo exists and our configured
    # credentials can see it before we create a DB row. Catches typos,
    # wrong provider, revoked tokens, missing scopes — one call per
    # registration, rare operation.
    from ai_driven.scm.exceptions import SCMAuthError, SCMError, SCMNotFoundError
    from ai_driven.scm.factory import get_scm_client_from_settings
    try:
        probe = get_scm_client_from_settings(body.scmType)
    except SCMError as ex:
        # Missing credentials for the chosen provider.
        raise HTTPException(
            status_code=400,
            detail=str(ex),
        )
    try:
        # list_pull_requests is cheap and covers the same access scope
        # the agents will need. We don't care about the result — only
        # that the call succeeds against the repo.
        await probe.list_pull_requests(body.fullName)
    except SCMNotFoundError:
        raise HTTPException(
            status_code=400,
            detail=(
                f"Repository '{body.fullName}' was not found on {body.scmType}, "
                f"or the configured credentials cannot access it."
            ),
        )
    except SCMAuthError as ex:
        raise HTTPException(
            status_code=400,
            detail=(
                f"Cannot authenticate to {body.scmType} — check the configured "
                f"credentials. Provider said: {str(ex)[:180]}"
            ),
        )
    except SCMError as ex:
        raise HTTPException(
            status_code=400,
            detail=f"{body.scmType} probe failed: {type(ex).__name__}: {str(ex)[:180]}",
        )
    finally:
        try:
            await probe.close()
        except Exception:
            pass

    repo = await repo_repo.create(
        team_id=team.id,
        full_name=body.fullName,
        scm_type=body.scmType,
        default_branch=body.defaultBranch,
        clone_url=_clone_url_for(body.scmType, body.fullName),
    )

    return _repo_to_response(repo)


@router.get("/repos", response_model=RepoListResponse)
async def list_repos(
    request: Request,
    limit: int = 50,
    offset: int = 0,
    session: AsyncSession = Depends(get_session),
) -> RepoListResponse:
    """List repositories for the authenticated team."""
    team = await _get_team_from_request(request, session)

    repo_repo = RepoRepository(session)
    repos = await repo_repo.list_for_team(team.id, limit=limit, offset=offset)

    return RepoListResponse(
        data=[_repo_to_response(r) for r in repos],
        total=len(repos),
    )


@router.delete("/repos/{repo_id}", status_code=204)
async def delete_repo(
    request: Request,
    repo_id: str,
    hard: bool = False,
    session: AsyncSession = Depends(get_session),
) -> None:
    """Delete a repository.

    Default: soft-delete (``is_active=False``). Preserves the row, tasks keep
    their ``repo_id`` link, and the UI shows 'Inactive'. Reactivate via
    ``PUT /repos/{id}`` with ``{"isActive": true}``.

    Pass ``?hard=true`` to permanently remove the row. Linked tasks are
    detached (``Task.repo_id=NULL``) so task history survives the removal.
    """
    team = await _get_team_from_request(request, session)

    try:
        parsed_id = uuid.UUID(repo_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid repository ID format")

    repo_repo = RepoRepository(session)
    repo = await repo_repo.get_by_id(parsed_id)

    if not repo or repo.team_id != team.id:
        raise HTTPException(status_code=404, detail="Repository not found")

    if hard:
        await repo_repo.hard_delete(parsed_id)
    else:
        await repo_repo.soft_delete(parsed_id)


@router.put("/repos/{repo_id}", response_model=RepoResponse)
async def update_repo(
    request: Request,
    repo_id: str,
    body: RepoUpdate,
    session: AsyncSession = Depends(get_session),
) -> RepoResponse:
    """Update repository settings."""
    team = await _get_team_from_request(request, session)

    try:
        parsed_id = uuid.UUID(repo_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid repository ID format")

    repo_repo = RepoRepository(session)
    repo = await repo_repo.get_by_id(parsed_id)

    if not repo or repo.team_id != team.id:
        raise HTTPException(status_code=404, detail="Repository not found")

    update_fields: dict[str, Any] = {}
    if body.defaultBranch is not None:
        update_fields["default_branch"] = body.defaultBranch
    if body.settingsJson is not None:
        update_fields["settings_json"] = body.settingsJson
    if body.isActive is not None:
        update_fields["is_active"] = body.isActive

    if update_fields:
        await repo_repo.update_fields(parsed_id, **update_fields)
        # Refresh the object
        await session.refresh(repo)

    return _repo_to_response(repo)


# ---- Usage ----


@router.get("/usage", response_model=UsageResponse)
async def get_usage(
    request: Request,
    date: str | None = None,
    session: AsyncSession = Depends(get_session),
) -> UsageResponse:
    """Return token usage and cost for the authenticated team.

    Query parameters:
        date: Optional date in YYYY-MM-DD format. Defaults to today.
    """
    team = await _get_team_from_request(request, session)

    tracker = CostTracker()
    usage = await tracker.get_daily_usage(str(team.id), date=date)
    budget = await tracker.check_budget(str(team.id))

    return UsageResponse(
        teamId=str(team.id),
        date=usage.date,
        inputTokens=usage.input_tokens,
        outputTokens=usage.output_tokens,
        totalCostUsd=usage.total_cost_usd,
        budget=BudgetInfo(
            withinBudget=budget.within_budget,
            usagePercent=budget.usage_percent,
            dailyBudgetUsd=budget.daily_budget_usd,
            spentUsd=budget.spent_usd,
            warning=budget.warning,
        ),
    )


# ---- Pipeline Runs (Timeline view) ----


_STATUS_TO_STAGE: dict[str, str] = {
    "queued": "jira",
    "planning": "jira",
    "running": "code",
    "coding": "code",
    "testing": "code",
    "pr_created": "review",
    "reviewing": "review",
    "review_passed": "deploy",
    "review_failed": "review",
    "deploying": "deploy",
    "deploy_failed": "deploy",
    "deployed": "monitor",
    "monitoring": "monitor",
    "completed": "deploy",    # coding agent done = PR ready for review/deploy
    "failed": "code",         # failed stays at code stage (not monitor)
    "cancelled": "code",
    # Phase 2 statuses. `blocked` halts pre-coding (specialist verdict=block);
    # `awaiting_qa` is between coding-success and post-coding QA verdict;
    # `qa_failed` is post-coding QA verdict=block. Each anchors to the stage
    # where the decision was made so the timeline badge reflects truth.
    "blocked": "code",        # halted BEFORE code ran — sits at "code" stage
    "awaiting_qa": "review",  # PR exists; QA has not yet returned a verdict
    "qa_failed": "review",    # PR exists; QA blocked the merge
}

# Per-agent stage override: coding agent "completed" means PR opened (review stage),
# workflow agent "completed" means full pipeline done (monitor stage).
_AGENT_COMPLETED_STAGE: dict[str, str] = {
    "coding": "review",
    "review": "review",
    "devops": "monitor",
    "workflow": "monitor",
}

_STATUS_TO_STAGE_STATUS: dict[str, str] = {
    "queued": "queued",
    "planning": "active",
    "running": "active",
    "coding": "active",
    "testing": "active",
    "pr_created": "active",
    "reviewing": "active",
    "review_passed": "passed",
    "review_failed": "failed",
    "deploying": "active",
    "deploy_failed": "failed",
    "deployed": "passed",
    "monitoring": "active",
    "completed": "passed",
    "failed": "failed",
    "cancelled": "cancelled",
    # Phase 2 statuses. The frontend's deriveStatus maps 'failed' to the
    # 'blocked' pill, which is what we want for blocked + qa_failed.
    # awaiting_qa is mid-pipeline → 'active'.
    "blocked": "failed",
    "awaiting_qa": "active",
    "qa_failed": "failed",
}


class PipelineRunResponse(CamelModel):
    id: str
    jiraKey: str
    title: str
    repo: str
    branch: str
    agent: str
    stage: str
    stageStatus: str
    prUrl: str | None = None
    prNumber: int | None = None
    startedAt: str
    durationMs: int
    assignee: str | None = None
    filesChanged: int | None = None
    additions: int | None = None
    deletions: int | None = None


@router.get("/pipeline/runs", response_model=list[PipelineRunResponse])
async def list_pipeline_runs(
    session: AsyncSession = Depends(get_session),
) -> list[PipelineRunResponse]:
    """Return active/recent tasks as pipeline run entries for the timeline view."""
    from ai_driven.db.postgres.models import Task as TaskModel

    now_utc = datetime.now(timezone.utc)
    cutoff = now_utc - timedelta(hours=24)

    result = await session.execute(
        select(TaskModel)
        .where(TaskModel.created_at >= cutoff)
        .order_by(TaskModel.created_at.desc())
        .limit(50)
    )
    tasks = list(result.scalars().all())

    runs: list[PipelineRunResponse] = []
    for t in tasks:
        output = t.output_data or {}
        started = t.started_at or t.created_at
        duration_ms = 0
        if t.completed_at and started:
            duration_ms = int((t.completed_at - started).total_seconds() * 1000)
        elif started:
            duration_ms = int((now_utc - started).total_seconds() * 1000)

        files_changed: int | None = output.get("files_changed")
        additions: int | None = output.get("additions")
        deletions: int | None = output.get("deletions")

        # Determine pipeline stage — terminal tasks use per-agent mapping
        # so a coding agent stops at "review" while a workflow agent reaches "monitor".
        # T23: TERMINAL_STATUSES now includes qa_failed + blocked so a
        # live-mode QA block or strict pre-coding block displays in the
        # same "done" bucket as completed/failed rather than falling
        # through to the default in-flight stage.
        if t.status in TERMINAL_STATUSES or t.status == "cancelled":
            # blocked = specialist halted BEFORE coding ran; keep it at the
            # stage the status map says ("code") rather than promoting to
            # "review" via the coding-agent completion override. Same rule
            # for qa_failed + awaiting_qa — those are review-stage facts
            # that shouldn't be clobbered by a generic completion rule.
            if t.status in {"blocked", "qa_failed", "awaiting_qa"}:
                stage = _STATUS_TO_STAGE.get(t.status, "code")
            else:
                stage = _AGENT_COMPLETED_STAGE.get(t.agent_type, _STATUS_TO_STAGE.get(t.status, "code"))
        else:
            stage = _STATUS_TO_STAGE.get(t.status, "code")

        runs.append(PipelineRunResponse(
            id=str(t.id),
            jiraKey=t.jira_key or "",
            title=t.title,
            repo=t.input_data.get("repo_full_name", ""),
            branch=t.input_data.get("branch", f"agent/{t.jira_key or t.id}"),
            agent=t.agent_type,
            stage=stage,
            stageStatus=_STATUS_TO_STAGE_STATUS.get(t.status, "running"),
            prUrl=output.get("pr_url"),
            prNumber=output.get("pr_number"),
            startedAt=started.isoformat() if started else now_utc.isoformat(),
            durationMs=duration_ms,
            assignee=t.input_data.get("assignee"),
            filesChanged=files_changed,
            additions=additions,
            deletions=deletions,
        ))

    return runs


# ---- Incidents ----


class IncidentRcaResponse(CamelModel):
    rootCause: str
    confidence: str
    evidence: list[str]
    suggestedFix: str
    relatedPr: dict[str, Any] | None = None


class IncidentResponse(CamelModel):
    id: str
    title: str
    severity: IncidentSeverity
    status: str
    service: str
    startedAt: str
    metric: str
    currentValue: str
    threshold: str
    rca: IncidentRcaResponse | None = None


class IncidentCreate(CamelModel):
    title: str
    severity: IncidentSeverity = IncidentSeverity.P2
    service: str
    metric: str = ""
    currentValue: str = ""
    threshold: str = ""

    @field_validator("severity", mode="before")
    @classmethod
    def _normalize_severity(cls, v: Any) -> IncidentSeverity:
        if isinstance(v, IncidentSeverity):
            return v
        normalized = IncidentSeverity.normalize(str(v)) if v is not None else None
        if normalized is None:
            raise ValueError(
                f"severity must be one of P1, P2, P3, P4; got '{v}'"
            )
        return normalized


def _incident_to_response(inc: Any) -> IncidentResponse:
    rca = None
    if inc.rca_json:
        rca = IncidentRcaResponse(
            rootCause=inc.rca_json.get("root_cause", ""),
            confidence=inc.rca_json.get("confidence", "medium"),
            evidence=inc.rca_json.get("evidence", []),
            suggestedFix=inc.rca_json.get("suggested_fix", ""),
            relatedPr=inc.rca_json.get("related_pr"),
        )
    return IncidentResponse(
        id=str(inc.id),
        title=inc.title,
        severity=IncidentSeverity.normalize(inc.severity) or IncidentSeverity.P2,
        status=inc.status,
        service=inc.service,
        startedAt=inc.started_at.isoformat() if inc.started_at else "",
        metric=inc.metric,
        currentValue=inc.current_value,
        threshold=inc.threshold,
        rca=rca,
    )


@router.get("/incidents", response_model=list[IncidentResponse])
async def list_incidents(
    session: AsyncSession = Depends(get_session),
) -> list[IncidentResponse]:
    """List all incidents, most recent first."""
    from ai_driven.db.postgres.repository import IncidentRepository

    repo = IncidentRepository(session)
    incidents = await repo.list_all()
    return [_incident_to_response(i) for i in incidents]


@router.post("/incidents", response_model=IncidentResponse, status_code=201)
async def create_incident(
    body: IncidentCreate,
    session: AsyncSession = Depends(get_session),
) -> IncidentResponse:
    """Create a new incident."""
    from ai_driven.db.postgres.repository import IncidentRepository

    repo = IncidentRepository(session)
    inc = await repo.create(
        team_id=uuid.UUID("00000000-0000-0000-0000-000000000000"),
        title=body.title,
        severity=body.severity.value,
        service=body.service,
        metric=body.metric,
        current_value=body.currentValue,
        threshold=body.threshold,
    )
    await session.commit()
    return _incident_to_response(inc)


# ---- Reviews ----


class ReviewCommentResponse(CamelModel):
    id: str
    filePath: str
    lineNumber: int | None = None
    severity: str
    category: str
    confidence: float = 0.8
    title: str = ""
    body: str = ""
    suggestedCode: str | None = None
    resolved: bool = False


class ReviewFileResponse(CamelModel):
    path: str
    additions: int
    deletions: int
    comments: list[ReviewCommentResponse]


class ReviewDetailResponse(CamelModel):
    id: str
    prNumber: int
    repo: str
    title: str
    branch: str
    author: str
    jiraKey: str | None = None
    prUrl: str
    summary: str
    status: str
    durationMs: int | None = None
    files: list[ReviewFileResponse]


def _normalize_comments(raw_comments: list | None) -> list[dict[str, Any]]:
    """Normalize review comments to the expected schema.

    Handles both old-format comments (file_path/file/path, line, comment/message)
    and new-format comments (structured with severity, category, confidence, title, body).
    """
    if not raw_comments:
        return []
    normalized: list[dict[str, Any]] = []
    for c in raw_comments:
        confidence_raw = c.get("confidence", 0.8)
        try:
            confidence_val = float(confidence_raw)
        except (TypeError, ValueError):
            # Handle old string-based confidence like "medium", "high", "low"
            confidence_map = {"high": 0.9, "medium": 0.7, "low": 0.4}
            confidence_val = confidence_map.get(str(confidence_raw).lower(), 0.8)
        confidence_val = max(0.0, min(1.0, confidence_val))

        normalized.append({
            "filePath": c.get("file_path", c.get("filePath", c.get("file", c.get("path", "unknown")))),
            "lineNumber": c.get("line_number", c.get("lineNumber", c.get("line"))),
            "severity": c.get("severity", "info"),
            "category": c.get("category", "correctness"),
            "confidence": confidence_val,
            "title": c.get("title", c.get("message", "")),
            "body": c.get("body", c.get("comment", c.get("message", ""))),
            "suggestedCode": c.get("suggested_code", c.get("suggestedCode")),
            "id": c.get("id", str(uuid.uuid4())),
            "resolved": c.get("resolved", False),
        })
    return normalized


def _review_to_response(r: Any) -> ReviewDetailResponse:
    """Convert a Review model to the API response shape."""
    # Normalize comments_json into structured format, then group by file
    normalized = _normalize_comments(r.comments_json)
    files_by_path: dict[str, list[dict[str, Any]]] = {}
    for c in normalized:
        path = c["filePath"]
        files_by_path.setdefault(path, []).append(c)

    file_responses: list[ReviewFileResponse] = []
    for path, file_comments in files_by_path.items():
        comment_responses = [
            ReviewCommentResponse(
                id=c["id"],
                filePath=c["filePath"],
                lineNumber=c["lineNumber"],
                severity=c["severity"],
                category=c["category"],
                confidence=c["confidence"],
                title=c["title"],
                body=c["body"],
                suggestedCode=c["suggestedCode"],
                resolved=c["resolved"],
            )
            for c in file_comments
        ]
        file_responses.append(ReviewFileResponse(
            path=path,
            additions=0,
            deletions=0,
            comments=comment_responses,
        ))

    # Derive branch from task or use a fallback
    task = r.task
    jira_key = task.jira_key if task else None
    title = task.title if task else f"PR #{r.pr_number}"

    duration_ms: int | None = None
    if task and task.started_at and task.completed_at:
        duration_ms = int(
            (task.completed_at - task.started_at).total_seconds() * 1000
        )

    return ReviewDetailResponse(
        id=str(r.id),
        prNumber=r.pr_number,
        repo=r.repo_full_name,
        title=title,
        branch=f"agent/{jira_key or r.id}",
        author=task.agent_type if task else "review",
        jiraKey=jira_key,
        prUrl=r.pr_url,
        summary=r.summary or "",
        status=r.status,
        durationMs=duration_ms,
        files=file_responses,
    )


@router.get("/reviews", response_model=list[ReviewDetailResponse])
async def list_reviews(
    session: AsyncSession = Depends(get_session),
) -> list[ReviewDetailResponse]:
    """List all code reviews with inline comments."""
    from ai_driven.db.postgres.repository import ReviewRepository
    from sqlalchemy.orm import selectinload
    from ai_driven.db.postgres.models import Review as ReviewModel

    result = await session.execute(
        select(ReviewModel)
        .options(selectinload(ReviewModel.task))
        .order_by(ReviewModel.created_at.desc())
        .limit(50)
    )
    reviews = list(result.scalars().all())
    return [_review_to_response(r) for r in reviews]


@router.get("/reviews/{review_id}", response_model=ReviewDetailResponse)
async def get_review(
    review_id: str,
    session: AsyncSession = Depends(get_session),
) -> ReviewDetailResponse:
    """Get a single review by ID."""
    from sqlalchemy.orm import selectinload
    from ai_driven.db.postgres.models import Review as ReviewModel

    try:
        parsed_id = uuid.UUID(review_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid review ID format")

    result = await session.execute(
        select(ReviewModel)
        .options(selectinload(ReviewModel.task))
        .where(ReviewModel.id == parsed_id)
    )
    review = result.scalar_one_or_none()
    if not review:
        raise HTTPException(status_code=404, detail="Review not found")

    return _review_to_response(review)


async def _resolve_review_scm_target(
    review_id: str, session: AsyncSession
) -> tuple[Any, str, int, str]:
    """Return (review, repo_full_name, pr_number, scm_type) or raise HTTPException."""
    from ai_driven.db.postgres.models import Repository as RepoRow
    from ai_driven.db.postgres.models import Review as ReviewModel
    from ai_driven.db.postgres.models import Task as TaskRow
    from sqlalchemy.orm import selectinload

    try:
        parsed_id = uuid.UUID(review_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid review ID format")

    result = await session.execute(
        select(ReviewModel)
        .options(selectinload(ReviewModel.task))
        .where(ReviewModel.id == parsed_id)
    )
    review = result.scalar_one_or_none()
    if not review:
        raise HTTPException(status_code=404, detail="Review not found")

    scm_type = "github"
    if review.task and review.task.repo_id:
        res = await session.execute(
            select(RepoRow.scm_type).where(RepoRow.id == review.task.repo_id)
        )
        row = res.scalar_one_or_none()
        if row:
            scm_type = row

    return review, review.repo_full_name, review.pr_number, scm_type


@router.get("/reviews/{review_id}/reviewers")
async def get_review_reviewers(
    review_id: str,
    session: AsyncSession = Depends(get_session),
) -> dict[str, Any]:
    """Return the reviewer list from the underlying PR, with the AI reviewer prepended."""
    from ai_driven.scm.factory import get_scm_client_from_settings

    review, repo_full_name, pr_number, scm_type = await _resolve_review_scm_target(
        review_id, session
    )

    ai_reviewer = {
        "username": "gotyme-x-ai",
        "displayName": "GoTyme-X AI",
        "avatarUrl": None,
        "isBot": True,
        "state": review.status if review.status in (
            "approved", "changes_requested", "commented", "pending"
        ) else "commented",
        "commentCount": len(review.comments_json or []),
    }

    try:
        scm = get_scm_client_from_settings(scm_type)
    except Exception as ex:
        return {"reviewers": [ai_reviewer], "scmError": str(ex)}

    try:
        raw = await scm.get_pr_reviewers(repo_full_name, pr_number)
    except Exception as ex:
        logger.warning("get_pr_reviewers failed: %s", ex)
        return {"reviewers": [ai_reviewer], "scmError": str(ex)[:200]}
    finally:
        await scm.close()

    human = [
        {
            "username": r.username,
            "displayName": r.display_name,
            "avatarUrl": r.avatar_url,
            "isBot": r.is_bot,
            "state": r.state,
            "commentCount": r.comment_count,
        }
        for r in raw
    ]
    return {"reviewers": [ai_reviewer] + human}


@router.get("/reviews/{review_id}/checks")
async def get_review_checks(
    review_id: str,
    session: AsyncSession = Depends(get_session),
) -> dict[str, Any]:
    """Return the CI check-run list for the PR's head commit."""
    from ai_driven.scm.factory import get_scm_client_from_settings

    _review, repo_full_name, pr_number, scm_type = await _resolve_review_scm_target(
        review_id, session
    )

    try:
        scm = get_scm_client_from_settings(scm_type)
    except Exception as ex:
        return {"checks": [], "scmError": str(ex)}

    try:
        raw = await scm.get_pr_checks(repo_full_name, pr_number)
    except Exception as ex:
        logger.warning("get_pr_checks failed: %s", ex)
        return {"checks": [], "scmError": str(ex)[:200]}
    finally:
        await scm.close()

    return {
        "checks": [
            {
                "name": c.name,
                "status": c.status,
                "conclusion": c.conclusion,
                "durationSeconds": c.duration_seconds,
                "detailsUrl": c.details_url,
            }
            for c in raw
        ]
    }


@router.get("/reviews/{review_id}/commits")
async def get_review_commits(
    review_id: str,
    session: AsyncSession = Depends(get_session),
) -> dict[str, Any]:
    """Return the commit list for the PR behind this review."""
    from ai_driven.scm.factory import get_scm_client_from_settings

    _review, repo_full_name, pr_number, scm_type = await _resolve_review_scm_target(
        review_id, session
    )

    try:
        scm = get_scm_client_from_settings(scm_type)
    except Exception as ex:
        return {"commits": [], "scmError": str(ex)}

    try:
        raw = await scm.get_pr_commits(repo_full_name, pr_number)
    except Exception as ex:
        logger.warning("get_pr_commits failed: %s", ex)
        return {"commits": [], "scmError": str(ex)[:200]}
    finally:
        await scm.close()

    return {
        "commits": [
            {
                "sha": c.sha,
                "shortSha": c.sha[:7],
                "message": c.message,
                "author": c.author,
                "timestamp": c.timestamp.isoformat() if c.timestamp else None,
                "url": c.url,
            }
            for c in raw
        ]
    }


# ---- Insights ----


class InsightsSummaryResponse(CamelModel):
    hoursSaved: float
    hoursSavedDelta: float
    prsMerged: int
    prsMergedDelta: float
    avgCycleTimeHours: float
    avgCycleTimeDelta: float
    successRate: float
    successRateDelta: float


class WeeklyHoursResponse(CamelModel):
    week: str
    human: float
    agent: float


class PRSplitResponse(CamelModel):
    type: str
    value: int


class TopRepoResponse(CamelModel):
    repo: str
    prs: int
    hours: float
    success: float


class InsightsResponse(CamelModel):
    summary: InsightsSummaryResponse
    hoursByWeek: list[WeeklyHoursResponse]
    prSplit: list[PRSplitResponse]
    topRepos: list[TopRepoResponse]
    heatmap: list[list[float]]


@router.get("/insights", response_model=InsightsResponse)
async def get_insights(
    time_range: str = Query(default="30d", pattern="^(7d|30d|90d)$", alias="range"),
    session: AsyncSession = Depends(get_session),
) -> InsightsResponse:
    """Return engineering productivity insights computed from task data."""
    from ai_driven.db.postgres.models import Task as TaskModel, Review as ReviewModel

    days = {"7d": 7, "30d": 30, "90d": 90}[time_range]
    now_utc = datetime.now(timezone.utc)
    cutoff = now_utc - timedelta(days=days)
    prev_cutoff = cutoff - timedelta(days=days)

    # Current period tasks
    cur_result = await session.execute(
        select(TaskModel).where(TaskModel.created_at >= cutoff)
    )
    cur_tasks = list(cur_result.scalars().all())

    # Previous period tasks for delta
    prev_result = await session.execute(
        select(TaskModel).where(
            TaskModel.created_at >= prev_cutoff,
            TaskModel.created_at < cutoff,
        )
    )
    prev_tasks = list(prev_result.scalars().all())

    # Reviews in current period
    review_result = await session.execute(
        select(func.count(ReviewModel.id)).where(ReviewModel.created_at >= cutoff)
    )
    prs_merged = review_result.scalar() or 0

    prev_review_result = await session.execute(
        select(func.count(ReviewModel.id)).where(
            ReviewModel.created_at >= prev_cutoff,
            ReviewModel.created_at < cutoff,
        )
    )
    prev_prs = prev_review_result.scalar() or 0

    # Compute metrics
    # T23: SUCCESS vs FAILURE bucket membership. Post-T23 live-mode,
    # failures include qa_failed (post-coding block) and blocked
    # (strict pre-coding block) in addition to the legacy "failed".
    completed_cur = [t for t in cur_tasks if t.status in SUCCESS_STATUSES]
    completed_prev = [t for t in prev_tasks if t.status in SUCCESS_STATUSES]
    failed_cur = [t for t in cur_tasks if t.status in FAILURE_STATUSES]
    failed_prev = [t for t in prev_tasks if t.status in FAILURE_STATUSES]

    # Hours saved: estimate 2 hours per completed coding task, 0.5 per review
    hours_saved = sum(
        2.0 if t.agent_type == "coding" else 0.5
        for t in completed_cur
    )
    prev_hours = sum(
        2.0 if t.agent_type == "coding" else 0.5
        for t in completed_prev
    )
    hours_delta = ((hours_saved - prev_hours) / prev_hours * 100) if prev_hours > 0 else 0.0

    prs_delta = ((prs_merged - prev_prs) / prev_prs * 100) if prev_prs > 0 else 0.0

    # Avg cycle time
    cycle_times = []
    for t in completed_cur:
        if t.started_at and t.completed_at:
            cycle_times.append((t.completed_at - t.started_at).total_seconds() / 3600)
    avg_cycle = sum(cycle_times) / len(cycle_times) if cycle_times else 0.0

    prev_cycles = []
    for t in completed_prev:
        if t.started_at and t.completed_at:
            prev_cycles.append((t.completed_at - t.started_at).total_seconds() / 3600)
    prev_avg_cycle = sum(prev_cycles) / len(prev_cycles) if prev_cycles else 0.0
    cycle_delta = ((avg_cycle - prev_avg_cycle) / prev_avg_cycle * 100) if prev_avg_cycle > 0 else 0.0

    # Success rate
    total_terminal = len(completed_cur) + len(failed_cur)
    success_rate = (len(completed_cur) / total_terminal * 100) if total_terminal > 0 else 0.0
    prev_terminal = len(completed_prev) + len(failed_prev)
    prev_success = (len(completed_prev) / prev_terminal * 100) if prev_terminal > 0 else 0.0
    success_delta = success_rate - prev_success

    # Weekly breakdown
    from collections import defaultdict
    weekly_hours: dict[str, dict[str, float]] = defaultdict(lambda: {"human": 0.0, "agent": 0.0})
    for t in completed_cur:
        week = t.created_at.strftime("W%V")
        hours = 2.0 if t.agent_type == "coding" else 0.5
        weekly_hours[week]["agent"] += hours
        weekly_hours[week]["human"] += hours * 0.3  # Estimate human residual work

    weeks_sorted = sorted(weekly_hours.keys())
    hours_by_week = [
        WeeklyHoursResponse(
            week=w,
            human=round(weekly_hours[w]["human"], 1),
            agent=round(weekly_hours[w]["agent"], 1),
        )
        for w in weeks_sorted
    ]

    # PR split
    agent_authored = len([t for t in completed_cur if t.agent_type == "coding"])
    agent_assisted = max(0, prs_merged - agent_authored)
    pr_split = [
        PRSplitResponse(type="Agent-authored", value=agent_authored),
        PRSplitResponse(type="Agent-assisted", value=agent_assisted),
        PRSplitResponse(type="Human-only", value=max(0, prs_merged // 5)),  # Estimate
    ]

    # Top repos
    repo_stats: dict[str, dict[str, Any]] = defaultdict(
        lambda: {"prs": 0, "hours": 0.0, "completed": 0, "total": 0}
    )
    for t in cur_tasks:
        repo = t.input_data.get("repo_full_name", "unknown")
        if not repo:
            continue
        repo_stats[repo]["total"] += 1
        if t.status in SUCCESS_STATUSES:
            repo_stats[repo]["completed"] += 1
            repo_stats[repo]["prs"] += 1
            repo_stats[repo]["hours"] += 2.0 if t.agent_type == "coding" else 0.5

    top_repos = sorted(repo_stats.items(), key=lambda x: x[1]["prs"], reverse=True)[:5]
    top_repos_response = [
        TopRepoResponse(
            repo=repo,
            prs=stats["prs"],
            hours=round(stats["hours"], 1),
            success=round(stats["completed"] / stats["total"] * 100, 0) if stats["total"] > 0 else 0.0,
        )
        for repo, stats in top_repos
    ]

    # Activity heatmap (7 days × 24 hours)
    heatmap: list[list[float]] = [[0.0] * 24 for _ in range(7)]
    for t in cur_tasks:
        if t.created_at:
            dow = t.created_at.weekday()  # 0=Mon
            hour = t.created_at.hour
            heatmap[dow][hour] += 1.0

    # Normalize to 0-1
    max_val = max(max(row) for row in heatmap) or 1.0
    heatmap = [[round(v / max_val, 2) for v in row] for row in heatmap]

    return InsightsResponse(
        summary=InsightsSummaryResponse(
            hoursSaved=round(hours_saved, 1),
            hoursSavedDelta=round(hours_delta, 1),
            prsMerged=prs_merged,
            prsMergedDelta=round(prs_delta, 1),
            avgCycleTimeHours=round(avg_cycle, 1),
            avgCycleTimeDelta=round(cycle_delta, 1),
            successRate=round(success_rate, 1),
            successRateDelta=round(success_delta, 1),
        ),
        hoursByWeek=hours_by_week,
        prSplit=pr_split,
        topRepos=top_repos_response,
        heatmap=heatmap,
    )
