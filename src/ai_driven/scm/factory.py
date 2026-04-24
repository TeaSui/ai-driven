"""Factory function for creating SCM clients."""

from __future__ import annotations

from typing import Any

from ai_driven.scm.bitbucket_client import BitbucketClient
from ai_driven.scm.exceptions import SCMError
from ai_driven.scm.github_client import GitHubClient
from ai_driven.scm.models import SCMType


def get_scm_client_from_settings(
    scm_type: str | SCMType,
) -> GitHubClient | BitbucketClient:
    """Build an SCM client using credentials from runtime settings.

    Used by request-path helpers (admin endpoints, webhook re-trigger, etc.)
    that don't have explicit credentials on hand but need to talk to the
    provider the task's repo is registered under.
    """
    from ai_driven.config import get_settings

    settings = get_settings()
    if isinstance(scm_type, str):
        try:
            scm_type = SCMType(scm_type.lower())
        except ValueError:
            raise SCMError(f"Unsupported SCM type: {scm_type}")

    if scm_type == SCMType.GITHUB:
        if not settings.github_token:
            raise SCMError("GitHub token not configured (AI_DRIVEN_GITHUB_TOKEN)")
        return GitHubClient(token=settings.github_token)

    if scm_type == SCMType.BITBUCKET:
        if not (settings.bitbucket_username and settings.bitbucket_app_password):
            raise SCMError(
                "Bitbucket credentials not configured "
                "(AI_DRIVEN_BITBUCKET_USERNAME + AI_DRIVEN_BITBUCKET_APP_PASSWORD)"
            )
        return BitbucketClient(
            username=settings.bitbucket_username,
            app_password=settings.bitbucket_app_password,
        )

    raise SCMError(f"Unsupported SCM type: {scm_type}")


def detect_scm_from_repo_name(repo_full_name: str) -> str:
    """Heuristic: pick a provider from just a repo identifier.

    Called when we have no Repository row (e.g. ad-hoc task with only a
    `repo_full_name` string). GitHub repos are `owner/repo`, Bitbucket Cloud
    repos are `workspace/repo` with the same shape — so this is always a
    best-effort guess. Callers that need certainty should pass the
    `Repository.scm_type` field from Postgres.
    """
    return "github"


def get_scm_client(
    scm_type: str | SCMType, credentials: dict[str, Any]
) -> GitHubClient | BitbucketClient:
    """Return the appropriate SCM client for the given type and credentials.

    Parameters
    ----------
    scm_type:
        ``"github"`` or ``"bitbucket"`` (or the corresponding ``SCMType`` enum).
    credentials:
        For GitHub: ``{"token": "..."}``
        For Bitbucket: ``{"username": "...", "app_password": "..."}``

    Raises
    ------
    SCMError
        If the SCM type is not supported or required credentials are missing.
    """
    if isinstance(scm_type, str):
        try:
            scm_type = SCMType(scm_type.lower())
        except ValueError:
            raise SCMError(f"Unsupported SCM type: {scm_type}")

    if scm_type == SCMType.GITHUB:
        token = credentials.get("token")
        if not token:
            raise SCMError("GitHub credentials require a 'token' field")
        return GitHubClient(token=token)

    if scm_type == SCMType.BITBUCKET:
        username = credentials.get("username")
        app_password = credentials.get("app_password")
        if not username or not app_password:
            raise SCMError(
                "Bitbucket credentials require 'username' and 'app_password' fields"
            )
        return BitbucketClient(username=username, app_password=app_password)

    raise SCMError(f"Unsupported SCM type: {scm_type}")
