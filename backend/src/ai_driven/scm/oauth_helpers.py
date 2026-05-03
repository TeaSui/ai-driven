"""Small helpers shared by the OAuth authorize/callback endpoints and
the PR 3 review-action refactor.

The goal is a single source of truth for scope allowlist + scope
superset logic, so both PR 1 (authorize input validation) and PR 3
(review-action precondition check) can't drift.
"""

from __future__ import annotations

from enum import Enum

# Allowed values of the ``scope`` query param on /authorize. The provider
# will reject anything unknown anyway, but validating up-front gives a
# 400 with a useful error instead of a 502.
#
# GitHub scope semantics:
#   - public_repo: PR comments / approvals on PUBLIC repos only
#   - repo: full read+write on private + public repos (required for
#     approving private-repo PRs)
#
# Bitbucket uses different scope names (``pullrequest:write`` etc.);
# PR 1's ALLOWED_SCOPES is the GitHub set only. Bitbucket's scope
# mapping is handled inside the provider-specific authorize handler.
ALLOWED_SCOPES: frozenset[str] = frozenset({"public_repo", "repo"})


def has_scope(stored: str, needed: str) -> bool:
    """Return True if the stored scope string grants ``needed``.

    ``stored`` is the space-separated scope string returned by the
    provider on token exchange (``"repo read:user"`` style).

    GitHub scope supersets: ``repo`` implies ``public_repo``. Other
    pairs are exact-match.

    Examples:
        has_scope("repo read:user", "repo") -> True
        has_scope("repo read:user", "public_repo") -> True   # superset
        has_scope("public_repo read:user", "repo") -> False
        has_scope("", "public_repo") -> False
    """
    if not stored:
        return False
    granted = set(stored.split())
    if needed in granted:
        return True
    if needed == "public_repo" and "repo" in granted:
        return True
    return False


class RefreshOutcome(str, Enum):
    """Result of a refresh-token exchange.

    Enum (not a bare string) so PR 3 can switch on it without typo
    risk. Imported but not used in PR 1 — defined here so PR 3 can
    rely on this module as a stable contract.
    """

    REFRESHED = "refreshed"
    NO_REFRESH_SUPPORTED = "no_refresh_supported"
    REFRESH_EXPIRED = "refresh_expired"
    REFRESH_REVOKED = "refresh_revoked"
