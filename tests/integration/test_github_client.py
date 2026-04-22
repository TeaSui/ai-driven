"""Unit tests for GitHubClient using httpx MockTransport."""

from __future__ import annotations

import base64
import json

import httpx
import pytest

from ai_driven.scm.exceptions import SCMNotFoundError
from ai_driven.scm.github_client import GitHubClient
from ai_driven.scm.models import PRState


def _json_response(data, status_code=200):
    return httpx.Response(status_code, json=data)


@pytest.fixture
def github_client_with_transport():
    """Factory that creates a GitHubClient with a custom MockTransport."""
    def _factory(handler):
        client = GitHubClient(token="test-token")
        client._http = httpx.AsyncClient(
            transport=httpx.MockTransport(handler),
            base_url="https://api.github.com",
            headers={
                "Authorization": "Bearer test-token",
                "Accept": "application/vnd.github+json",
            },
        )
        return client
    return _factory


async def test_list_files(github_client_with_transport):
    """Mock GitHub contents API response and verify list_files."""
    def handler(request: httpx.Request) -> httpx.Response:
        return _json_response([
            {"path": "src/main.py", "name": "main.py", "type": "file", "size": 1024},
            {"path": "src/utils", "name": "utils", "type": "dir", "size": 0},
            {"path": "README.md", "name": "README.md", "type": "file", "size": 256},
        ])

    client = github_client_with_transport(handler)
    entries = await client.list_files("test-org/test-repo", "src")

    assert len(entries) == 3
    assert entries[0].path == "src/main.py"
    assert entries[0].is_directory is False
    assert entries[1].name == "utils"
    assert entries[1].is_directory is True


async def test_read_file_base64(github_client_with_transport):
    """Mock file with base64 content and verify decoding."""
    file_content = "print('hello world')\n"
    encoded = base64.b64encode(file_content.encode()).decode()

    def handler(request: httpx.Request) -> httpx.Response:
        return _json_response({
            "path": "main.py",
            "name": "main.py",
            "encoding": "base64",
            "content": encoded,
            "size": len(file_content),
        })

    client = github_client_with_transport(handler)
    result = await client.read_file("test-org/test-repo", "main.py")

    assert result.path == "main.py"
    assert result.content == file_content
    assert result.encoding == "utf-8"


async def test_create_pull_request(github_client_with_transport):
    """Mock PR creation and verify returned PullRequest."""
    def handler(request: httpx.Request) -> httpx.Response:
        return _json_response({
            "number": 99,
            "title": "Add feature",
            "body": "Description here",
            "state": "open",
            "merged": False,
            "head": {"ref": "feature/x"},
            "base": {"ref": "main"},
            "user": {"login": "dev-user"},
            "html_url": "https://github.com/test-org/test-repo/pull/99",
            "created_at": "2026-04-20T10:00:00Z",
            "updated_at": "2026-04-20T10:00:00Z",
        }, status_code=201)

    client = github_client_with_transport(handler)
    pr = await client.create_pull_request(
        "test-org/test-repo", "Add feature", "Description here", "feature/x"
    )

    assert pr.number == 99
    assert pr.title == "Add feature"
    assert pr.state == PRState.OPEN
    assert pr.source_branch == "feature/x"
    assert pr.author == "dev-user"


async def test_get_pr_diff(github_client_with_transport):
    """Mock files endpoint and verify PRDiff structure."""
    def handler(request: httpx.Request) -> httpx.Response:
        return _json_response([
            {
                "filename": "src/main.py",
                "status": "modified",
                "additions": 10,
                "deletions": 3,
                "patch": "@@ -1,5 +1,12 @@\n+new line",
            },
            {
                "filename": "src/new_file.py",
                "status": "added",
                "additions": 25,
                "deletions": 0,
                "patch": "@@ -0,0 +1,25 @@\n+content",
            },
        ])

    client = github_client_with_transport(handler)
    diff = await client.get_pr_diff("test-org/test-repo", 42)

    assert len(diff.files) == 2
    assert diff.total_additions == 35
    assert diff.total_deletions == 3
    assert diff.files[0].path == "src/main.py"
    assert diff.files[1].status == "added"


async def test_post_pr_comment_inline(github_client_with_transport):
    """Mock inline comment creation."""
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        return _json_response({
            "id": 5001,
            "body": body["body"],
            "user": {"login": "ai-bot"},
            "path": body.get("path"),
            "line": body.get("line"),
        }, status_code=201)

    client = github_client_with_transport(handler)
    comment = await client.post_pr_comment(
        "test-org/test-repo", 42, "Fix this line", file_path="src/main.py", line=10
    )

    assert comment.id == "5001"
    assert comment.body == "Fix this line"
    assert comment.file_path == "src/main.py"
    assert comment.line == 10


async def test_post_pr_comment_general(github_client_with_transport):
    """Mock general PR comment (not inline)."""
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        return _json_response({
            "id": 5002,
            "body": body["body"],
            "user": {"login": "ai-bot"},
        }, status_code=201)

    client = github_client_with_transport(handler)
    comment = await client.post_pr_comment(
        "test-org/test-repo", 42, "LGTM!"
    )

    assert comment.id == "5002"
    assert comment.body == "LGTM!"
    assert comment.file_path is None


async def test_error_404_raises(github_client_with_transport):
    """Mock 404 response and verify SCMNotFoundError is raised."""
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"message": "Not Found"})

    client = github_client_with_transport(handler)

    with pytest.raises(SCMNotFoundError):
        await client.read_file("test-org/nonexistent", "missing.py")
