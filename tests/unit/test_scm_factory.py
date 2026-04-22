"""Tests for the SCM client factory."""

from __future__ import annotations

import pytest

from ai_driven.scm.bitbucket_client import BitbucketClient
from ai_driven.scm.exceptions import SCMError
from ai_driven.scm.factory import get_scm_client
from ai_driven.scm.github_client import GitHubClient
from ai_driven.scm.models import SCMType


class TestGetSCMClient:
    def test_github_from_string(self) -> None:
        client = get_scm_client("github", {"token": "gh-tok"})
        assert isinstance(client, GitHubClient)

    def test_github_from_enum(self) -> None:
        client = get_scm_client(SCMType.GITHUB, {"token": "gh-tok"})
        assert isinstance(client, GitHubClient)

    def test_bitbucket_from_string(self) -> None:
        client = get_scm_client(
            "bitbucket", {"username": "user", "app_password": "pass"}
        )
        assert isinstance(client, BitbucketClient)

    def test_bitbucket_from_enum(self) -> None:
        client = get_scm_client(
            SCMType.BITBUCKET, {"username": "user", "app_password": "pass"}
        )
        assert isinstance(client, BitbucketClient)

    def test_case_insensitive(self) -> None:
        client = get_scm_client("GitHub", {"token": "tok"})
        assert isinstance(client, GitHubClient)

    def test_unsupported_type_raises(self) -> None:
        with pytest.raises(SCMError, match="Unsupported SCM type"):
            get_scm_client("gitlab", {"token": "tok"})

    def test_github_missing_token_raises(self) -> None:
        with pytest.raises(SCMError, match="token"):
            get_scm_client("github", {})

    def test_bitbucket_missing_credentials_raises(self) -> None:
        with pytest.raises(SCMError, match="username"):
            get_scm_client("bitbucket", {"username": "user"})

    def test_bitbucket_missing_username_raises(self) -> None:
        with pytest.raises(SCMError, match="username"):
            get_scm_client("bitbucket", {"app_password": "pass"})
