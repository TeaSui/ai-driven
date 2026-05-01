"""Add task_override_audit table, last_heartbeat_at column, and system seeds (T24).

Revision ID: f6a7b8c9d0e1
Revises: e5f6a7b8c9d0
Create Date: 2026-05-01

T24 escape hatches: admin override endpoint + background reapers.

This migration creates the scaffolding that both features rely on:

- ``tasks.last_heartbeat_at TIMESTAMPTZ`` (nullable). Written by
  ``consumer.py::_heartbeat`` every 5 min while a task is in flight.
  Reapers key on it to decide whether a ``running`` task is really
  stuck (vs actively doing work). Partial index on the 3 non-terminal
  statuses where the reaper bothers to look — keeps the index small.

- ``task_override_audit`` table. Every admin override (and every reaper
  reap) writes one row here. ``overridden_by_member_id`` is FK to
  team_members. Reapers use the system team_member UUID seeded below.

- Idempotent seeds: system team + system team_member. Fixed UUIDs so the
  reaper code can reference them at import time. ``ON CONFLICT DO NOTHING``
  so re-applying the migration (fresh env, prod replay, test fixtures) is
  safe. Downgrade does NOT delete these rows — audit rows written by T24
  reference them via FK and would be corrupted by a later re-upgrade.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision: str = "f6a7b8c9d0e1"
down_revision: Union[str, None] = "e5f6a7b8c9d0"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


SYSTEM_TEAM_ID = "00000000-0000-0000-0000-000000000000"
SYSTEM_MEMBER_ID = "00000000-0000-0000-0000-000000000001"


def upgrade() -> None:
    # --- 1. tasks.last_heartbeat_at column + partial index ---
    op.add_column(
        "tasks",
        sa.Column("last_heartbeat_at", sa.DateTime(timezone=True), nullable=True),
    )
    # Partial index restricted to the 3 statuses the reapers scan. A full
    # btree over the column would be dominated by rows in terminal states
    # where the column will never be updated again — wasted space + write
    # overhead. PostgreSQL-specific; SQLite (test) ignores the WHERE clause.
    op.create_index(
        "ix_tasks_last_heartbeat_at",
        "tasks",
        ["last_heartbeat_at"],
        postgresql_where=sa.text(
            "status IN ('running', 'orchestrating', 'awaiting_qa')"
        ),
    )

    # --- 2. task_override_audit table ---
    op.create_table(
        "task_override_audit",
        sa.Column(
            "id",
            UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "task_id",
            UUID(as_uuid=True),
            sa.ForeignKey("tasks.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "overridden_by_member_id",
            UUID(as_uuid=True),
            sa.ForeignKey("team_members.id"),
            nullable=False,
        ),
        sa.Column("from_status", sa.Text(), nullable=False),
        sa.Column("to_status", sa.Text(), nullable=False),
        # 'rerun_coding' | 'rerun_qa' | 'force_pass' | 'reap'
        sa.Column("action", sa.Text(), nullable=False),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "ix_task_override_audit_task_id",
        "task_override_audit",
        ["task_id"],
    )

    # --- 3. idempotent system team + member seeds ---
    # Fixed UUIDs. Reused across all environments so reaper code can
    # reference the constant without a bootstrap step. ON CONFLICT DO
    # NOTHING guards the rare re-upgrade scenario on an already-seeded DB.
    op.execute(
        sa.text(
            """
            INSERT INTO teams (id, name, api_key_hash, claude_provider,
                               settings_json, is_active, max_concurrent_tasks,
                               created_at, updated_at)
            VALUES (:tid, 'system', '__system__', 'anthropic',
                    '{}'::json, true, 5, now(), now())
            ON CONFLICT (id) DO NOTHING
            """
        ).bindparams(tid=SYSTEM_TEAM_ID)
    )
    op.execute(
        sa.text(
            """
            INSERT INTO team_members (id, team_id, email, role,
                                      api_key_hash, is_active, created_at)
            VALUES (:mid, :tid, 'system@ai-driven.local', 'admin',
                    NULL, true, now())
            ON CONFLICT (id) DO NOTHING
            """
        ).bindparams(mid=SYSTEM_MEMBER_ID, tid=SYSTEM_TEAM_ID)
    )


def downgrade() -> None:
    # Drop the audit table + heartbeat column/index. Do NOT delete the
    # system team/member seeds — audit rows written before downgrade may
    # live outside this migration (future re-upgrade) and would break FK
    # on re-insert.
    op.drop_index(
        "ix_task_override_audit_task_id", table_name="task_override_audit"
    )
    op.drop_table("task_override_audit")
    op.drop_index("ix_tasks_last_heartbeat_at", table_name="tasks")
    op.drop_column("tasks", "last_heartbeat_at")
