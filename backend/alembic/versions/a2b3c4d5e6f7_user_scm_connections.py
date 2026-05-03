"""Add user_scm_connections table (SCM OAuth Connections PR 1).

Revision ID: a2b3c4d5e6f7
Revises: f6a7b8c9d0e1
Create Date: 2026-05-03

Per-user OAuth connections to GitHub / Bitbucket. Replaces the single
``AI_DRIVEN_GITHUB_TOKEN`` model with one row per (team_member, provider)
so review actions can be attributed to the human clicking the button.

Schema notes:

- ``team_id`` is denormalized from ``team_members.team_id``. The invariant
  is that ``team_members.team_id`` is immutable — nothing in the current
  codebase modifies it after insert — so the denormalized column never
  goes stale. This is required for the ``uq_team_provider_scmuser``
  uniqueness guard, which needs a single-column team reference it can
  include in the unique constraint.
- Two uniqueness guards:
    * ``uq_member_provider`` ``(team_member_id, provider)`` — one
      connection per (member, provider). Drives the upsert on callback.
    * ``uq_team_provider_scmuser`` ``(team_id, provider, scm_user_id)`` —
      prevents two members of the same team both claiming the same
      GitHub identity (a real failure mode for shared-machine setups).
- Tokens live in ``*_enc BYTEA`` columns — Fernet ciphertext. The
  migration is agnostic to the encryption scheme; rotation is handled
  in application code (``auth/scm_tokens.py``).
- CHECK constraint on ``provider`` matches the codebase's two supported
  SCMs. Adding a third provider will require a new migration.
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision: str = "a2b3c4d5e6f7"
down_revision: Union[str, None] = "f6a7b8c9d0e1"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "user_scm_connections",
        sa.Column(
            "id",
            UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "team_id",
            UUID(as_uuid=True),
            sa.ForeignKey("teams.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "team_member_id",
            UUID(as_uuid=True),
            sa.ForeignKey("team_members.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("provider", sa.String(20), nullable=False),
        sa.Column("scm_user_id", sa.String(64), nullable=False),
        sa.Column("scm_username", sa.String(128), nullable=False),
        sa.Column("scm_email", sa.String(320), nullable=True),
        sa.Column("access_token_enc", sa.LargeBinary(), nullable=False),
        sa.Column("refresh_token_enc", sa.LargeBinary(), nullable=True),
        sa.Column("scope", sa.String(255), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "connected_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("last_used_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_refreshed_at", sa.DateTime(timezone=True), nullable=True),
        sa.UniqueConstraint(
            "team_member_id", "provider", name="uq_member_provider"
        ),
        sa.UniqueConstraint(
            "team_id", "provider", "scm_user_id",
            name="uq_team_provider_scmuser",
        ),
        sa.CheckConstraint(
            "provider IN ('github', 'bitbucket')", name="ck_provider"
        ),
    )
    op.create_index(
        "ix_user_scm_connections_member",
        "user_scm_connections",
        ["team_member_id"],
    )
    op.create_index(
        "ix_user_scm_connections_team",
        "user_scm_connections",
        ["team_id"],
    )


def downgrade() -> None:
    # Drops the table outright — all stored connections are lost. Users
    # reconnect on next upgrade (documented in
    # docs/runbooks/07-scm-oauth.md). Acceptable because the OAuth flow
    # is self-service.
    op.drop_index(
        "ix_user_scm_connections_team", table_name="user_scm_connections"
    )
    op.drop_index(
        "ix_user_scm_connections_member", table_name="user_scm_connections"
    )
    op.drop_table("user_scm_connections")
