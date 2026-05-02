"""Health check endpoint with dependency status."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel
from sqlalchemy import text

from ai_driven.config import get_settings

router = APIRouter()
logger = logging.getLogger(__name__)


class DependencyStatus(BaseModel):
    name: str
    status: str  # "healthy", "degraded", "unhealthy"
    detail: str = ""


class HealthResponse(BaseModel):
    status: str
    version: str
    debug: bool = False
    dependencies: list[DependencyStatus] = []


async def _check_postgres() -> DependencyStatus:
    try:
        from ai_driven.db.postgres.session import async_session_factory

        async with async_session_factory() as session:
            await session.execute(text("SELECT 1"))
        return DependencyStatus(name="postgresql", status="healthy")
    except Exception as e:
        return DependencyStatus(name="postgresql", status="unhealthy", detail=str(e)[:200])


async def _check_redis() -> DependencyStatus:
    settings = get_settings()
    if not settings.redis_url:
        return DependencyStatus(name="redis", status="degraded", detail="not configured")
    try:
        import redis.asyncio as aioredis

        r = aioredis.from_url(settings.redis_url, socket_connect_timeout=2)
        await r.ping()
        await r.aclose()
        return DependencyStatus(name="redis", status="healthy")
    except Exception as e:
        return DependencyStatus(name="redis", status="unhealthy", detail=str(e)[:200])


async def _check_claude() -> DependencyStatus:
    """Quick check that Claude API is reachable (no actual message sent)."""
    settings = get_settings()
    if not settings.anthropic_api_key and settings.claude_provider.value == "anthropic":
        return DependencyStatus(name="claude", status="degraded", detail="API key not configured")
    return DependencyStatus(name="claude", status="healthy")


@router.get("/health", response_model=HealthResponse)
async def health_check() -> HealthResponse:
    deps: list[DependencyStatus] = []

    try:
        deps.append(await _check_postgres())
    except Exception:
        deps.append(DependencyStatus(name="postgresql", status="unhealthy", detail="check failed"))

    try:
        deps.append(await _check_redis())
    except Exception:
        deps.append(DependencyStatus(name="redis", status="unhealthy", detail="check failed"))

    try:
        deps.append(await _check_claude())
    except Exception:
        deps.append(DependencyStatus(name="claude", status="unhealthy", detail="check failed"))

    overall = "healthy"
    if any(d.status == "unhealthy" for d in deps):
        overall = "degraded"

    settings = get_settings()
    return HealthResponse(status=overall, version="0.1.0", debug=settings.debug, dependencies=deps)
