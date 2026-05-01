"""Persistence layer for task_artifacts rows.

UPSERT semantics: re-runs of the same (task_id, role) pair UPDATE the
existing row rather than inserting. Repair/retry history is tracked in
the agent_events pub/sub stream, not in the artifacts table — each
(task_id, role) pair holds at most one row representing the latest
specialist output.

This layer is the ONLY write path the orchestrator uses to persist
specialist output. Callers must have already validated `Artifact.payload`
via `validate_payload(role, raw)` in artifacts.py before upserting.

Transaction ownership: matches the house convention in
`src/ai_driven/db/postgres/repository.py` — this class flushes but does
NOT commit. The caller (OrchestratorAgent in Task 16) owns the
transaction boundary so multi-step orchestration flows can commit atomically.
"""
from __future__ import annotations

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.db.postgres.models import TaskArtifact


class ArtifactRepository:
    """Thin data-access layer for the task_artifacts table."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def upsert(self, task_id: uuid.UUID, artifact: Artifact) -> None:
        """Insert or update the single row for (task_id, artifact.role)."""
        existing = await self._session.execute(
            select(TaskArtifact).where(
                TaskArtifact.task_id == task_id,
                TaskArtifact.role == artifact.role,
            )
        )
        row = existing.scalar_one_or_none()
        if row is None:
            self._session.add(
                TaskArtifact(
                    task_id=task_id,
                    role=artifact.role,
                    payload=artifact.payload,
                    verdict=artifact.verdict,
                    reasoning=artifact.reasoning,
                    model_used=artifact.model_used,
                    input_tokens=artifact.input_tokens,
                    output_tokens=artifact.output_tokens,
                    cost_usd=artifact.cost_usd,
                )
            )
        else:
            row.payload = artifact.payload
            row.verdict = artifact.verdict
            row.reasoning = artifact.reasoning
            row.model_used = artifact.model_used
            row.input_tokens = artifact.input_tokens
            row.output_tokens = artifact.output_tokens
            row.cost_usd = artifact.cost_usd
        await self._session.flush()

    async def list_for_task(self, task_id: uuid.UUID) -> dict[str, Artifact]:
        """Return all artifacts for a task, keyed by role."""
        result = await self._session.execute(
            select(TaskArtifact).where(TaskArtifact.task_id == task_id)
        )
        out: dict[str, Artifact] = {}
        for row in result.scalars():
            out[row.role] = Artifact(
                role=row.role,
                verdict=row.verdict,
                reasoning=row.reasoning,
                payload=row.payload,
                model_used=row.model_used,
                input_tokens=row.input_tokens,
                output_tokens=row.output_tokens,
                cost_usd=row.cost_usd,
            )
        return out
