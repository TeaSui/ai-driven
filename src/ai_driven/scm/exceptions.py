"""Shared SCM exception classes for GitHub and Bitbucket clients."""

from __future__ import annotations


class SCMError(Exception):
    """Base exception for all SCM operations."""

    pass


class SCMNotFoundError(SCMError):
    """Resource not found (HTTP 404)."""

    pass


class SCMRateLimitError(SCMError):
    """Rate limit exceeded (HTTP 429 or GitHub 403 with rate-limit headers)."""

    def __init__(self, retry_after: int = 60) -> None:
        self.retry_after = retry_after
        super().__init__(f"Rate limited. Retry after {retry_after}s")


class SCMValidationError(SCMError):
    """Validation error from the SCM API (HTTP 422)."""

    pass


class SCMAuthError(SCMError):
    """Authentication or authorization failure (HTTP 401/403)."""

    pass
