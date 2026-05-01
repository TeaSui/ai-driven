"""FastAPI application entrypoint."""

from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from ai_driven.api.health import router as health_router
from ai_driven.api.webhooks.github import router as github_webhook_router
from ai_driven.api.webhooks.bitbucket import router as bitbucket_webhook_router
from ai_driven.api.webhooks.jira import router as jira_webhook_router
from ai_driven.api.webhooks.datadog import router as datadog_webhook_router
from ai_driven.api.webhooks.slack import router as slack_webhook_router
from ai_driven.api.auth.router import router as auth_router
from ai_driven.api.chat.router import router as chat_router
from ai_driven.api.admin.router import router as admin_router
from ai_driven.api.error_handlers import register_error_handlers
from ai_driven.auth.middleware import APIKeyMiddleware
from ai_driven.config import get_settings
from ai_driven.db.postgres.session import engine, init_db
from ai_driven.observability.logging import setup_logging
from ai_driven.observability.tracing import setup_tracing


async def _ensure_debug_team() -> None:
    """Create the sentinel debug team if it doesn't exist (debug mode only)."""
    import uuid
    from ai_driven.db.postgres.session import async_session_factory
    from ai_driven.db.postgres.models import Team

    sentinel_id = uuid.UUID("00000000-0000-0000-0000-000000000000")
    async with async_session_factory() as session:
        existing = await session.get(Team, sentinel_id)
        if existing is None:
            session.add(Team(
                id=sentinel_id,
                name="Debug Team",
                api_key_hash="debug",
                claude_provider="bedrock",
                max_concurrent_tasks=5,
            ))
            await session.commit()


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    settings = get_settings()
    setup_logging(settings)
    setup_tracing(settings)
    await init_db()
    if settings.debug:
        await _ensure_debug_team()
    yield
    await engine.dispose()


def create_app() -> FastAPI:
    settings = get_settings()

    # RULE-E3: Block debug mode in production — refuse to start
    if settings.debug and settings.environment.value == "production":
        import sys

        logger = __import__("logging").getLogger(__name__)
        logger.critical(
            "FATAL: AI_DRIVEN_DEBUG=true in PRODUCTION environment. "
            "Refusing to start — debug mode bypasses authentication."
        )
        sys.exit(1)

    app = FastAPI(
        title="AI-Driven Development Platform",
        version="0.1.0",
        lifespan=lifespan,
        docs_url="/docs" if settings.debug else None,
        redoc_url=None,
    )

    # CORS: debug = wildcard; non-debug = explicit localhost:3000 allow-list
    # so the Next.js UI can hit the backend cross-origin (required for
    # WebSocket connections that bypass Next's rewrites, which drop the
    # WS upgrade). Tighten this list when deploying.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=(
            ["*"] if settings.debug else [
                "http://localhost:3000",
                "http://127.0.0.1:3000",
            ]
        ),
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_middleware(APIKeyMiddleware)

    register_error_handlers(app)

    app.include_router(health_router)
    app.include_router(github_webhook_router, prefix="/webhooks/github", tags=["webhooks"])
    app.include_router(bitbucket_webhook_router, prefix="/webhooks/bitbucket", tags=["webhooks"])
    app.include_router(jira_webhook_router, prefix="/webhooks/jira", tags=["webhooks"])
    app.include_router(datadog_webhook_router, prefix="/webhooks/datadog", tags=["webhooks"])
    app.include_router(slack_webhook_router, prefix="/webhooks/slack", tags=["webhooks"])
    app.include_router(auth_router, prefix="/api/auth", tags=["auth"])
    app.include_router(chat_router, prefix="/api/chat", tags=["chat"])
    app.include_router(admin_router, prefix="/api/admin", tags=["admin"])

    return app


app = create_app()
