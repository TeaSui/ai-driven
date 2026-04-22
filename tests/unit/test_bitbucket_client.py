"""Tests for the hardened Bitbucket client."""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, patch

import httpx
import pytest

from ai_driven.scm.bitbucket_client import BitbucketClient, _raise_for_status
from ai_driven.scm.exceptions import (
    SCMAuthError,
    SCMNotFoundError,
    SCMRateLimitError,
    SCMValidationError,
)
from ai_driven.scm.models import PRState


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_response(
    status: int = 200,
    json_data: Any = None,
    headers: dict[str, str] | None = None,
    url: str = "https://api.bitbucket.org/2.0/test",
    text: str | None = None,
) -> httpx.Response:
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


class TestBitbucketRaiseForStatus:
    def test_200_no_error(self) -> None:
        _raise_for_status(_make_response(200))

    def test_404_raises_not_found(self) -> None:
        with pytest.raises(SCMNotFoundError):
            _raise_for_status(_make_response(404))

    def test_422_raises_validation(self) -> None:
        with pytest.raises(SCMValidationError):
            _raise_for_status(_make_response(422, json_data={"error": "bad"}))

    def test_429_raises_rate_limit(self) -> None:
        with pytest.raises(SCMRateLimitError) as exc_info:
            _raise_for_status(_make_response(429, headers={"Retry-After": "45"}))
        assert exc_info.value.retry_after == 45

    def test_401_raises_auth(self) -> None:
        with pytest.raises(SCMAuthError):
            _raise_for_status(_make_response(401))

    def test_403_raises_auth(self) -> None:
        with pytest.raises(SCMAuthError):
            _raise_for_status(_make_response(403))

    def test_500_raises_http_error(self) -> None:
        with pytest.raises(httpx.HTTPStatusError):
            _raise_for_status(_make_response(500))


# ---------------------------------------------------------------------------
# BitbucketClient methods (mocked HTTP)
# ---------------------------------------------------------------------------


@pytest.fixture
def client() -> BitbucketClient:
    return BitbucketClient(username="user", app_password="pass")


class TestListFiles:
    async def test_single_page(self, client: BitbucketClient) -> None:
        data = {
            "values": [
                {"path": "src/main.py", "type": "commit_file", "size": 100},
                {"path": "src/lib", "type": "commit_directory", "size": 0},
            ]
        }
        client._request = AsyncMock(return_value=_make_response(200, json_data=data))  # type: ignore[method-assign]

        entries = await client.list_files("owner/repo")
        assert len(entries) == 2
        assert entries[0].name == "main.py"
        assert entries[1].is_directory is True

    async def test_pagination(self, client: BitbucketClient) -> None:
        page1_data = {
            "values": [{"path": "a.py", "type": "commit_file", "size": 10}],
            "next": "https://api.bitbucket.org/2.0/next-page",
        }
        page2_data = {
            "values": [{"path": "b.py", "type": "commit_file", "size": 20}],
        }

        page1 = _make_response(200, json_data=page1_data)
        page2 = _make_response(200, json_data=page2_data)
        client._request = AsyncMock(side_effect=[page1, page2])  # type: ignore[method-assign]

        entries = await client.list_files("owner/repo")
        assert len(entries) == 2
        assert entries[0].path == "a.py"
        assert entries[1].path == "b.py"


class TestGetPullRequest:
    async def test_get_pr(self, client: BitbucketClient) -> None:
        pr_data = {
            "id": 10,
            "title": "feat: thing",
            "description": "desc",
            "state": "OPEN",
            "source": {"branch": {"name": "feature"}},
            "destination": {"branch": {"name": "main"}},
            "author": {"display_name": "dev"},
            "links": {"html": {"href": "https://bitbucket.org/owner/repo/pull/10"}},
        }
        client._request = AsyncMock(return_value=_make_response(200, json_data=pr_data))  # type: ignore[method-assign]

        pr = await client.get_pull_request("owner/repo", 10)
        assert pr.number == 10
        assert pr.state == PRState.OPEN


class TestListPullRequests:
    async def test_list_prs(self, client: BitbucketClient) -> None:
        pr_data = {
            "id": 1,
            "title": "PR 1",
            "description": "",
            "state": "OPEN",
            "source": {"branch": {"name": "f1"}},
            "destination": {"branch": {"name": "main"}},
            "author": {"display_name": "dev"},
            "links": {"html": {"href": "https://bitbucket.org/o/r/pull/1"}},
        }
        resp_data = {"values": [pr_data]}
        client._request = AsyncMock(return_value=_make_response(200, json_data=resp_data))  # type: ignore[method-assign]

        prs = await client.list_pull_requests("owner/repo")
        assert len(prs) == 1
        assert prs[0].number == 1


class TestCreateOrUpdateFile:
    async def test_create_file(self, client: BitbucketClient) -> None:
        client._request = AsyncMock(return_value=_make_response(200, text=""))  # type: ignore[method-assign]

        result = await client.create_or_update_file(
            "owner/repo", "new.py", "content", "add file"
        )
        assert isinstance(result, dict)

        call_kwargs = client._request.call_args
        assert call_kwargs.kwargs["data"]["message"] == "add file"
        assert call_kwargs.kwargs["data"]["branch"] == "main"


class TestFromOAuth:
    async def test_from_oauth_exchanges_credentials(self) -> None:
        with patch("ai_driven.scm.bitbucket_client.httpx.AsyncClient") as mock_cls:
            mock_client = AsyncMock()
            mock_cls.return_value = mock_client

            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=False)

            token_resp = _make_response(
                200, json_data={"access_token": "oauth-token"}
            )
            mock_client.post = AsyncMock(return_value=token_resp)

            bb = await BitbucketClient.from_oauth(
                client_id="cid", client_secret="csecret"
            )
            assert isinstance(bb, BitbucketClient)
