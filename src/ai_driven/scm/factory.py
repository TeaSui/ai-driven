"""Factory function for creating SCM clients."""

from __future__ import annotations

from typing import Any

from ai_driven.scm.bitbucket_client import BitbucketClient
from ai_driven.scm.exceptions import SCMError
from ai_driven.scm.github_client import GitHubClient
from ai_driven.scm.models import SCMType


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
