"""Add RAG code_embeddings and task_evaluations tables.

Revision ID: a1b2c3d4e5f6
Revises: d21815f37bd0
Create Date: 2026-04-21
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "a1b2c3d4e5f6"
down_revision: Union[str, None] = "d21815f37bd0"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Enable pgvector extension
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    # Code embeddings for RAG
    op.create_table(
        "code_embeddings",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("repo_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("repositories.id"), nullable=False),
        sa.Column("file_path", sa.Text(), nullable=False),
        sa.Column("chunk_content", sa.Text(), nullable=False),
        sa.Column("chunk_index", sa.Integer(), nullable=False),
        sa.Column("metadata_json", postgresql.JSONB(), server_default="{}"),
        sa.Column("last_commit_sha", sa.String(40), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    # Add pgvector column via raw SQL — alembic has no native pgvector type
    op.execute("ALTER TABLE code_embeddings ADD COLUMN embedding vector(1024) NOT NULL")

    op.create_index("idx_code_emb_repo", "code_embeddings", ["repo_id"])
    op.create_index("idx_code_emb_file", "code_embeddings", ["repo_id", "file_path"])

    # Task evaluations for grading
    op.create_table(
        "task_evaluations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("task_id", postgresql.UUID(as_uuid=True), sa.ForeignKey("tasks.id"), nullable=False),
        sa.Column("dimension", sa.String(50), nullable=False),
        sa.Column("score", sa.Integer(), nullable=True),
        sa.Column("reasoning", sa.Text(), nullable=True),
        sa.Column("strengths", postgresql.ARRAY(sa.Text()), server_default="{}"),
        sa.Column("weaknesses", postgresql.ARRAY(sa.Text()), server_default="{}"),
        sa.Column("grader_model", sa.String(100), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("idx_eval_task", "task_evaluations", ["task_id"])


def downgrade() -> None:
    op.drop_index("idx_eval_task", table_name="task_evaluations")
    op.drop_table("task_evaluations")
    op.drop_index("idx_code_emb_file", table_name="code_embeddings")
    op.drop_index("idx_code_emb_repo", table_name="code_embeddings")
    op.drop_table("code_embeddings")
    op.execute("DROP EXTENSION IF EXISTS vector")
