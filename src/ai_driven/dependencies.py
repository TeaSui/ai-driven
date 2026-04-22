"""FastAPI dependency injection providers."""

from __future__ import annotations

import uuid
from functools import lru_cache

from fastapi import Depends, HTTPException, Request

from ai_driven.claude.client import ClaudeClient
from ai_driven.claude.provider import create_provider
from ai_driven.config import get_settings
from ai_driven.db.postgres.models import Task
from ai_driven.db.postgres.repository import TaskRepository
from ai_driven.db.postgres.session import async_session_factory
from ai_driven.jira.client import JiraClient
from ai_driven.scm.github_client import GitHubClient
from ai_driven.scm.bitbucket_client import BitbucketClient


# ── Auth dependencies ────────────────────────────────────────────────────────

# Valid roles accepted by require_role — kept as a constant to avoid typos
_VALID_ROLES = frozenset({"owner", "admin", "member", "viewer"})


def require_role(*allowed_roles: str):
    """FastAPI dependency that checks the authenticated user has one of the allowed roles.

    Usage::

        @router.delete("/teams/{team_id}", dependencies=[require_role("owner")])
        async def delete_team(...): ...

    The role is read from ``request.state.team_role`` which is set by
    :class:`~ai_driven.auth.middleware.APIKeyMiddleware`.  If the role is
    missing it defaults to ``"member"`` (least-privilege fallback).
    """
    # Validate at import/call time, not at request time
    invalid = set(allowed_roles) - _VALID_ROLES
    if invalid:
        raise ValueError(f"Invalid role(s) passed to require_role: {invalid}")

    async def _check_role(request: Request) -> None:
        role = getattr(request.state, "team_role", "member")
        if role not in allowed_roles:
            raise HTTPException(
                status_code=403,
                detail="Insufficient permissions",
            )

    return Depends(_check_role)


def get_team_id(request: Request) -> str:
    """Extract team_id from request state (set by APIKeyMiddleware)."""
    team_id = getattr(request.state, "team_id", None)
    if not team_id:
        raise HTTPException(status_code=401, detail="Not authenticated")
    return team_id


async def require_task_access(
    task_id: str, team_id: str = Depends(get_team_id)
) -> Task:
    """Verify the caller's team owns the task. Returns 404 (not 403) to avoid leaking existence."""
    async with async_session_factory() as session:
        repo = TaskRepository(session)
        task = await repo.get_by_id(uuid.UUID(task_id))
        if task is None or str(task.team_id) != team_id:
            raise HTTPException(status_code=404, detail="Task not found")
        return task


# ── Service dependencies ─────────────────────────────────────────────────────


@lru_cache
def get_claude_client() -> ClaudeClient:
    provider = create_provider()
    return ClaudeClient(provider=provider)


def get_jira_client() -> JiraClient:
    return JiraClient()


def get_github_client() -> GitHubClient:
    settings = get_settings()
    return GitHubClient(token=settings.github_app_private_key)


def get_bitbucket_client() -> BitbucketClient:
    settings = get_settings()
    return BitbucketClient(
        username=settings.bitbucket_client_id,
        app_password=settings.bitbucket_client_secret,
    )
