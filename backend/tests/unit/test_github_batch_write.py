"""Tests for GitHubClient.create_or_update_files_batch (one-commit N-file writes).

Verifies the 5-step Git Data API sequence:
  1. GET refs/heads/{branch}     -> parent commit SHA
  2. GET commits/{parent_sha}    -> parent tree SHA
  3. POST git/blobs (x N)        -> blob SHAs   (run in parallel)
  4. POST git/trees              -> new tree SHA
  5. POST git/commits            -> new commit SHA
  6. PATCH refs/heads/{branch}   -> move ref (force=false)
"""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import AsyncMock

import httpx
import pytest

from ai_driven.scm.github_client import GitHubClient


def _make_response(
    status: int = 200,
    json_data: Any = None,
    url: str = "https://api.github.com/test",
) -> httpx.Response:
    return httpx.Response(
        status_code=status,
        request=httpx.Request("GET", url),
        json=json_data if json_data is not None else {},
    )


@pytest.fixture
def client() -> GitHubClient:
    return GitHubClient(token="test-token")


class TestBatchWriteSequence:
    async def test_full_sequence_fires_in_order(self, client: GitHubClient) -> None:
        """Every step of the Git Data API protocol should be called in order."""
        calls: list[tuple[str, str]] = []

        async def fake_request(method: str, url: str, **_: Any) -> httpx.Response:
            calls.append((method, url))
            if method == "GET" and "/git/ref/heads/" in url:
                return _make_response(200, {"object": {"sha": "PARENT_SHA"}})
            if method == "GET" and "/git/commits/" in url:
                return _make_response(200, {"tree": {"sha": "PARENT_TREE"}})
            if method == "POST" and url.endswith("/git/blobs"):
                return _make_response(201, {"sha": f"BLOB_{len(calls)}"})
            if method == "POST" and url.endswith("/git/trees"):
                return _make_response(201, {"sha": "NEW_TREE"})
            if method == "POST" and url.endswith("/git/commits"):
                return _make_response(201, {"sha": "NEW_COMMIT"})
            if method == "PATCH" and "/git/refs/heads/" in url:
                return _make_response(200, {"ref": "refs/heads/feat/x"})
            raise AssertionError(f"unexpected call: {method} {url}")

        client._request = AsyncMock(side_effect=fake_request)  # type: ignore[method-assign]

        result = await client.create_or_update_files_batch(
            "owner/repo",
            [
                {"path": "a.py", "content": "print(1)\n"},
                {"path": "b.py", "content": "print(2)\n"},
                {"path": "docs/c.md", "content": "# hi\n"},
            ],
            "feat: ship three files in one commit",
            branch="feat/x",
        )

        # Verify the high-level sequence.
        methods = [m for (m, _) in calls]
        urls = [u for (_, u) in calls]
        assert methods[0] == "GET" and "/git/ref/heads/feat/x" in urls[0]
        assert methods[1] == "GET" and "/git/commits/PARENT_SHA" in urls[1]
        # Blob creates come next (3 of them), then tree, then commit, then PATCH.
        blob_calls = [i for i, (m, u) in enumerate(calls)
                      if m == "POST" and u.endswith("/git/blobs")]
        tree_idx = next(i for i, (m, u) in enumerate(calls)
                        if m == "POST" and u.endswith("/git/trees"))
        commit_idx = next(i for i, (m, u) in enumerate(calls)
                          if m == "POST" and u.endswith("/git/commits"))
        patch_idx = next(i for i, (m, u) in enumerate(calls)
                         if m == "PATCH" and "/git/refs/heads/" in u)
        assert len(blob_calls) == 3
        assert all(b < tree_idx for b in blob_calls)
        assert tree_idx < commit_idx < patch_idx

        # Exactly one PATCH at the end.
        patches = [c for c in calls if c[0] == "PATCH"]
        assert len(patches) == 1

        # Return payload shape.
        assert result["sha"] == "NEW_COMMIT"
        assert result["files"] == ["a.py", "b.py", "docs/c.md"]
        assert result["message"] == "feat: ship three files in one commit"

    async def test_patch_uses_force_false(self, client: GitHubClient) -> None:
        """Concurrent pushes must not be clobbered — force=false."""
        patch_body: dict[str, Any] = {}

        async def fake_request(
            method: str, url: str, *, json: dict[str, Any] | None = None, **_: Any
        ) -> httpx.Response:
            if method == "GET" and "/git/ref/heads/" in url:
                return _make_response(200, {"object": {"sha": "PARENT"}})
            if method == "GET" and "/git/commits/" in url:
                return _make_response(200, {"tree": {"sha": "TREE"}})
            if method == "POST" and url.endswith("/git/blobs"):
                return _make_response(201, {"sha": "BLOB"})
            if method == "POST" and url.endswith("/git/trees"):
                return _make_response(201, {"sha": "NEWT"})
            if method == "POST" and url.endswith("/git/commits"):
                return _make_response(201, {"sha": "NEWC"})
            if method == "PATCH":
                patch_body.update(json or {})
                return _make_response(200, {})
            raise AssertionError(url)

        client._request = AsyncMock(side_effect=fake_request)  # type: ignore[method-assign]

        await client.create_or_update_files_batch(
            "o/r", [{"path": "x", "content": "y"}], "msg", branch="main"
        )
        assert patch_body.get("sha") == "NEWC"
        assert patch_body.get("force") is False

    async def test_blobs_are_parallelized(self, client: GitHubClient) -> None:
        """All blob creations should be in-flight together (asyncio.gather),
        not one-after-another. Signal this by holding each blob call on an
        event that only releases after we've seen N concurrent in-flight calls.
        """
        n_files = 4
        in_flight = 0
        max_in_flight = 0
        start_gate = asyncio.Event()

        async def fake_request(
            method: str, url: str, **_: Any
        ) -> httpx.Response:
            nonlocal in_flight, max_in_flight
            if method == "GET" and "/git/ref/heads/" in url:
                return _make_response(200, {"object": {"sha": "P"}})
            if method == "GET" and "/git/commits/" in url:
                return _make_response(200, {"tree": {"sha": "T"}})
            if method == "POST" and url.endswith("/git/blobs"):
                in_flight += 1
                max_in_flight = max(max_in_flight, in_flight)
                if in_flight >= n_files:
                    start_gate.set()
                # Wait until every blob call has entered before releasing any.
                # Serialized code would deadlock here; gather'd code releases.
                await asyncio.wait_for(start_gate.wait(), timeout=1.0)
                in_flight -= 1
                return _make_response(201, {"sha": "BLOB"})
            if method == "POST" and url.endswith("/git/trees"):
                return _make_response(201, {"sha": "NEWT"})
            if method == "POST" and url.endswith("/git/commits"):
                return _make_response(201, {"sha": "NEWC"})
            if method == "PATCH":
                return _make_response(200, {})
            raise AssertionError(url)

        client._request = AsyncMock(side_effect=fake_request)  # type: ignore[method-assign]

        await client.create_or_update_files_batch(
            "o/r",
            [{"path": f"f{i}.py", "content": f"x{i}"} for i in range(n_files)],
            "batch",
            branch="main",
        )
        assert max_in_flight == n_files

    async def test_tree_entries_use_base_tree(self, client: GitHubClient) -> None:
        """The tree POST must include base_tree = parent tree sha so unrelated
        files in the repo survive the commit.
        """
        tree_body: dict[str, Any] = {}

        async def fake_request(
            method: str, url: str, *, json: dict[str, Any] | None = None, **_: Any
        ) -> httpx.Response:
            if method == "GET" and "/git/ref/heads/" in url:
                return _make_response(200, {"object": {"sha": "PS"}})
            if method == "GET" and "/git/commits/" in url:
                return _make_response(200, {"tree": {"sha": "PARENT_TREE_SHA"}})
            if method == "POST" and url.endswith("/git/blobs"):
                return _make_response(201, {"sha": "BLOBSHA"})
            if method == "POST" and url.endswith("/git/trees"):
                tree_body.update(json or {})
                return _make_response(201, {"sha": "NT"})
            if method == "POST" and url.endswith("/git/commits"):
                return _make_response(201, {"sha": "NC"})
            if method == "PATCH":
                return _make_response(200, {})
            raise AssertionError(url)

        client._request = AsyncMock(side_effect=fake_request)  # type: ignore[method-assign]

        await client.create_or_update_files_batch(
            "o/r",
            [{"path": "a", "content": "c1"}, {"path": "b", "content": "c2"}],
            "msg",
        )
        assert tree_body["base_tree"] == "PARENT_TREE_SHA"
        entries = tree_body["tree"]
        assert [e["path"] for e in entries] == ["a", "b"]
        assert all(e["mode"] == "100644" for e in entries)
        assert all(e["type"] == "blob" for e in entries)

    async def test_dedupe_last_wins(self, client: GitHubClient) -> None:
        """Duplicate paths — later entry wins, matching N sequential writes."""
        blob_contents: list[str] = []

        async def fake_request(
            method: str, url: str, *, json: dict[str, Any] | None = None, **_: Any
        ) -> httpx.Response:
            if method == "GET" and "/git/ref/heads/" in url:
                return _make_response(200, {"object": {"sha": "P"}})
            if method == "GET" and "/git/commits/" in url:
                return _make_response(200, {"tree": {"sha": "T"}})
            if method == "POST" and url.endswith("/git/blobs"):
                blob_contents.append((json or {}).get("content", ""))
                return _make_response(201, {"sha": f"B{len(blob_contents)}"})
            if method == "POST" and url.endswith("/git/trees"):
                return _make_response(201, {"sha": "NT"})
            if method == "POST" and url.endswith("/git/commits"):
                return _make_response(201, {"sha": "NC"})
            if method == "PATCH":
                return _make_response(200, {})
            raise AssertionError(url)

        client._request = AsyncMock(side_effect=fake_request)  # type: ignore[method-assign]

        result = await client.create_or_update_files_batch(
            "o/r",
            [
                {"path": "a.py", "content": "FIRST"},
                {"path": "a.py", "content": "SECOND"},
            ],
            "msg",
        )
        # Only one blob created, with the later content.
        assert blob_contents == ["SECOND"]
        assert result["files"] == ["a.py"]

    async def test_empty_files_is_noop(self, client: GitHubClient) -> None:
        client._request = AsyncMock()  # type: ignore[method-assign]
        result = await client.create_or_update_files_batch(
            "o/r", [], "nothing", branch="main"
        )
        assert result["files"] == []
        assert result["sha"] is None
        client._request.assert_not_called()
