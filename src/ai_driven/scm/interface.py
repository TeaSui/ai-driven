"""Abstract SCM interface — protocol for GitHub and Bitbucket implementations."""

from __future__ import annotations

from typing import Any, Protocol

from ai_driven.scm.models import (
    Commit,
    DirectoryEntry,
    FileContent,
    PRCheck,
    PRComment,
    PRDiff,
    PRReviewer,
    PullRequest,
)


class SCMClient(Protocol):
    """Unified interface for source code management operations."""

    async def list_files(
        self, repo_full_name: str, path: str = "", ref: str = "main"
    ) -> list[DirectoryEntry]:
        """List files and directories at a path in a repository."""
        ...

    async def read_file(
        self, repo_full_name: str, path: str, ref: str = "main"
    ) -> FileContent:
        """Read a file's contents from a repository."""
        ...

    async def search_code(
        self, repo_full_name: str, query: str
    ) -> list[FileContent]:
        """Search for code patterns in a repository."""
        ...

    async def create_branch(
        self, repo_full_name: str, branch_name: str, from_ref: str = "main"
    ) -> None:
        """Create a new branch from a reference."""
        ...

    async def create_pull_request(
        self,
        repo_full_name: str,
        title: str,
        body: str,
        source_branch: str,
        target_branch: str = "main",
    ) -> PullRequest:
        """Create a new pull request."""
        ...

    async def get_pr_diff(
        self, repo_full_name: str, pr_number: int
    ) -> PRDiff:
        """Get the diff of a pull request."""
        ...

    async def post_pr_comment(
        self,
        repo_full_name: str,
        pr_number: int,
        body: str,
        file_path: str | None = None,
        line: int | None = None,
    ) -> PRComment:
        """Post a comment on a pull request (general or inline)."""
        ...

    async def merge_pull_request(
        self, repo_full_name: str, pr_number: int, merge_method: str = "squash"
    ) -> None:
        """Merge a pull request."""
        ...

    async def approve_pr(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        """Submit an approving review on a pull request."""
        ...

    async def request_changes(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        """Submit a request-changes review on a pull request."""
        ...

    async def get_commit_diff(
        self, repo_full_name: str, commit_sha: str
    ) -> PRDiff:
        """Get the diff for a specific commit."""
        ...

    async def list_recent_merges(
        self, repo_full_name: str, limit: int = 10
    ) -> list[dict[str, Any]]:
        """List recently merged PRs (used as deployment proxy)."""
        ...

    async def get_pr_reviewers(
        self, repo_full_name: str, pr_number: int
    ) -> list[PRReviewer]:
        """List reviewers on a pull request with their latest review state."""
        ...

    async def get_pr_checks(
        self, repo_full_name: str, pr_number: int
    ) -> list[PRCheck]:
        """List CI check runs for a pull request's head commit."""
        ...

    async def get_pr_commits(
        self, repo_full_name: str, pr_number: int
    ) -> list[Commit]:
        """List the commits on a pull request."""
        ...

    async def create_or_update_files_batch(
        self,
        repo_full_name: str,
        files: list[dict[str, str]],
        message: str,
        branch: str = "main",
    ) -> dict[str, Any]:
        """Write N files in a SINGLE commit.

        ``files`` is ordered; later entries win on duplicate paths. Returns a
        backend-specific response (GitHub: commit SHA envelope; Bitbucket:
        commit object or a fallback echo of the paths).
        """
        ...
