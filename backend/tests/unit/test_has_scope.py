"""Unit tests for ai_driven.scm.oauth_helpers.has_scope.

Covers the four cases in the brief's helper examples.
"""

from __future__ import annotations

import pytest

from ai_driven.scm.oauth_helpers import ALLOWED_SCOPES, RefreshOutcome, has_scope


def test_repo_directly_granted():
    assert has_scope("repo read:user", "repo") is True


def test_repo_implies_public_repo():
    assert has_scope("repo read:user", "public_repo") is True


def test_public_repo_does_not_imply_repo():
    assert has_scope("public_repo read:user", "repo") is False


def test_empty_stored_is_never_granted():
    assert has_scope("", "public_repo") is False


def test_unrelated_scope_not_granted():
    assert has_scope("read:user admin:org", "repo") is False


@pytest.mark.parametrize("scope", ["public_repo", "repo"])
def test_allowed_scopes_membership(scope):
    assert scope in ALLOWED_SCOPES


def test_refresh_outcome_values_stable_for_pr3():
    # PR 3 switches on these; typos would break the review-action flow
    # silently. Pin the wire values.
    assert RefreshOutcome.REFRESHED.value == "refreshed"
    assert RefreshOutcome.NO_REFRESH_SUPPORTED.value == "no_refresh_supported"
    assert RefreshOutcome.REFRESH_EXPIRED.value == "refresh_expired"
    assert RefreshOutcome.REFRESH_REVOKED.value == "refresh_revoked"
