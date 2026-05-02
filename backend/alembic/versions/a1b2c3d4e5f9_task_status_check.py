"""Add CHECK constraint on tasks.status covering the cancelled terminal state.

Revision ID: a1b2c3d4e5f9
Revises: a1b2c3d4e5f8
Create Date: 2026-04-24

User-initiated stops (/api/chat/tasks/{id}/stop) now set status='cancelled'
instead of 'failed'. This migration pins the valid status set at the DB
level so a future writer can't silently introduce a new value that bypasses
the success-rate math (which counts completed / (completed+failed) — cancelled
is excluded by design).

See: docs/implementation/08-auth-and-ws-security.md Phase 1.
"""
from typing import Sequence, Union

from alembic import op

revision: str = "a1b2c3d4e5f9"
down_revision: Union[str, None] = "a1b2c3d4e5f8"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# Canonical terminal and in-flight statuses for Task.status.
# Must match ai_driven.db.postgres.models.Task.status comments.
_VALID_STATUSES = (
    "queued",
    "planning",
    "coding",
    "testing",
    "running",
    "awaiting_input",
    "paused",
    "completed",
    "failed",
    "cancelled",
)


def upgrade() -> None:
    # 1. Normalize any stray cancel-like values.
    op.execute(
        """
        UPDATE tasks SET status = 'cancelled'
        WHERE status IN ('canceled', 'stopped', 'aborted')
        """
    )

    # 2. Enforce the canonical set at the column level.
    values = ",".join(f"'{s}'" for s in _VALID_STATUSES)
    op.create_check_constraint(
        "ck_tasks_status",
        "tasks",
        f"status IN ({values})",
    )


def downgrade() -> None:
    op.drop_constraint("ck_tasks_status", "tasks", type_="check")
