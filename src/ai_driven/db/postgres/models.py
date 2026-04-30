"""SQLAlchemy models for PostgreSQL domain data."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from decimal import Decimal

import sqlalchemy as sa
from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    Enum as SAEnum,
    Float,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Team(Base):
    __tablename__ = "teams"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(255), unique=True, nullable=False)
    api_key_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    claude_provider: Mapped[str] = mapped_column(String(50), default="anthropic")
    settings_json: Mapped[dict] = mapped_column(JSON, default=dict)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    max_concurrent_tasks: Mapped[int] = mapped_column(Integer, default=5)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    repositories: Mapped[list[Repository]] = relationship(back_populates="team")
    tasks: Mapped[list[Task]] = relationship(back_populates="team")


class Repository(Base):
    __tablename__ = "repositories"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    team_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("teams.id"), nullable=False)
    scm_type: Mapped[str] = mapped_column(String(20), nullable=False)  # github | bitbucket
    full_name: Mapped[str] = mapped_column(String(255), nullable=False)
    clone_url: Mapped[str] = mapped_column(String(500), nullable=False)
    default_branch: Mapped[str] = mapped_column(String(100), default="main")
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    settings_json: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    team: Mapped[Team] = relationship(back_populates="repositories")
    tasks: Mapped[list[Task]] = relationship(back_populates="repository")


class Task(Base):
    __tablename__ = "tasks"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    team_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("teams.id"), nullable=False)
    repo_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("repositories.id"), nullable=True)
    jira_key: Mapped[str | None] = mapped_column(String(50), nullable=True)
    agent_type: Mapped[str] = mapped_column(String(50), nullable=False)  # coding | review | workflow | devops
    orchestration_tier: Mapped[str | None] = mapped_column(String(16), nullable=True)  # fast_path | full_workflow | multi_domain | NULL (pre-orchestrator)
    status: Mapped[str] = mapped_column(String(50), default="queued")
    # Status flow: queued → planning → coding → testing → pr_created → completed | failed
    title: Mapped[str] = mapped_column(String(500), default="")
    input_data: Mapped[dict] = mapped_column(JSON, default=dict)
    output_data: Mapped[dict] = mapped_column(JSON, default=dict)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    total_tokens: Mapped[int] = mapped_column(Integer, default=0)
    total_cost_usd: Mapped[float] = mapped_column(Float, default=0.0)
    iterations: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    team: Mapped[Team] = relationship(back_populates="tasks")
    repository: Mapped[Repository | None] = relationship(back_populates="tasks")
    reviews: Mapped[list[Review]] = relationship(back_populates="task")
    conversations: Mapped[list[ConversationRecord]] = relationship(back_populates="task")


class Review(Base):
    __tablename__ = "reviews"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    task_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("tasks.id"), nullable=False)
    pr_url: Mapped[str] = mapped_column(String(500), nullable=False)
    pr_number: Mapped[int] = mapped_column(Integer, nullable=False)
    repo_full_name: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(String(50), default="pending")  # pending | approved | changes_requested
    summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    comments_json: Mapped[list] = mapped_column(JSON, default=list)
    issues_found: Mapped[int] = mapped_column(Integer, default=0)
    critical_issues: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    task: Mapped[Task] = relationship(back_populates="reviews")


class Incident(Base):
    __tablename__ = "incidents"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    team_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("teams.id"), nullable=False)
    title: Mapped[str] = mapped_column(String(500), nullable=False)
    severity: Mapped[str] = mapped_column(String(10), nullable=False)  # P1 | P2 | P3 | P4
    status: Mapped[str] = mapped_column(String(50), default="active")  # active | triaging | mitigated | resolved
    service: Mapped[str] = mapped_column(String(255), nullable=False)
    metric: Mapped[str] = mapped_column(String(255), default="")
    current_value: Mapped[str] = mapped_column(String(100), default="")
    threshold: Mapped[str] = mapped_column(String(100), default="")
    rca_json: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)

    team: Mapped[Team] = relationship()


class TeamMember(Base):
    """Represents a member within a team for RBAC.

    Each member has a role (owner/admin/member/viewer) and an optional
    per-member API key hash for future individual authentication.
    """

    __tablename__ = "team_members"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    team_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("teams.id"), nullable=False, index=True
    )
    email: Mapped[str] = mapped_column(String(255), nullable=False)
    role: Mapped[str] = mapped_column(String(20), nullable=False, default="member")
    api_key_hash: Mapped[str | None] = mapped_column(
        String(64), nullable=True, unique=True
    )
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    team: Mapped[Team] = relationship()


class ConversationRecord(Base):
    __tablename__ = "conversations"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    task_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("tasks.id"), nullable=False)
    messages_json: Mapped[list] = mapped_column(JSON, default=list)
    total_input_tokens: Mapped[int] = mapped_column(Integer, default=0)
    total_output_tokens: Mapped[int] = mapped_column(Integer, default=0)
    total_cost_usd: Mapped[float] = mapped_column(Float, default=0.0)
    model_used: Mapped[str] = mapped_column(String(100), default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)

    task: Mapped[Task] = relationship(back_populates="conversations")


class TaskArtifact(Base):
    """Structured output of a specialist run, one row per (task_id, role).

    Written by the orchestrator after each specialist completes. Enforces
    one artifact per (task_id, role) via a unique composite index —
    re-runs UPDATE, repair/retry history is in the agent_events stream.
    Column set mirrors the migration at
    alembic/versions/a1b2c3d4e5fa_orchestration_artifacts.py.
    """

    __tablename__ = "task_artifacts"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("tasks.id", ondelete="CASCADE"),
        nullable=False,
    )
    role: Mapped[str] = mapped_column(String(32), nullable=False)
    payload: Mapped[dict] = mapped_column(JSONB, nullable=False)
    verdict: Mapped[str] = mapped_column(String(16), nullable=False)
    reasoning: Mapped[str | None] = mapped_column(Text, nullable=True)
    model_used: Mapped[str | None] = mapped_column(String(100), nullable=True)
    input_tokens: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    # Per-artifact dollar cost. Added by the orchestrator-revision spec
    # (docs/specs/2026-04-30-orchestrator-revision.md §2.2) so shadow-mode
    # orchestration spend is visible to the cost-attribution dashboard.
    # NUMERIC(10,6) gives exact micro-dollar precision — avoids binary
    # float drift when summing many small specialist costs.
    cost_usd: Mapped[Decimal] = mapped_column(
        Numeric(10, 6), nullable=False, default=Decimal("0"),
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow
    )

    __table_args__ = (
        sa.Index("idx_task_artifacts_task", "task_id"),
        sa.Index("idx_task_artifacts_task_role", "task_id", "role", unique=True),
    )
