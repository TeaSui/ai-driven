"""Tests for BitbucketClient.create_or_update_files_batch.

Bitbucket's ``POST /repositories/{repo}/src`` accepts multiple file parts in
one multipart POST and produces exactly one commit. These tests verify the
batch method sends all file parts in a single request with the correct
message/branch fields.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock

import httpx
import pytest

from ai_driven.scm.bitbucket_client import BitbucketClient


def _make_response(
    status: int = 201,
    json_data: Any = None,
    text: str | None = None,
    url: str = "https://api.bitbucket.org/2.0/test",
) -> httpx.Response:
    kwargs: dict[str, Any] = {
        "status_code": status,
        "request": httpx.Request("POST", url),
    }
    if json_data is not None:
        kwargs["json"] = json_data
    else:
        kwargs["text"] = text or ""
    return httpx.Response(**kwargs)


@pytest.fixture
def client() -> BitbucketClient:
    c = BitbucketClient.__new__(BitbucketClient)
    c._http = httpx.AsyncClient(  # type: ignore[attr-defined]
        base_url="https://api.bitbucket.org/2.0",
        auth=("u", "p"),
    )
    return c


class TestBitbucketBatchWrite:
    async def test_single_post_with_all_parts(
        self, client: BitbucketClient
    ) -> None:
        captured: dict[str, Any] = {}

        async def fake_request(
            method: str,
            url: str,
            *,
            data: dict[str, Any] | None = None,
            files: dict[str, Any] | None = None,
            **_: Any,
        ) -> httpx.Response:
            captured["method"] = method
            captured["url"] = url
            captured["data"] = data
            captured["files"] = files
            return _make_response(201, json_data={"hash": "abcd1234"})

        client._request = AsyncMock(side_effect=fake_request)  # type: ignore[method-assign]

        result = await client.create_or_update_files_batch(
            "team/repo",
            [
                {"path": "a.py", "content": "print(1)\n"},
                {"path": "docs/b.md", "content": "hello\n"},
                {"path": "tests/c.py", "content": "assert True\n"},
            ],
            "feat: three files",
            branch="feat/x",
        )

        # ONE request, to /src
        assert client._request.call_count == 1
        assert captured["method"] == "POST"
        assert captured["url"] == "/repositories/team/repo/src"

        # message + branch in form data
        assert captured["data"]["message"] == "feat: three files"
        assert captured["data"]["branch"] == "feat/x"

        # all three paths as multipart parts
        assert set(captured["files"].keys()) == {"a.py", "docs/b.md", "tests/c.py"}
        # each part is ("file", bytes)
        for name, part in captured["files"].items():
            assert part[0] == "file"
            assert isinstance(part[1], (bytes, bytearray))

        assert result == {"hash": "abcd1234"}

    async def test_dedupe_last_wins(self, client: BitbucketClient) -> None:
        captured: dict[str, Any] = {}

        async def fake_request(
            method: str,
            url: str,
            *,
            data: dict[str, Any] | None = None,
            files: dict[str, Any] | None = None,
            **_: Any,
        ) -> httpx.Response:
            captured["files"] = files
            return _make_response(201, json_data={"hash": "x"})

        client._request = AsyncMock(side_effect=fake_request)  # type: ignore[method-assign]

        await client.create_or_update_files_batch(
            "t/r",
            [
                {"path": "a.py", "content": "FIRST"},
                {"path": "a.py", "content": "SECOND"},
            ],
            "msg",
            branch="main",
        )
        # dedup keeps only the later content
        assert list(captured["files"].keys()) == ["a.py"]
        assert captured["files"]["a.py"][1] == b"SECOND"

    async def test_empty_files_is_noop(self, client: BitbucketClient) -> None:
        client._request = AsyncMock()  # type: ignore[method-assign]
        result = await client.create_or_update_files_batch(
            "t/r", [], "nothing", branch="main"
        )
        assert result["files"] == []
        client._request.assert_not_called()

    async def test_falls_back_to_echo_on_empty_body(
        self, client: BitbucketClient
    ) -> None:
        async def fake_request(*_a: Any, **_kw: Any) -> httpx.Response:
            # Bitbucket sometimes returns 201 with empty body on successful src POST.
            return _make_response(201, text="")

        client._request = AsyncMock(side_effect=fake_request)  # type: ignore[method-assign]

        result = await client.create_or_update_files_batch(
            "t/r",
            [{"path": "a", "content": "c"}],
            "m",
            branch="main",
        )
        assert result["message"] == "m"
        assert result["files"] == ["a"]
