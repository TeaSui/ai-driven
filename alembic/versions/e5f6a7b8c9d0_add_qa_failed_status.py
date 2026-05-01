"""Add 'qa_failed' terminal status to ck_tasks_status.

Revision ID: e5f6a7b8c9d0
Revises: d4e5f6a7b8c9
Create Date: 2026-05-01

T23 QA verdict -> Task status: the post-coding QA specialist (T22) writes
``output_data.qa_verdict`` asynchronously. T23 bridges that verdict to a
terminal task status:

- verdict in {pass, pass_with_notes, error, not_implemented} -> ``completed``
- verdict == ``block`` -> ``qa_failed`` (new; this migration)

``qa_failed`` is distinct from ``failed`` so admin dashboards can separate
"coding raised an exception" (failed) from "coding succeeded but QA found a
blocking gap" (qa_failed). The latter is a triage bucket that T24 will wire
an operator override endpoint onto.

State machine after T23 (no more values added in Phase 2):

    queued -> running -> completed          (fast_path or non-live mode)
    queued -> running -> awaiting_qa -> completed    (QA pass/pass_with_notes/error)
    queued -> running -> awaiting_qa -> qa_failed    (QA block, live mode)
    queued -> running -> blocked            (T21 specialist block)
    queued -> running -> failed             (exception path)
"""
from typing import Sequence, Union

from alembic import op

revision: str = "e5f6a7b8c9d0"
down_revision: Union[str, None] = "d4e5f6a7b8c9"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# Canonical Task.status values after T23.
# Must match ai_driven.db.postgres.models.Task.status comments and the
# prior migration (d4e5f6a7b8c9) plus the new 'qa_failed' value.
_VALID_STATUSES_T23 = (
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
    "qa_failed",
)

# Pre-T23 (= T22) values, used on downgrade.
_VALID_STATUSES_PRE_T23 = (
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


def _recreate_check_constraint(values: tuple[str, ...]) -> None:
    rendered = ",".join(f"'{s}'" for s in values)
    op.drop_constraint("ck_tasks_status", "tasks", type_="check")
    op.create_check_constraint(
        "ck_tasks_status",
        "tasks",
        f"status IN ({rendered})",
    )


def upgrade() -> None:
    _recreate_check_constraint(_VALID_STATUSES_T23)


def downgrade() -> None:
    # Normalize any 'qa_failed' rows so the tighter constraint can be
    # re-applied. Downgrade happens in-place on a live DB; without this
    # UPDATE the CREATE CHECK would fail on every task that was persisted
    # under T23. We rewrite to 'blocked' because 'qa_failed' represents a
    # QA-detected blocking gap -- semantically closer to 'blocked' (another
    # triage bucket) than to 'failed' (an exception).
    op.execute(
        """
        UPDATE tasks SET status = 'blocked'
        WHERE status = 'qa_failed'
        """
    )
    _recreate_check_constraint(_VALID_STATUSES_PRE_T23)
