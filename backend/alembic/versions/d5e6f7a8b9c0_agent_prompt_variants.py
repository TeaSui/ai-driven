"""Prompt-variant registry for A/B testing + per-variant score aggregation.

Revision ID: d5e6f7a8b9c0
Revises: c4d5e6f7a8b9
Create Date: 2026-05-07

Two tables + a column on tasks:

  * ``agent_prompt_variants`` — registry of candidate system prompts keyed
    by (agent_type, name). ``weight`` drives the dispatch-time weighted
    random pick; 0 = disabled, any non-negative int scales the pick
    probability vs. siblings. ``is_control`` marks the single baseline
    variant for an agent_type (nullable-unique index enforces <=1).
  * ``tasks.prompt_variant_id`` — nullable FK stamped at dispatch so
    every eval row for that task can be attributed.
  * ``agent_prompt_variant_stats`` — per-variant per-dimension rollup
    maintained by a scheduled aggregation (n samples, avg score,
    last_updated). Kept as a materialized snapshot rather than a view
    so stats can be written by a background job without holding a long
    transaction on task_evaluations.

Dashboard reads stats via joins on task_evaluations + tasks —
agent_prompt_variant_stats is a cache; the authoritative source
stays the raw evaluations.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision: str = "d5e6f7a8b9c0"
down_revision: Union[str, None] = "c4d5e6f7a8b9"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "agent_prompt_variants",
        sa.Column(
            "id", UUID(as_uuid=True), primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column("agent_type", sa.String(32), nullable=False),
        sa.Column("name", sa.String(64), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("system_prompt", sa.Text(), nullable=False),
        # Dispatch-time weighted random pick. 0 = disabled (hidden from
        # picker without deleting the row + losing historical attribution).
        sa.Column("weight", sa.Integer(), nullable=False, server_default=sa.text("0")),
        # Exactly one variant per agent_type should be is_control=true.
        # Enforced via the partial unique index below.
        sa.Column("is_control", sa.Boolean(), nullable=False, server_default=sa.text("false")),
        sa.Column(
            "created_at", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
        sa.Column("created_by", sa.String(255), nullable=True),
        sa.UniqueConstraint("agent_type", "name", name="uq_variant_agent_name"),
    )
    # Only one control per agent_type. Using a partial unique index so
    # inactive/retired variants (is_control=false) don't collide.
    op.create_index(
        "ix_variant_one_control_per_agent",
        "agent_prompt_variants",
        ["agent_type"],
        unique=True,
        postgresql_where=sa.text("is_control"),
    )

    op.add_column(
        "tasks",
        sa.Column(
            "prompt_variant_id", UUID(as_uuid=True),
            sa.ForeignKey("agent_prompt_variants.id", ondelete="SET NULL"),
            nullable=True,
        ),
    )
    op.create_index(
        "ix_tasks_prompt_variant",
        "tasks",
        ["prompt_variant_id"],
        postgresql_where=sa.text("prompt_variant_id IS NOT NULL"),
    )

    op.create_table(
        "agent_prompt_variant_stats",
        sa.Column(
            "variant_id", UUID(as_uuid=True),
            sa.ForeignKey("agent_prompt_variants.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("dimension", sa.String(64), primary_key=True),
        sa.Column("n_samples", sa.Integer(), nullable=False),
        # Stored as NUMERIC(4,2) so rollups round cleanly and the API
        # surfaces identical values across sessions.
        sa.Column("avg_score", sa.Numeric(4, 2), nullable=False),
        sa.Column("stddev_score", sa.Numeric(4, 2), nullable=True),
        sa.Column(
            "last_updated", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
    )


def downgrade() -> None:
    op.drop_table("agent_prompt_variant_stats")
    op.drop_index("ix_tasks_prompt_variant", table_name="tasks")
    op.drop_column("tasks", "prompt_variant_id")
    op.drop_index(
        "ix_variant_one_control_per_agent",
        table_name="agent_prompt_variants",
    )
    op.drop_table("agent_prompt_variants")
