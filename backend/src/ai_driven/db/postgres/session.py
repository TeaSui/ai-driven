"""PostgreSQL async session management."""

from __future__ import annotations

from typing import AsyncGenerator

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from ai_driven.config import get_settings

settings = get_settings()

engine: AsyncEngine = create_async_engine(
    settings.postgres_url,
    echo=settings.sql_echo,
    pool_size=20,
    max_overflow=10,
    pool_pre_ping=True,
)

async_session_factory = async_sessionmaker(
    engine,
    class_=AsyncSession,
    expire_on_commit=False,
)


async def init_db() -> None:
    """Initialize database — create tables if needed (dev only)."""
    if settings.environment.value == "local":
        import logging
        log = logging.getLogger(__name__)
        from sqlalchemy import text
        from ai_driven.db.postgres.models import Base
        # Import side-effect-only: registers TaskEvaluation + CodeEmbedding in
        # Base.metadata so create_all picks them up. Without this, F6 eval
        # scoring and F5 RAG indexing crash with "relation does not exist".
        import ai_driven.evaluation.models  # noqa: F401
        try:
            import ai_driven.rag.models  # noqa: F401
            rag_enabled = True
        except Exception as ex:  # pragma: no cover
            log.warning("skipping RAG model registration: %s", ex)
            rag_enabled = False

        # pgvector extension — attempt in an isolated autocommit connection so
        # a failure (e.g. role without superuser) doesn't poison the main
        # create_all transaction.
        if rag_enabled:
            try:
                ext_engine = engine.execution_options(isolation_level="AUTOCOMMIT")
                async with ext_engine.connect() as ext_conn:
                    await ext_conn.execute(text("CREATE EXTENSION IF NOT EXISTS vector"))
            except Exception as ex:
                log.warning(
                    "Could not create pgvector extension (%s). RAG table "
                    "creation will be skipped. Use pgvector/pgvector:pg16 or "
                    "run `CREATE EXTENSION vector;` manually as a superuser.",
                    ex,
                )
                rag_table = Base.metadata.tables.get("code_embeddings")
                if rag_table is not None:
                    Base.metadata.remove(rag_table)

        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    """FastAPI dependency for database sessions."""
    async with async_session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
