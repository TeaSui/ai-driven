"""Tests for the hardened GitHub client."""

from __future__ import annotations

import base64
import json
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest

from ai_driven.scm.exceptions import (
    SCMAuthError,
    SCMNotFoundError,
    SCMRateLimitError,
    SCMValidationError,
)
from ai_driven.scm.github_client import (
    GitHubClient,
    _check_rate_limit,
    _raise_for_status,
)
from ai_driven.scm.models import PRState


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_response(
    status: int = 200,
    json_data: Any = None,
    headers: dict[str, str] | None = None,
    url: str = "https://api.github.com/test",
    text: str | None = None,
) -> httpx.Response:
    """Build a fake httpx.Response."""
    kwargs: dict[str, Any] = {
        "status_code": status,
        "headers": headers or {},
        "request": httpx.Request("GET", url),
    }
    if json_data is not None:
        kwargs["json"] = json_data
    elif text is not None:
        kwargs["text"] = text
    else:
        kwargs["text"] = ""
    return httpx.Response(**kwargs)


# ---------------------------------------------------------------------------
# _raise_for_status
# ---------------------------------------------------------------------------

class TestRaiseForStatus:
    def test_200_no_error(self) -> None:
        resp = _make_response(200, json_data={})
        _raise_for_status(resp)  # should not raise

    def test_404_raises_not_found(self) -> None:
        resp = _make_response(404)
        with pytest.raises(SCMNotFoundError):
            _raise_for_status(resp)

    def test_422_raises_validation_error(self) -> None:
        resp = _make_response(422, json_data={"message": "bad"})
        with pytest.raises(SCMValidationError, match="Validation error"):
            _raise_for_status(resp)

    def test_401_raises_auth_error(self) -> None:
        resp = _make_response(401)
        with pytest.raises(SCMAuthError, match="Authentication failed"):
            _raise_for_status(resp)

    def test_403_raises_auth_error(self) -> None:
        resp = _make_response(403)
        with pytest.raises(SCMAuthError, match="Forbidden"):
            _raise_for_status(resp)

    def test_429_raises_rate_limit(self) -> None:
        resp = _make_response(429, headers={"Retry-After": "30"})
        with pytest.raises(SCMRateLimitError) as exc_info:
            _raise_for_status(resp)
        assert exc_info.value.retry_after == 30

    def test_403_with_rate_limit_zero_raises_rate_limit(self) -> None:
        resp = _make_response(403, headers={"X-RateLimit-Remaining": "0"})
        with pytest.raises(SCMRateLimitError):
            _raise_for_status(resp)

    def test_500_raises_httpx_error(self) -> None:
        resp = _make_response(500)
        with pytest.raises(httpx.HTTPStatusError):
            _raise_for_status(resp)


# ---------------------------------------------------------------------------
# _check_rate_limit
# ---------------------------------------------------------------------------

class TestCheckRateLimit:
    def test_logs_warning_when_low(self, caplog: pytest.LogCaptureFixture) -> None:
        resp = _make_response(
            200,
            headers={"X-RateLimit-Remaining": "50", "X-RateLimit-Reset": "9999"},
        )
        import logging

        with caplog.at_level(logging.WARNING, logger="ai_driven.scm.github_client"):
            _check_rate_limit(resp)
        assert "rate limit low" in caplog.text.lower()

    def test_no_warning_when_high(self, caplog: pytest.LogCaptureFixture) -> None:
        resp = _make_response(
            200, headers={"X-RateLimit-Remaining": "4999"}
        )
        import logging

        with caplog.at_level(logging.WARNING, logger="ai_driven.scm.github_client"):
            _check_rate_limit(resp)
        assert caplog.text == ""


# ---------------------------------------------------------------------------
# GitHubClient methods (mocked HTTP)
# ---------------------------------------------------------------------------

@pytest.fixture
def client() -> GitHubClient:
    return GitHubClient(token="test-token")


class TestListFiles:
    async def test_list_files_single_page(self, client: GitHubClient) -> None:
        items = [
            {"path": "src/main.py", "name": "main.py", "type": "file", "size": 100},
            {"path": "src/lib", "name": "lib", "type": "dir", "size": 0},
        ]
        mock_resp = _make_response(200, json_data=items)
        client._request = AsyncMock(return_value=mock_resp)  # type: ignore[method-assign]

        entries = await client.list_files("owner/repo")
        assert len(entries) == 2
        assert entries[0].path == "src/main.py"
        assert entries[1].is_directory is True

    async def test_list_files_pagination(self, client: GitHubClient) -> None:
        page1 = _make_response(
            200,
            json_data=[{"path": "a.py", "name": "a.py", "type": "file", "size": 10}],
            headers={"Link": '<https://api.github.com/next>; rel="next"'},
        )
        page2 = _make_response(
            200,
            json_data=[{"path": "b.py", "name": "b.py", "type": "file", "size": 20}],
        )
        client._request = AsyncMock(side_effect=[page1, page2])  # type: ignore[method-assign]

        entries = await client.list_files("owner/repo")
        assert len(entries) == 2
        assert entries[0].path == "a.py"
        assert entries[1].path == "b.py"


class TestReadFile:
    async def test_read_file_base64(self, client: GitHubClient) -> None:
        encoded = base64.b64encode(b"hello world").decode()
        mock_resp = _make_response(
            200,
            json_data={
                "path": "test.py",
                "content": encoded,
                "encoding": "base64",
                "size": 11,
            },
        )
        client._request = AsyncMock(return_value=mock_resp)  # type: ignore[method-assign]

        fc = await client.read_file("owner/repo", "test.py")
        assert fc.content == "hello world"
        assert fc.path == "test.py"


class TestGetPullRequest:
    async def test_get_pr(self, client: GitHubClient) -> None:
        pr_data = {
            "number": 42,
            "title": "feat: add thing",
            "body": "description",
            "state": "open",
            "merged": False,
            "head": {"ref": "feature"},
            "base": {"ref": "main"},
            "user": {"login": "dev"},
            "html_url": "https://github.com/owner/repo/pull/42",
            "created_at": "2025-01-01T00:00:00Z",
            "updated_at": "2025-01-02T00:00:00Z",
        }
        client._request = AsyncMock(return_value=_make_response(200, json_data=pr_data))  # type: ignore[method-assign]

        pr = await client.get_pull_request("owner/repo", 42)
        assert pr.number == 42
        assert pr.state == PRState.OPEN
        assert pr.source_branch == "feature"


class TestListPullRequests:
    async def test_list_prs(self, client: GitHubClient) -> None:
        pr_data = {
            "number": 1,
            "title": "PR 1",
            "body": "",
            "state": "open",
            "merged": False,
            "head": {"ref": "f1"},
            "base": {"ref": "main"},
            "user": {"login": "dev"},
            "html_url": "https://github.com/o/r/pull/1",
            "created_at": "2025-01-01T00:00:00Z",
            "updated_at": "2025-01-02T00:00:00Z",
        }
        client._request = AsyncMock(return_value=_make_response(200, json_data=[pr_data]))  # type: ignore[method-assign]

        prs = await client.list_pull_requests("owner/repo", state="open")
        assert len(prs) == 1
        assert prs[0].number == 1


class TestCreateOrUpdateFile:
    async def test_create_file(self, client: GitHubClient) -> None:
        resp_data = {"content": {"path": "new.py", "sha": "abc123"}}
        client._request = AsyncMock(return_value=_make_response(201, json_data=resp_data))  # type: ignore[method-assign]

        result = await client.create_or_update_file(
            "owner/repo", "new.py", "print('hi')", "add new.py"
        )
        assert result["content"]["path"] == "new.py"

        # Verify the base64 encoding was applied
        call_kwargs = client._request.call_args
        body = call_kwargs.kwargs["json"]
        decoded = base64.b64decode(body["content"]).decode()
        assert decoded == "print('hi')"

    async def test_update_file_includes_sha(self, client: GitHubClient) -> None:
        client._request = AsyncMock(return_value=_make_response(200, json_data={}))  # type: ignore[method-assign]

        await client.create_or_update_file(
            "owner/repo", "old.py", "updated", "update old.py", sha="old-sha"
        )
        body = client._request.call_args.kwargs["json"]
        assert body["sha"] == "old-sha"


class TestFromApp:
    async def test_from_app_exchanges_jwt_for_token(self) -> None:
        with (
            patch("ai_driven.scm.github_client.jwt.encode", return_value="fake-jwt"),
            patch("ai_driven.scm.github_client.httpx.AsyncClient") as mock_cls,
        ):
            mock_client = AsyncMock()
            mock_cls.return_value = mock_client

            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)

            token_resp = _make_response(201, json_data={"token": "installation-token"})
            mock_client.post = AsyncMock(return_value=token_resp)

            gh = await GitHubClient.from_app(
                app_id=12345,
                private_key="fake-private-key",
                installation_id=67890,
            )
            assert isinstance(gh, GitHubClient)


class TestNextLink:
    def test_extracts_next_url(self) -> None:
        resp = _make_response(
            200,
            headers={
                "Link": '<https://api.github.com/repos?page=2>; rel="next", '
                '<https://api.github.com/repos?page=5>; rel="last"'
            },
        )
        assert GitHubClient._next_link(resp) == "https://api.github.com/repos?page=2"

    def test_returns_none_when_no_next(self) -> None:
        resp = _make_response(200)
        assert GitHubClient._next_link(resp) is None
