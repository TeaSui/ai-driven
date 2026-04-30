"""Add task_artifacts table and tasks.orchestration_tier column.

Revision ID: a1b2c3d4e5fa
Revises: a1b2c3d4e5f9
Create Date: 2026-04-29

Phase 1 of the orchestrator + specialists rollout. Adds:
  * task_artifacts: one row per (task_id, role) holding the structured
    output of a specialist run (BA, stack_analyst, security, qa, etc.).
  * tasks.orchestration_tier: classifier output (fast_path|full_workflow|
    multi_domain). NULL for pre-orchestrator tasks.

Both changes are additive and zero-downtime. See:
docs/specs/2026-04-29-orchestrator-specialists-design.md
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "a1b2c3d4e5fa"
down_revision: Union[str, None] = "a1b2c3d4e5f9"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


_VALID_ROLES = (
    "business_analyst",
    "stack_analyst",
    "security",
    "qa",
    "tech_lead",
    "ux_designer",
    "api_test",
)
_VALID_VERDICTS = (
    "pass",
    "pass_with_notes",
    "block",
    "error",
    "not_implemented",
)
_VALID_TIERS = ("fast_path", "full_workflow", "multi_domain")


def upgrade() -> None:
    # pgcrypto provides gen_random_uuid(). It is NOT guaranteed to be
    # pre-installed on the target Postgres (depends on image/version).
    op.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto;")

    op.create_table(
        "task_artifacts",
        sa.Column(
            "id",
            sa.dialects.postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "task_id",
            sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey("tasks.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("role", sa.String(32), nullable=False),
        sa.Column("payload", sa.dialects.postgresql.JSONB, nullable=False),
        sa.Column("verdict", sa.String(16), nullable=False),
        sa.Column("reasoning", sa.Text, nullable=True),
        sa.Column("model_used", sa.String(100), nullable=True),
        sa.Column("input_tokens", sa.Integer, nullable=False, server_default="0"),
        sa.Column("output_tokens", sa.Integer, nullable=False, server_default="0"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "role IN (" + ",".join(f"'{r}'" for r in _VALID_ROLES) + ")",
            name="ck_task_artifacts_role",
        ),
        sa.CheckConstraint(
            "verdict IN (" + ",".join(f"'{v}'" for v in _VALID_VERDICTS) + ")",
            name="ck_task_artifacts_verdict",
        ),
    )
    op.create_index(
        "idx_task_artifacts_task", "task_artifacts", ["task_id"], unique=False,
    )
    op.create_index(
        "idx_task_artifacts_task_role",
        "task_artifacts",
        ["task_id", "role"],
        unique=True,
    )

    op.add_column(
        "tasks",
        sa.Column("orchestration_tier", sa.String(16), nullable=True),
    )
    op.create_check_constraint(
        "ck_tasks_orchestration_tier",
        "tasks",
        "orchestration_tier IS NULL OR orchestration_tier IN ("
        + ",".join(f"'{t}'" for t in _VALID_TIERS)
        + ")",
    )


def downgrade() -> None:
    op.drop_constraint("ck_tasks_orchestration_tier", "tasks", type_="check")
    op.drop_column("tasks", "orchestration_tier")
    op.drop_index("idx_task_artifacts_task_role", table_name="task_artifacts")
    op.drop_index("idx_task_artifacts_task", table_name="task_artifacts")
    op.drop_table("task_artifacts")
