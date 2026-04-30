"""Unit tests for the orchestrator artifact layer.

This file is seeded in Task 2 (ORM model) and extended by Tasks 3 and 4
(Pydantic schemas, repository). Keep new tests appended in task order so
the file reads top-to-bottom matching the plan.
"""
from __future__ import annotations

from ai_driven.db.postgres.models import Task, TaskArtifact


def test_task_artifact_model_columns() -> None:
    cols = {c.name for c in TaskArtifact.__table__.columns}
    assert cols == {
        "id", "task_id", "role", "payload", "verdict", "reasoning",
        "model_used", "input_tokens", "output_tokens", "created_at",
    }


def test_task_artifact_unique_index_on_task_role() -> None:
    indexes = {idx.name: list(idx.columns.keys()) for idx in TaskArtifact.__table__.indexes}
    assert "idx_task_artifacts_task_role" in indexes
    assert indexes["idx_task_artifacts_task_role"] == ["task_id", "role"]


def test_task_has_orchestration_tier_column() -> None:
    cols = {c.name for c in Task.__table__.columns}
    assert "orchestration_tier" in cols
