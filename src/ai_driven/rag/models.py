"""SQLAlchemy models for RAG code embeddings."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from ai_driven.db.postgres.models import Base

# Import conditionally to avoid failure when pgvector not installed
try:
    from pgvector.sqlalchemy import Vector
except ImportError:
    Vector = None  # type: ignore[assignment,misc]


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class CodeEmbedding(Base):
    __tablename__ = "code_embeddings"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    repo_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("repositories.id"), nullable=False)
    file_path: Mapped[str] = mapped_column(Text, nullable=False)
    chunk_content: Mapped[str] = mapped_column(Text, nullable=False)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    embedding = mapped_column(Vector(1024), nullable=False) if Vector else mapped_column(Text)
    metadata_json: Mapped[dict] = mapped_column(JSONB, default=dict)
    last_commit_sha: Mapped[str | None] = mapped_column(String(40), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    __table_args__ = (
        Index("idx_code_emb_repo", "repo_id"),
        Index("idx_code_emb_file", "repo_id", "file_path"),
    )
