"""Add cost_usd column to task_artifacts.

Revision ID: b2c3d4e5f6a7
Revises: a1b2c3d4e5fa
Create Date: 2026-04-30

Per docs/specs/2026-04-30-orchestrator-revision.md §2.2. Shadow-mode
orchestration spend is currently invisible to the cost-attribution
dashboard; persisting per-artifact cost lets the dashboard sum
specialist + coding + review spend across a single task.

NUMERIC(10,6) gives exact micro-dollar precision. Additive, zero-
downtime, non-null with a server default of 0 so existing rows are
backfilled at migration time without an extra UPDATE pass.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "b2c3d4e5f6a7"
down_revision: Union[str, None] = "a1b2c3d4e5fa"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "task_artifacts",
        sa.Column(
            "cost_usd",
            sa.Numeric(10, 6),
            nullable=False,
            server_default="0",
        ),
    )


def downgrade() -> None:
    op.drop_column("task_artifacts", "cost_usd")
