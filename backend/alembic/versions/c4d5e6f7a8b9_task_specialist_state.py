"""Add task_specialist_state table for HITL async-continuation (spec HITL-v1).

Revision ID: c4d5e6f7a8b9
Revises: b3c4d5e6f7a8
Create Date: 2026-05-07

When a specialist (starting with BusinessAnalyst) needs human input, it
persists its Claude conversation + pending questions into this table and
ACKs the SQS message. The user answers via POST /api/chat/tasks/:id/respond;
the handler re-enqueues an agent_type=specialist_resume SQS message that
loads the conversation and continues.

Natural key is (task_id, role) — only one HITL round per specialist per
task is in flight at a time. Partial index on pending_question IS NOT NULL
so the 4h awaiting_input reaper (background/reapers.py) can find stuck
rows without scanning the whole table.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import JSONB, UUID

revision: str = "c4d5e6f7a8b9"
down_revision: Union[str, None] = "b3c4d5e6f7a8"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "task_specialist_state",
        sa.Column(
            "task_id",
            UUID(as_uuid=True),
            sa.ForeignKey("tasks.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("role", sa.Text(), nullable=False),
        # Full Claude message list (incl. any pending tool_use block).
        # Used to resume the BA loop without replaying the cached system
        # prompt turn.
        sa.Column("conversation", JSONB(), nullable=False),
        # {questions:[{id,question,options,why}], round_id, asked_at}
        # NULL when answered / terminal.
        sa.Column("pending_question", JSONB(), nullable=True),
        # {answers:{<qid>:<text>}, round_id, answered_at}
        sa.Column("last_answer", JSONB(), nullable=True),
        # Per-task cap enforced in /respond. Spec default = 3.
        sa.Column(
            "rounds",
            sa.Integer(),
            nullable=False,
            server_default=sa.text("0"),
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("task_id", "role"),
    )
    # Reaper scans rows with an OPEN pending_question older than 4h.
    # Partial index keeps the working set small — most rows will have
    # pending_question=NULL once answered.
    op.create_index(
        "ix_specialist_state_pending",
        "task_specialist_state",
        ["updated_at"],
        postgresql_where=sa.text("pending_question IS NOT NULL"),
    )


def downgrade() -> None:
    op.drop_index(
        "ix_specialist_state_pending", table_name="task_specialist_state"
    )
    op.drop_table("task_specialist_state")
