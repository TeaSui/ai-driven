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
    """Validation error from the SCM API (HTTP 422).

    Carries an optional structured ``detail`` dict populated from the API's
    JSON error body when available. Callers recovering from the "pull request
    already exists" case should inspect ``.detail`` rather than string-matching
    ``str(exc)`` — provider error messages shift over time and can be
    localized, but the structured body is stable.

    ``detail`` is ``None`` when the response body was not parseable JSON (e.g.
    a text error from a proxy in front of the provider), in which case the
    stringified body is preserved in the exception ``args``/``message`` as
    before.
    """

    def __init__(self, message: str, detail: dict | None = None) -> None:
        super().__init__(message)
        self.detail = detail


class SCMAuthError(SCMError):
    """Authentication or authorization failure (HTTP 401/403)."""

    pass
