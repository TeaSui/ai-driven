"""Add 'blocked' terminal status to ck_tasks_status.

Revision ID: c3d4e5f6a7b8
Revises: b2c3d4e5f6a7
Create Date: 2026-05-01

T21 strict gating: the orchestrator worker halts the pipeline when a
specialist returns verdict='block' (and live mode is stamped on the
SQS message). Those tasks transition to status='blocked' so the
dashboard can distinguish "coding agent errored" (failed) from
"upstream specialist said do not proceed" (blocked) without mangling
the success-rate denominator.

Scope: ONLY adds 'blocked'. The related 'qa_failed' value is T22's
concern (it requires separating pre- and post-coding QA, which T21
explicitly defers). Adding unused enum values here would create
coupling we'd need to unwind.

See: pre-baked red-team finding #1 (QA pre-coding false-halt) and #6
(drop qa_failed from T21) in the T21 task brief.
"""
from typing import Sequence, Union

from alembic import op

revision: str = "c3d4e5f6a7b8"
down_revision: Union[str, None] = "b2c3d4e5f6a7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# Canonical terminal and in-flight statuses for Task.status after T21.
# Must match ai_driven.db.postgres.models.Task.status comments and the
# prior migration (a1b2c3d4e5f9) plus the new 'blocked' value.
_VALID_STATUSES_T21 = (
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
    "blocked",
)

_VALID_STATUSES_PRE_T21 = (
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


def _recreate_check_constraint(values: tuple[str, ...]) -> None:
    rendered = ",".join(f"'{s}'" for s in values)
    op.drop_constraint("ck_tasks_status", "tasks", type_="check")
    op.create_check_constraint(
        "ck_tasks_status",
        "tasks",
        f"status IN ({rendered})",
    )


def upgrade() -> None:
    _recreate_check_constraint(_VALID_STATUSES_T21)


def downgrade() -> None:
    # Normalize any 'blocked' rows so the tighter constraint can be re-applied.
    # Downgrade happens in-place on a live DB; without this UPDATE the
    # CREATE CHECK would fail with "violates check constraint" on every
    # block that was recorded under T21.
    op.execute(
        """
        UPDATE tasks SET status = 'failed'
        WHERE status = 'blocked'
        """
    )
    _recreate_check_constraint(_VALID_STATUSES_PRE_T21)
