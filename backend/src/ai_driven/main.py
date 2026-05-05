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
from ai_driven.api.auth.scm_oauth import router as scm_oauth_router
from ai_driven.api.chat.router import router as chat_router
from ai_driven.api.admin.router import router as admin_router
from ai_driven.api.error_handlers import register_error_handlers
from ai_driven.auth.middleware import APIKeyMiddleware
from ai_driven.config import get_settings
from ai_driven.db.postgres.session import engine, init_db
from ai_driven.observability.logging import setup_logging
from ai_driven.observability.tracing import setup_tracing


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None]:
    settings = get_settings()
    setup_logging(settings)
    setup_tracing(settings)
    # SCM OAuth: scrub ``code=<value>`` from uvicorn access logs so the
    # OAuth authorization code doesn't end up in log aggregation.
    # Attach here (not in setup_logging) because uvicorn wires the
    # ``uvicorn.access`` logger at startup; earlier attachment risks
    # being overwritten by uvicorn's own handler configuration.
    import logging as _logging
    from ai_driven.observability.log_scrub import ScrubOAuthCodeFilter
    _logging.getLogger("uvicorn.access").addFilter(ScrubOAuthCodeFilter())
    await init_db()
    yield
    await engine.dispose()


def create_app() -> FastAPI:
    settings = get_settings()
    is_prod = settings.environment.value == "production"

    app = FastAPI(
        title="AI-Driven Development Platform",
        version="0.1.0",
        lifespan=lifespan,
        # /docs and /redoc exposed outside production only.
        docs_url=None if is_prod else "/docs",
        redoc_url=None,
    )

    # CORS: explicit localhost:3000 allow-list for the Next.js UI to hit the
    # backend cross-origin (required for WebSocket connections that bypass
    # Next's rewrites, which drop the WS upgrade). Tighten further in prod
    # once the UI is hosted somewhere with a stable origin.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[
            "http://localhost:3000",
            "http://127.0.0.1:3000",
        ],
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
    app.include_router(scm_oauth_router, prefix="/api/auth/scm", tags=["auth", "scm-oauth"])
    app.include_router(chat_router, prefix="/api/chat", tags=["chat"])
    app.include_router(admin_router, prefix="/api/admin", tags=["admin"])

    return app


app = create_app()
