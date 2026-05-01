"""Add 'awaiting_qa' interim status to ck_tasks_status.

Revision ID: d4e5f6a7b8c9
Revises: c3d4e5f6a7b8
Create Date: 2026-05-01

T22 post-coding QA: the CodingAgent now opens the PR and a subsequent
QA specialist validates the diff + acceptance criteria + security rules
asynchronously via a dedicated SQS queue.

Before T22, the coding worker transitioned a successful run to
``completed``. With T22, when the task's tier is ``full_workflow`` or
``multi_domain`` AND the worker enqueues a QA-task, the status moves to
the interim value ``awaiting_qa`` instead — explicit intermediate
state so webhook / Slack consumers aren't gaslit by a later ``qa_failed``
transition (coming in T23).

Scope: this migration adds ONLY ``awaiting_qa``. The related
``qa_failed`` value is T23's concern (it introduces the verdict→status
transition hook); adding an unused enum value here would create
coupling we'd need to unwind.

State machine after T22 (T23 adds qa_failed):

    queued → running → completed         (fast_path or non-live mode)
    queued → running → awaiting_qa → completed
                                   (T23 will also add: qa_failed)
    queued → running → blocked           (T21)
    queued → running → failed            (exception path)
"""
from typing import Sequence, Union

from alembic import op

revision: str = "d4e5f6a7b8c9"
down_revision: Union[str, None] = "c3d4e5f6a7b8"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# Canonical in-flight and terminal Task.status values after T22.
# Must match ai_driven.db.postgres.models.Task.status comments and the
# prior migration (c3d4e5f6a7b8) plus the new 'awaiting_qa' value.
_VALID_STATUSES_T22 = (
    "queued",
    "planning",
    "coding",
    "testing",
    "running",
    "awaiting_input",
    "paused",
    "awaiting_qa",
    "completed",
    "failed",
    "cancelled",
    "blocked",
)

# Pre-T22 (= T21) values, used on downgrade.
_VALID_STATUSES_PRE_T22 = (
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


def _recreate_check_constraint(values: tuple[str, ...]) -> None:
    rendered = ",".join(f"'{s}'" for s in values)
    op.drop_constraint("ck_tasks_status", "tasks", type_="check")
    op.create_check_constraint(
        "ck_tasks_status",
        "tasks",
        f"status IN ({rendered})",
    )


def upgrade() -> None:
    _recreate_check_constraint(_VALID_STATUSES_T22)


def downgrade() -> None:
    # Normalize any 'awaiting_qa' rows so the tighter constraint can be
    # re-applied. Downgrade happens in-place on a live DB; without this
    # UPDATE the CREATE CHECK would fail on every task that was persisted
    # under T22. We rewrite to 'completed' because 'awaiting_qa' represents
    # a successful coding run that's in limbo pending QA — it is closer to
    # 'completed' than to any other pre-T22 status.
    op.execute(
        """
        UPDATE tasks SET status = 'completed'
        WHERE status = 'awaiting_qa'
        """
    )
    _recreate_check_constraint(_VALID_STATUSES_PRE_T22)
