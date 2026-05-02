"""Evaluation runner — triggers post-task grading and persists results."""

from __future__ import annotations

import logging
import uuid
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.config import get_settings
from ai_driven.evaluation.grader import TaskGrader, EvalResult
from ai_driven.evaluation.models import TaskEvaluation

logger = logging.getLogger(__name__)


class EvaluationRunner:
    """Runs post-task evaluation and stores results in PostgreSQL."""

    def __init__(self, session: AsyncSession) -> None:
        self._session = session
        self._grader = TaskGrader()

    async def evaluate_task(
        self,
        task_id: uuid.UUID,
        task_input: str,
        agent_output: str,
        dimensions: list[str] | None = None,
        agent_type: str = "coding",
    ) -> list[EvalResult]:
        """Grade a task and persist evaluation results.

        Args:
            task_id: UUID of the completed task.
            task_input: The original task input/prompt.
            agent_output: The agent's final output text.
            dimensions: Optional list of dimensions to grade.
            agent_type: Agent type for selecting grading dimensions.

        Returns:
            List of evaluation results.
        """
        results = await self._grader.grade_all(task_input, agent_output, dimensions, agent_type=agent_type)

        for result in results:
            evaluation = TaskEvaluation(
                task_id=task_id,
                dimension=result.dimension,
                score=result.score,
                reasoning=result.reasoning,
                strengths=result.strengths,
                weaknesses=result.weaknesses,
                grader_model=result.grader_model,
            )
            self._session.add(evaluation)

        await self._session.flush()

        combined = self._grader.combined_score(results, agent_type=agent_type)
        logger.info(
            "Task %s evaluated: combined score %.1f/10 (%d dimensions)",
            task_id, combined, len(results),
        )

        return results


async def run_post_task_evaluation(
    task_id: str,
    task_input: str,
    agent_output: str,
    agent_type: str = "coding",
) -> None:
    """Fire-and-forget post-task evaluation.

    Called from base agent after task completion. Runs in a background task
    so it doesn't block the main agent flow.
    """
    settings = get_settings()
    if not settings.evaluation_enabled:
        return

    try:
        from ai_driven.db.postgres.session import async_session_factory

        async with async_session_factory() as session:
            runner = EvaluationRunner(session)
            await runner.evaluate_task(
                uuid.UUID(task_id), task_input, agent_output,
                agent_type=agent_type,
            )
            await session.commit()
    except Exception:
        logger.exception("Post-task evaluation failed for %s", task_id)
