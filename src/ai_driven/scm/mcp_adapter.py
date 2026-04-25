"""MCPSCMAdapter — implements the SCMClient Protocol on top of an MCP server.

Scaffolding for Phase A of docs/implementation/12-scm-via-mcp.md.

The official github/github-mcp-server exposes 102 tools covering 13 of our 15
`SCMClient` methods directly. Two methods — `get_pr_diff` and `get_pr_checks`
— have no direct MCP equivalent yet, so the adapter falls back to direct REST
calls against GitHub's API for those. Both fallbacks live in this file so the
rest of the codebase never has to know.

Status: scaffolding only. Each method's MCP wiring is marked with TODO(phase-a).
Wire up progressively, method by method, behind `scm_github_via_mcp` flag.
Until the flag is flipped, factory keeps returning the hand-rolled GitHubClient.
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from ai_driven.scm.exceptions import (
    SCMAuthError,
    SCMError,
    SCMNotFoundError,
    SCMRateLimitError,
)
from ai_driven.scm.models import (
    Commit,
    DiffFile,
    DirectoryEntry,
    FileContent,
    PRCheck,
    PRComment,
    PRDiff,
    PRReviewer,
    PRState,
    PullRequest,
)

logger = logging.getLogger(__name__)


class MCPSCMAdapter:
    """Implements SCMClient Protocol by delegating to an MCP server.

    Lifecycle: one adapter per request (cheap — holds no state beyond the
    reference to the shared McpClientManager and an optional REST fallback
    client).

    The `rest_fallback` client is used only for the two methods GitHub MCP
    doesn't cover cleanly (`get_pr_diff`, `get_pr_checks`). If `rest_fallback`
    is None, those two methods raise SCMError.
    """

    def __init__(
        self,
        *,
        server_name: str,
        mcp_manager: Any,  # McpClientManager — untyped to avoid import cycle
        rest_fallback: httpx.AsyncClient | None = None,
    ) -> None:
        self._server = server_name
        self._mcp = mcp_manager
        self._rest = rest_fallback

    async def close(self) -> None:
        """Mirrors GitHubClient.close() for call-site parity."""
        if self._rest is not None:
            await self._rest.aclose()

    # ------------------------------------------------------------------
    # MCP call helper
    # ------------------------------------------------------------------

    async def _call(self, tool: str, arguments: dict[str, Any]) -> Any:
        """Invoke an MCP tool, translating errors into our SCM* exception tree."""
        # Tool names are registered in McpClientManager as
        # f"mcp_{server_name}_{original_tool_name}".
        prefixed = f"mcp_{self._server}_{tool}"
        try:
            raw = await self._mcp.call_tool(prefixed, arguments)
        except Exception as ex:  # pragma: no cover — depends on MCP runtime
            raise SCMError(f"MCP call failed: {self._server}/{tool}: {ex}") from ex

        # McpClientManager wraps text responses as {"result": "..."}.
        # Tool-call errors come back as {"error": "..."}.
        if isinstance(raw, dict):
            if "error" in raw:
                detail = str(raw["error"])
                low = detail.lower()
                if "not found" in low or "404" in low:
                    raise SCMNotFoundError(detail)
                if "rate limit" in low or "429" in low:
                    raise SCMRateLimitError(retry_after=60)
                if "unauthorized" in low or "401" in low or "forbidden" in low:
                    raise SCMAuthError(detail)
                raise SCMError(detail)
        return raw

    # ------------------------------------------------------------------
    # SCMClient Protocol — read operations
    # ------------------------------------------------------------------

    async def list_files(
        self, repo_full_name: str, path: str = "", ref: str = "main"
    ) -> list[DirectoryEntry]:
        """TODO(phase-a): wire to `get_file_contents` (directory mode)."""
        raise NotImplementedError("MCPSCMAdapter.list_files — phase A A2")

    async def read_file(
        self, repo_full_name: str, path: str, ref: str = "main"
    ) -> FileContent:
        """TODO(phase-a): wire to `get_file_contents` (file mode)."""
        raise NotImplementedError("MCPSCMAdapter.read_file — phase A A2")

    async def search_code(
        self, repo_full_name: str, query: str
    ) -> list[FileContent]:
        """TODO(phase-a): wire to `search_code`."""
        raise NotImplementedError("MCPSCMAdapter.search_code — phase A A2")

    # ------------------------------------------------------------------
    # SCMClient Protocol — write operations
    # ------------------------------------------------------------------

    async def create_branch(
        self, repo_full_name: str, branch_name: str, from_ref: str = "main"
    ) -> None:
        """TODO(phase-a): wire to `create_branch`."""
        raise NotImplementedError("MCPSCMAdapter.create_branch — phase A A2")

    async def create_or_update_file(
        self,
        repo_full_name: str,
        path: str,
        content: str,
        commit_message: str,
        branch: str,
    ) -> None:
        """TODO(phase-a): wire to `create_or_update_file`."""
        raise NotImplementedError(
            "MCPSCMAdapter.create_or_update_file — phase A A2"
        )

    async def create_pull_request(
        self,
        repo_full_name: str,
        title: str,
        body: str,
        source_branch: str,
        target_branch: str = "main",
    ) -> PullRequest:
        """TODO(phase-a): wire to `create_pull_request`."""
        raise NotImplementedError("MCPSCMAdapter.create_pull_request — phase A A2")

    async def get_pull_request(
        self, repo_full_name: str, pr_number: int
    ) -> PullRequest:
        """TODO(phase-a): wire to `pull_request_read`."""
        raise NotImplementedError("MCPSCMAdapter.get_pull_request — phase A A2")

    async def list_pull_requests(
        self,
        repo_full_name: str,
        state: str = "open",
        page: int = 1,
        per_page: int = 30,
    ) -> list[PullRequest]:
        """TODO(phase-a): wire to `list_pull_requests`."""
        raise NotImplementedError("MCPSCMAdapter.list_pull_requests — phase A A2")

    # ------------------------------------------------------------------
    # SCMClient Protocol — PR workflow methods
    # ------------------------------------------------------------------

    async def get_pr_diff(
        self, repo_full_name: str, pr_number: int
    ) -> PRDiff:
        """REST fallback: GitHub MCP has no PR-files diff endpoint.

        Uses direct call to `/repos/{owner}/{repo}/pulls/{n}/files` via
        `rest_fallback` client.
        """
        if self._rest is None:
            raise SCMError(
                "get_pr_diff requires rest_fallback because the MCP server "
                "does not expose a PR-diff tool. Pass an httpx.AsyncClient "
                "with a GitHub token to MCPSCMAdapter(rest_fallback=...)."
            )
        resp = await self._rest.get(
            f"/repos/{repo_full_name}/pulls/{pr_number}/files"
        )
        _raise_rest(resp)
        files_data = resp.json()
        files = [
            DiffFile(
                path=f["filename"],
                status=f["status"],
                additions=f.get("additions", 0),
                deletions=f.get("deletions", 0),
                patch=f.get("patch", ""),
            )
            for f in files_data
        ]
        return PRDiff(
            files=files,
            total_additions=sum(f.additions for f in files),
            total_deletions=sum(f.deletions for f in files),
        )

    async def post_pr_comment(
        self,
        repo_full_name: str,
        pr_number: int,
        body: str,
        file_path: str | None = None,
        line: int | None = None,
    ) -> PRComment:
        """TODO(phase-a): wire to `pull_request_review_write`."""
        raise NotImplementedError("MCPSCMAdapter.post_pr_comment — phase A A2")

    async def merge_pull_request(
        self, repo_full_name: str, pr_number: int, merge_method: str = "squash"
    ) -> None:
        """TODO(phase-a): wire to `merge_pull_request`."""
        raise NotImplementedError("MCPSCMAdapter.merge_pull_request — phase A A2")

    async def approve_pr(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        """TODO(phase-a): wire to `pull_request_review_write` with event=APPROVE."""
        raise NotImplementedError("MCPSCMAdapter.approve_pr — phase A A2")

    async def request_changes(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        """TODO(phase-a): wire to `pull_request_review_write` with event=REQUEST_CHANGES."""
        raise NotImplementedError("MCPSCMAdapter.request_changes — phase A A2")

    async def get_commit_diff(
        self, repo_full_name: str, commit_sha: str
    ) -> PRDiff:
        """TODO(phase-a): wire to `get_commit`."""
        raise NotImplementedError("MCPSCMAdapter.get_commit_diff — phase A A2")

    async def list_recent_merges(
        self, repo_full_name: str, limit: int = 10
    ) -> list[dict[str, Any]]:
        """TODO(phase-a): wire to `list_pull_requests` + client-side merge filter."""
        raise NotImplementedError("MCPSCMAdapter.list_recent_merges — phase A A2")

    # ------------------------------------------------------------------
    # SCMClient Protocol — review sidebar methods
    # ------------------------------------------------------------------

    async def get_pr_reviewers(
        self, repo_full_name: str, pr_number: int
    ) -> list[PRReviewer]:
        """TODO(phase-a): compose from `pull_request_read` + review-list tool."""
        raise NotImplementedError("MCPSCMAdapter.get_pr_reviewers — phase A A2")

    async def get_pr_checks(
        self, repo_full_name: str, pr_number: int
    ) -> list[PRCheck]:
        """REST fallback: GitHub MCP has no check-runs endpoint.

        Uses direct call to `/repos/{owner}/{repo}/commits/{sha}/check-runs`
        via `rest_fallback` client.
        """
        if self._rest is None:
            raise SCMError(
                "get_pr_checks requires rest_fallback because the MCP server "
                "does not expose a check-runs tool."
            )
        # 1. Resolve PR head SHA.
        pr_resp = await self._rest.get(
            f"/repos/{repo_full_name}/pulls/{pr_number}"
        )
        _raise_rest(pr_resp)
        sha = (pr_resp.json().get("head") or {}).get("sha")
        if not sha:
            return []

        # 2. Fetch check-runs for that SHA.
        resp = await self._rest.get(
            f"/repos/{repo_full_name}/commits/{sha}/check-runs"
        )
        _raise_rest(resp)
        from datetime import datetime

        runs = (resp.json() or {}).get("check_runs") or []
        out: list[PRCheck] = []
        for run in runs:
            duration: int | None = None
            started, completed = run.get("started_at"), run.get("completed_at")
            if started and completed:
                try:
                    s = datetime.fromisoformat(started.replace("Z", "+00:00"))
                    c = datetime.fromisoformat(completed.replace("Z", "+00:00"))
                    duration = max(0, int((c - s).total_seconds()))
                except Exception:
                    duration = None
            out.append(PRCheck(
                name=run.get("name") or "check",
                status=run.get("status") or "queued",
                conclusion=run.get("conclusion"),
                duration_seconds=duration,
                details_url=run.get("html_url"),
            ))
        return out

    async def get_pr_commits(
        self, repo_full_name: str, pr_number: int
    ) -> list[Commit]:
        """TODO(phase-a): wire to `list_commits` scoped to PR."""
        raise NotImplementedError("MCPSCMAdapter.get_pr_commits — phase A A2")


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------


def _raise_rest(resp: httpx.Response) -> None:
    """Translate a failing REST response into our SCM* exception hierarchy."""
    status = resp.status_code
    if status < 400:
        return
    if status == 404:
        raise SCMNotFoundError(f"Resource not found: {resp.url}")
    if status == 429:
        retry_after = int(resp.headers.get("Retry-After", "60"))
        raise SCMRateLimitError(retry_after=retry_after)
    if status in (401, 403):
        raise SCMAuthError(f"Auth failed ({status}): {resp.url}")
    raise SCMError(f"REST fallback error {status}: {resp.text[:200]}")


# Reference the protocol symbol so mypy / linters flag this file if the
# Protocol drifts — PRState import is used there (and keeps ruff quiet here).
_ = PRState
