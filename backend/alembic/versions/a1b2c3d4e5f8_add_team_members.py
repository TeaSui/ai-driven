"""Add team_members table for RBAC.

Revision ID: a1b2c3d4e5f8
Revises: a1b2c3d4e5f7
Create Date: 2026-04-22

Adds the team_members table which stores per-member roles and optional
per-member API key hashes for future individual authentication.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision: str = "a1b2c3d4e5f8"
down_revision: Union[str, None] = "a1b2c3d4e5f7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "team_members",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("team_id", UUID(as_uuid=True), sa.ForeignKey("teams.id"), nullable=False),
        sa.Column("email", sa.String(255), nullable=False),
        sa.Column("role", sa.String(20), nullable=False, server_default="member"),
        sa.Column("api_key_hash", sa.String(64), nullable=True),
        sa.Column("is_active", sa.Boolean(), server_default=sa.text("true"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )

    # Index on team_id for listing members of a team
    op.create_index("ix_team_members_team_id", "team_members", ["team_id"])

    # Unique constraint on api_key_hash (only non-NULL values) for future per-member auth
    op.create_index(
        "ix_team_members_api_key_hash",
        "team_members",
        ["api_key_hash"],
        unique=True,
        postgresql_where=sa.text("api_key_hash IS NOT NULL"),
    )

    # Unique constraint: one email per team
    op.create_index(
        "ix_team_members_team_email",
        "team_members",
        ["team_id", "email"],
        unique=True,
    )

    # CHECK constraint to restrict role values
    op.create_check_constraint(
        "ck_team_members_role",
        "team_members",
        "role IN ('owner', 'admin', 'member', 'viewer')",
    )


def downgrade() -> None:
    op.drop_constraint("ck_team_members_role", "team_members", type_="check")
    op.drop_index("ix_team_members_team_email", table_name="team_members")
    op.drop_index("ix_team_members_api_key_hash", table_name="team_members")
    op.drop_index("ix_team_members_team_id", table_name="team_members")
    op.drop_table("team_members")
