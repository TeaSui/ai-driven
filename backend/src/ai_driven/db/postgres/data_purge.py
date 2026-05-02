"""Team data purge — deletes all data for a team across all tables."""

from __future__ import annotations

import logging
import uuid

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.db.postgres.models import (
    CodeEmbedding,
    ConversationRecord,
    Incident,
    Repository,
    Review,
    Task,
    Team,
)

logger = logging.getLogger(__name__)


async def purge_team_data(session: AsyncSession, team_id: uuid.UUID) -> dict[str, int]:
    """Delete all data for a team across all tables.

    Deletes in FK order (children before parents).
    Returns count of deleted records per table.
    """
    counts: dict[str, int] = {}

    # Subqueries for async-compatible IN clauses
    repo_ids_sq = select(Repository.id).where(Repository.team_id == team_id).scalar_subquery()
    task_ids_sq = select(Task.id).where(Task.team_id == team_id).scalar_subquery()

    # 1. CodeEmbeddings (via repo)
    result = await session.execute(
        delete(CodeEmbedding).where(CodeEmbedding.repo_id.in_(repo_ids_sq))
    )
    counts["code_embeddings"] = result.rowcount  # type: ignore[assignment]

    # 2. Reviews (via task)
    result = await session.execute(
        delete(Review).where(Review.task_id.in_(task_ids_sq))
    )
    counts["reviews"] = result.rowcount  # type: ignore[assignment]

    # 3. ConversationRecords (via task)
    result = await session.execute(
        delete(ConversationRecord).where(ConversationRecord.task_id.in_(task_ids_sq))
    )
    counts["conversations"] = result.rowcount  # type: ignore[assignment]

    # 4. Incidents
    result = await session.execute(
        delete(Incident).where(Incident.team_id == team_id)
    )
    counts["incidents"] = result.rowcount  # type: ignore[assignment]

    # 5. Tasks
    result = await session.execute(
        delete(Task).where(Task.team_id == team_id)
    )
    counts["tasks"] = result.rowcount  # type: ignore[assignment]

    # 6. Repositories
    result = await session.execute(
        delete(Repository).where(Repository.team_id == team_id)
    )
    counts["repositories"] = result.rowcount  # type: ignore[assignment]

    # 7. Team itself
    result = await session.execute(
        delete(Team).where(Team.id == team_id)
    )
    counts["teams"] = result.rowcount  # type: ignore[assignment]

    logger.info("Purged team %s data: %s", team_id, counts)
    return counts


async def export_team_data(session: AsyncSession, team_id: uuid.UUID) -> dict:
    """Export all data for a team as a JSON-serializable dict."""
    from ai_driven.db.postgres.repository import TaskRepository, RepoRepository, TeamRepository

    team_repo = TeamRepository(session)
    task_repo = TaskRepository(session)
    repo_repo = RepoRepository(session)

    team = await team_repo.get_by_id(team_id)
    if not team:
        return {}

    tasks = await task_repo.list_for_team(team_id, limit=10000)
    repos = await repo_repo.list_for_team(team_id, limit=10000)

    # Incidents
    from ai_driven.db.postgres.repository import IncidentRepository
    inc_repo = IncidentRepository(session)
    incidents = await inc_repo.list_for_team(team_id)

    return {
        "team": {
            "id": str(team.id),
            "name": team.name,
            "claude_provider": team.claude_provider,
            "created_at": team.created_at.isoformat() if team.created_at else None,
        },
        "repositories": [
            {
                "id": str(r.id),
                "full_name": r.full_name,
                "scm_type": r.scm_type,
                "default_branch": r.default_branch,
                "created_at": r.created_at.isoformat() if r.created_at else None,
            }
            for r in repos
        ],
        "tasks": [
            {
                "id": str(t.id),
                "agent_type": t.agent_type,
                "status": t.status,
                "title": t.title,
                "jira_key": t.jira_key,
                "total_tokens": t.total_tokens,
                "total_cost_usd": float(t.total_cost_usd) if t.total_cost_usd else None,
                "created_at": t.created_at.isoformat() if t.created_at else None,
            }
            for t in tasks
        ],
        "incidents": [
            {
                "id": str(i.id),
                "title": i.title,
                "severity": i.severity,
                "status": i.status,
                "service": i.service,
                "started_at": i.started_at.isoformat() if i.started_at else None,
            }
            for i in incidents
        ],
    }
