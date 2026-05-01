"""Repository pattern for PostgreSQL data access."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from sqlalchemy import delete, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.db.postgres.models import (
    ConversationRecord,
    Incident,
    Repository,
    Review,
    Task,
    Team,
)


class TeamRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def get_by_id(self, team_id: uuid.UUID) -> Team | None:
        return await self._session.get(Team, team_id)

    async def get_by_api_key_hash(self, api_key_hash: str) -> Team | None:
        result = await self._session.execute(
            select(Team).where(Team.api_key_hash == api_key_hash, Team.is_active.is_(True))
        )
        return result.scalar_one_or_none()

    async def create(self, **kwargs: Any) -> Team:
        team = Team(**kwargs)
        self._session.add(team)
        await self._session.flush()
        return team

    async def list_all(self, limit: int = 50, offset: int = 0) -> list[Team]:
        result = await self._session.execute(
            select(Team)
            .where(Team.is_active.is_(True))
            .order_by(Team.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all())

    async def update_api_key_hash(self, team_id: uuid.UUID, new_hash: str) -> None:
        """Update the API key hash for a team."""
        await self._session.execute(
            update(Team).where(Team.id == team_id).values(api_key_hash=new_hash)
        )

    async def soft_delete(self, team_id: uuid.UUID) -> None:
        await self._session.execute(
            update(Team).where(Team.id == team_id).values(is_active=False)
        )


class TaskRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def get_by_id(self, task_id: uuid.UUID) -> Task | None:
        return await self._session.get(Task, task_id)

    async def create(self, **kwargs: Any) -> Task:
        task = Task(**kwargs)
        self._session.add(task)
        await self._session.flush()
        return task

    async def create_task(
        self,
        task_id: str,
        agent_type: str,
        title: str,
        input_data: dict[str, Any] | None = None,
        team_id: uuid.UUID | None = None,
        repo_id: uuid.UUID | None = None,
    ) -> Task:
        """Create a task with sensible defaults for chat-originated tasks.

        Passing ``repo_id`` links the task to a registered repository so that
        admin endpoints like ``/api/admin/tasks/{id}/file`` can resolve the
        provider via ``Repository.scm_type`` (without it, they default to
        GitHub and 404 on Bitbucket repos).
        """
        if team_id is None:
            team_id = uuid.UUID("00000000-0000-0000-0000-000000000000")
        return await self.create(
            id=uuid.UUID(task_id),
            team_id=team_id,
            agent_type=agent_type,
            status="queued",
            title=title,
            input_data=input_data or {},
            repo_id=repo_id,
        )

    async def update_status(
        self, task_id: uuid.UUID, status: str, **extra: Any
    ) -> None:
        values: dict[str, Any] = {"status": status, **extra}
        if status in ("planning", "coding", "running") and "started_at" not in extra:
            values["started_at"] = datetime.now(timezone.utc)
        if status in ("completed", "failed"):
            values["completed_at"] = datetime.now(timezone.utc)

        await self._session.execute(
            update(Task).where(Task.id == task_id).values(**values)
        )

    async def update_output_data(
        self, task_id: uuid.UUID, patch: dict[str, Any]
    ) -> None:
        """Merge ``patch`` into ``Task.output_data`` without erasing existing keys.

        ``update_status(..., output_data=...)`` OVERWRITES the whole column,
        which silently drops any keys a prior write had set (e.g. pr_number
        vs branch_name vs pr_url written by different code paths). This
        helper does a read-modify-write inside a single session so two
        callers persisting different keys cannot stomp on each other.

        Keys present in ``patch`` win over pre-existing values (standard
        dict.update semantics). Passing an empty patch is a no-op — we
        still avoid issuing an UPDATE.

        Note: we intentionally use Python-side merge rather than Postgres
        JSONB ``||`` concat because the column is declared as ``JSON`` (not
        JSONB) in ``models.py`` and the test suite uses SQLite in-memory
        for unit tests, neither of which supports the ``||`` operator.
        """
        if not patch:
            return
        existing_task = await self._session.get(Task, task_id)
        if existing_task is None:
            return
        current = dict(existing_task.output_data or {})
        current.update(patch)
        await self._session.execute(
            update(Task).where(Task.id == task_id).values(output_data=current)
        )

    async def get_active_count_for_team(self, team_id: uuid.UUID) -> int:
        result = await self._session.execute(
            select(Task).where(
                Task.team_id == team_id,
                Task.status.in_(["queued", "planning", "coding", "testing"]),
            )
        )
        return len(result.scalars().all())

    async def list_for_team(
        self, team_id: uuid.UUID, limit: int = 50, offset: int = 0
    ) -> list[Task]:
        result = await self._session.execute(
            select(Task)
            .where(Task.team_id == team_id)
            .order_by(Task.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all())


class ReviewRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def get_by_id(self, review_id: uuid.UUID) -> Review | None:
        return await self._session.get(Review, review_id)

    async def create(self, **kwargs: Any) -> Review:
        review = Review(**kwargs)
        self._session.add(review)
        await self._session.flush()
        return review

    async def update_status(self, review_id: uuid.UUID, status: str, **extra: Any) -> None:
        await self._session.execute(
            update(Review).where(Review.id == review_id).values(status=status, **extra)
        )

    async def list_all(self, limit: int = 50, offset: int = 0) -> list[Review]:
        result = await self._session.execute(
            select(Review)
            .order_by(Review.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all())


class IncidentRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def get_by_id(self, incident_id: uuid.UUID) -> Incident | None:
        return await self._session.get(Incident, incident_id)

    async def create(self, **kwargs: Any) -> Incident:
        incident = Incident(**kwargs)
        self._session.add(incident)
        await self._session.flush()
        return incident

    async def list_all(self, limit: int = 50, offset: int = 0) -> list[Incident]:
        result = await self._session.execute(
            select(Incident)
            .order_by(Incident.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all())

    async def list_for_team(self, team_id: uuid.UUID, limit: int = 50, offset: int = 0) -> list[Incident]:
        result = await self._session.execute(
            select(Incident)
            .where(Incident.team_id == team_id)
            .order_by(Incident.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all())

    async def update_status(self, incident_id: uuid.UUID, status: str, **extra: Any) -> None:
        values: dict[str, Any] = {"status": status, **extra}
        await self._session.execute(
            update(Incident).where(Incident.id == incident_id).values(**values)
        )


class RepoRepository:
    """Repository for looking up registered SCM repositories."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def get_by_full_name(self, full_name: str) -> Repository | None:
        result = await self._session.execute(
            select(Repository).where(
                Repository.full_name == full_name,
                Repository.is_active.is_(True),
            )
        )
        return result.scalar_one_or_none()

    async def get_by_id(self, repo_id: uuid.UUID) -> Repository | None:
        return await self._session.get(Repository, repo_id)

    async def get_first_active(self) -> Repository | None:
        """Return the first active repository (useful as a default)."""
        result = await self._session.execute(
            select(Repository)
            .where(Repository.is_active.is_(True))
            .order_by(Repository.created_at.asc())
            .limit(1)
        )
        return result.scalar_one_or_none()

    async def create(self, **kwargs: Any) -> Repository:
        """Create a new repository record."""
        repo = Repository(**kwargs)
        self._session.add(repo)
        await self._session.flush()
        return repo

    async def list_for_team(
        self,
        team_id: uuid.UUID,
        *,
        include_inactive: bool = False,
        limit: int = 50,
        offset: int = 0,
    ) -> list[Repository]:
        """List repositories belonging to a team."""
        stmt = select(Repository).where(Repository.team_id == team_id)
        if not include_inactive:
            stmt = stmt.where(Repository.is_active.is_(True))
        stmt = stmt.order_by(Repository.created_at.desc()).limit(limit).offset(offset)
        result = await self._session.execute(stmt)
        return list(result.scalars().all())

    async def soft_delete(self, repo_id: uuid.UUID) -> None:
        """Set is_active=False on a repository."""
        await self._session.execute(
            update(Repository).where(Repository.id == repo_id).values(is_active=False)
        )

    async def hard_delete(self, repo_id: uuid.UUID) -> None:
        """Permanently delete a repository and detach its tasks.

        Detaches tasks (sets ``Task.repo_id=NULL``) rather than cascading,
        so the task history survives the repo removal. ``Task.repo_id`` is
        nullable — callers like ``_resolve_task_scm_type`` already handle
        that case by falling back to ``Task.input_data["repo_full_name"]``.
        """
        from ai_driven.db.postgres.models import Task
        await self._session.execute(
            update(Task).where(Task.repo_id == repo_id).values(repo_id=None)
        )
        await self._session.execute(
            delete(Repository).where(Repository.id == repo_id)
        )

    async def update_fields(self, repo_id: uuid.UUID, **kwargs: Any) -> None:
        """Update specified fields on a repository."""
        if not kwargs:
            return
        await self._session.execute(
            update(Repository).where(Repository.id == repo_id).values(**kwargs)
        )


class ConversationRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def create(self, **kwargs: Any) -> ConversationRecord:
        record = ConversationRecord(**kwargs)
        self._session.add(record)
        await self._session.flush()
        return record

    async def update(self, conv_id: uuid.UUID, **kwargs: Any) -> None:
        await self._session.execute(
            update(ConversationRecord).where(ConversationRecord.id == conv_id).values(**kwargs)
        )
