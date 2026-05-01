"""Bitbucket Cloud SCM implementation using httpx with retry and OAuth2 auth."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any
from urllib.parse import quote

import httpx
from tenacity import (
    retry,
    retry_if_exception,
    stop_after_attempt,
    wait_exponential,
)

from ai_driven.scm.exceptions import (
    SCMAuthError,
    SCMError,
    SCMNotFoundError,
    SCMRateLimitError,
    SCMValidationError,
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

BITBUCKET_API_BASE = "https://api.bitbucket.org/2.0"
BITBUCKET_AUTH_URL = "https://bitbucket.org/site/oauth2/access_token"


def _is_retryable(exc: BaseException) -> bool:
    """Return True for server errors and rate-limit errors (retryable)."""
    if isinstance(exc, SCMRateLimitError):
        return True
    if isinstance(exc, httpx.HTTPStatusError):
        return exc.response.status_code >= 500
    return False


_retry_policy = retry(
    retry=retry_if_exception(_is_retryable),
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=1, max=10),
    reraise=True,
)


def _raise_for_status(response: httpx.Response) -> None:
    """Raise specific SCM exceptions based on HTTP status codes."""
    status = response.status_code
    if status < 400:
        return

    if status == 404:
        raise SCMNotFoundError(f"Resource not found: {response.url}")

    if status == 422:
        structured: dict | None = None
        try:
            parsed = response.json()
            if isinstance(parsed, dict):
                structured = parsed
            detail: Any = parsed
        except Exception:
            detail = response.text
        raise SCMValidationError(f"Validation error: {detail}", detail=structured)

    if status == 429:
        retry_after = int(response.headers.get("Retry-After", "60"))
        raise SCMRateLimitError(retry_after=retry_after)

    if status == 401:
        raise SCMAuthError("Authentication failed")

    if status == 403:
        raise SCMAuthError(f"Forbidden: {response.url}")

    if status >= 500:
        response.raise_for_status()

    raise SCMError(f"Bitbucket API error {status}: {response.text[:200]}")


class BitbucketClient:
    """Bitbucket Cloud API client implementing the SCM interface."""

    def __init__(self, username: str, app_password: str) -> None:
        # Bitbucket's /pullrequests/{n}/diff endpoint returns a 302 redirect
        # to a signed URL; without follow_redirects the response body is empty
        # and our PR-diff parser sees 0 files. Same applies to /src/ raw file
        # reads on some paths. Enable follow_redirects globally.
        self._http = httpx.AsyncClient(
            base_url=BITBUCKET_API_BASE,
            auth=(username, app_password),
            timeout=30.0,
            follow_redirects=True,
        )

    @classmethod
    async def from_oauth(
        cls, client_id: str, client_secret: str
    ) -> BitbucketClient:
        """Create a client authenticated via OAuth2 client credentials.

        Exchanges client_id/client_secret for an access token using
        Bitbucket's OAuth2 token endpoint.
        """
        async with httpx.AsyncClient(timeout=30.0) as tmp_client:
            resp = await tmp_client.post(
                BITBUCKET_AUTH_URL,
                auth=(client_id, client_secret),
                data={"grant_type": "client_credentials"},
            )
            _raise_for_status(resp)
            token = resp.json()["access_token"]

        instance = cls.__new__(cls)
        instance._http = httpx.AsyncClient(
            base_url=BITBUCKET_API_BASE,
            headers={"Authorization": f"Bearer {token}"},
            timeout=30.0,
            follow_redirects=True,
        )
        return instance

    async def close(self) -> None:
        await self._http.aclose()

    # ------------------------------------------------------------------
    # Internal HTTP helper
    # ------------------------------------------------------------------

    @_retry_policy
    async def _request(
        self,
        method: str,
        url: str,
        *,
        params: dict[str, Any] | None = None,
        json: dict[str, Any] | None = None,
        data: dict[str, Any] | None = None,
        files: Any | None = None,
    ) -> httpx.Response:
        """Execute an HTTP request with retry and error handling."""
        resp = await self._http.request(
            method, url, params=params, json=json, data=data, files=files
        )
        _raise_for_status(resp)
        return resp

    # ------------------------------------------------------------------
    # File operations
    # ------------------------------------------------------------------

    async def _resolve_ref(self, repo_full_name: str, ref: str) -> str:
        """Resolve a branch name into a commit hash.

        Bitbucket Cloud's /src/{ref} endpoint is ambiguous when the ref contains
        a slash (e.g. "agent/bitbucket-smoke-3") — it parses only the first
        segment as the ref and treats the rest as a path. URL-encoding doesn't
        help (verified 2026-04-25 against origination1/ai-driven). The reliable
        fix is to resolve the branch to its commit hash first and then use the
        hash in the /src/{hash}/... URL.

        Shortcuts:
          * refs that look like full (40-char) or short (≥7-char hex) commit
            hashes are returned as-is
          * refs without slashes are returned as-is (Bitbucket handles them fine)
        """
        if "/" not in ref and (len(ref) >= 7 and all(c in "0123456789abcdef" for c in ref.lower())):
            return ref
        if "/" not in ref:
            return ref
        encoded = quote(ref, safe="")
        resp = await self._request(
            "GET", f"/repositories/{repo_full_name}/refs/branches/{encoded}"
        )
        sha = (resp.json().get("target") or {}).get("hash")
        if not sha:
            raise SCMError(
                f"Bitbucket returned no commit hash for branch {ref!r}"
            )
        return sha

    async def list_files(
        self, repo_full_name: str, path: str = "", ref: str = "main"
    ) -> list[DirectoryEntry]:
        """List files and directories with Bitbucket pagination (``next`` URL)."""
        resolved = await self._resolve_ref(repo_full_name, ref)
        entries: list[DirectoryEntry] = []
        url: str | None = f"/repositories/{repo_full_name}/src/{resolved}/{path}"
        params: dict[str, Any] | None = None

        while url is not None:
            resp = await self._request("GET", url, params=params)
            data = resp.json()

            entries.extend(
                DirectoryEntry(
                    path=item["path"],
                    name=item["path"].split("/")[-1],
                    is_directory=item["type"] == "commit_directory",
                    size=item.get("size", 0),
                )
                for item in data.get("values", [])
            )

            url = data.get("next")
            params = None  # params are embedded in the ``next`` URL

        return entries

    async def read_file(
        self, repo_full_name: str, path: str, ref: str = "main"
    ) -> FileContent:
        resolved = await self._resolve_ref(repo_full_name, ref)
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/src/{resolved}/{path}",
        )
        content = resp.text

        return FileContent(
            path=path, content=content, encoding="utf-8", size=len(content)
        )

    async def search_code(
        self, repo_full_name: str, query: str
    ) -> list[FileContent]:
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/src",
            params={"q": f'path ~ "{query}"'},
        )
        items = resp.json().get("values", [])

        results = []
        for item in items[:10]:
            if item["type"] == "commit_file":
                file_content = await self.read_file(repo_full_name, item["path"])
                results.append(file_content)
        return results

    async def create_or_update_file(
        self,
        repo_full_name: str,
        path: str,
        content: str,
        message: str,
        branch: str = "main",
    ) -> dict[str, Any]:
        """Commit a file change via the Bitbucket source endpoint."""
        resp = await self._request(
            "POST",
            f"/repositories/{repo_full_name}/src",
            data={
                "message": message,
                "branch": branch,
            },
            files={path: ("file", content.encode("utf-8"))},
        )
        return resp.json() if resp.text else {}

    # ------------------------------------------------------------------
    # Branch operations
    # ------------------------------------------------------------------

    async def create_branch(
        self, repo_full_name: str, branch_name: str, from_ref: str = "main"
    ) -> None:
        await self._request(
            "POST",
            f"/repositories/{repo_full_name}/refs/branches",
            json={"name": branch_name, "target": {"hash": from_ref}},
        )

    # ------------------------------------------------------------------
    # Pull request operations
    # ------------------------------------------------------------------

    async def create_pull_request(
        self,
        repo_full_name: str,
        title: str,
        body: str,
        source_branch: str,
        target_branch: str = "main",
    ) -> PullRequest:
        resp = await self._request(
            "POST",
            f"/repositories/{repo_full_name}/pullrequests",
            json={
                "title": title,
                "description": body,
                "source": {"branch": {"name": source_branch}},
                "destination": {"branch": {"name": target_branch}},
                "close_source_branch": True,
            },
        )
        return self._parse_pr(resp.json())

    async def get_pull_request(
        self, repo_full_name: str, pr_number: int
    ) -> PullRequest:
        """Fetch a single pull request by ID."""
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}",
        )
        return self._parse_pr(resp.json())

    async def list_pull_requests(
        self,
        repo_full_name: str,
        state: str = "OPEN",
        page: int = 1,
        pagelen: int = 25,
    ) -> list[PullRequest]:
        """List pull requests with state filter and pagination."""
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/pullrequests",
            params={"state": state, "page": str(page), "pagelen": str(pagelen)},
        )
        return [self._parse_pr(pr) for pr in resp.json().get("values", [])]

    async def find_pr_by_branch(
        self, repo_full_name: str, branch_ref: str
    ) -> tuple[int, str] | None:
        """Look up an open PR by its source branch.

        Used to recover a PR id/URL after a 422 "pull request already exists"
        response. Bitbucket supports a ``q`` BBQL filter on the pullrequests
        endpoint — we query ``source.branch.name`` rather than paginating the
        full open list.

        Returns ``(pr_id, url)`` or ``None`` if no open PR matches.
        """
        if not branch_ref:
            return None
        q = f'state="OPEN" AND source.branch.name="{branch_ref}"'
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/pullrequests",
            params={"q": q, "pagelen": "1"},
        )
        values = (resp.json() or {}).get("values") or []
        if not values:
            return None
        pr = values[0]
        url = ((pr.get("links") or {}).get("html") or {}).get("href") or ""
        return (int(pr["id"]), url)

    async def get_pr_diff(
        self, repo_full_name: str, pr_number: int
    ) -> PRDiff:
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}/diff",
        )
        diff_text = resp.text

        files: list[DiffFile] = []
        for section in diff_text.split("diff --git"):
            if not section.strip():
                continue
            lines = section.strip().split("\n")
            path = ""
            for line in lines:
                if line.startswith("+++ b/"):
                    path = line[6:]
                    break
            additions = sum(
                1 for ln in lines if ln.startswith("+") and not ln.startswith("+++")
            )
            deletions = sum(
                1 for ln in lines if ln.startswith("-") and not ln.startswith("---")
            )
            files.append(
                DiffFile(
                    path=path,
                    status="modified",
                    additions=additions,
                    deletions=deletions,
                    patch=section,
                )
            )

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
        payload: dict[str, Any] = {"content": {"raw": body}}
        if file_path and line:
            payload["inline"] = {"path": file_path, "to": line}

        resp = await self._request(
            "POST",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}/comments",
            json=payload,
        )
        data = resp.json()

        return PRComment(
            id=str(data["id"]),
            body=data["content"]["raw"],
            author=data["user"]["display_name"],
            file_path=data.get("inline", {}).get("path"),
            line=data.get("inline", {}).get("to"),
        )

    async def merge_pull_request(
        self, repo_full_name: str, pr_number: int, merge_method: str = "squash"
    ) -> None:
        strategy_map = {
            "squash": "squash",
            "merge": "merge_commit",
            "rebase": "fast_forward",
        }
        await self._request(
            "POST",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}/merge",
            json={"merge_strategy": strategy_map.get(merge_method, "squash")},
        )

    async def approve_pr(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        """Approve a PR via Bitbucket's approval endpoint."""
        await self._request(
            "POST",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}/approve",
        )

    async def request_changes(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        """Request changes by posting a comment (Bitbucket has no native request-changes)."""
        await self._request(
            "POST",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}/comments",
            json={"content": {"raw": body or "Changes requested by AI-Driven Review Bot"}},
        )

    async def get_commit_diff(
        self, repo_full_name: str, commit_sha: str
    ) -> PRDiff:
        """Get the diff for a single commit."""
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/diff/{commit_sha}",
        )
        diff_text = resp.text
        files: list[DiffFile] = []
        for section in diff_text.split("diff --git"):
            if not section.strip():
                continue
            lines = section.strip().split("\n")
            path = ""
            for line in lines:
                if line.startswith("+++ b/"):
                    path = line[6:]
                    break
            additions = sum(1 for ln in lines if ln.startswith("+") and not ln.startswith("+++"))
            deletions = sum(1 for ln in lines if ln.startswith("-") and not ln.startswith("---"))
            files.append(DiffFile(path=path, status="modified", additions=additions, deletions=deletions, patch=section))

        return PRDiff(
            files=files,
            total_additions=sum(f.additions for f in files),
            total_deletions=sum(f.deletions for f in files),
        )

    async def list_recent_merges(
        self, repo_full_name: str, limit: int = 10
    ) -> list[dict[str, Any]]:
        """List recently merged PRs."""
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/pullrequests",
            params={"state": "MERGED", "pagelen": str(limit)},
        )
        merged = []
        for pr in resp.json().get("values", []):
            merged.append({
                "number": pr["id"],
                "title": pr["title"],
                "merged_at": pr.get("updated_on", ""),
                "author": pr["author"]["display_name"],
                "merge_commit_sha": pr.get("merge_commit", {}).get("hash", ""),
            })
        return merged[:limit]

    async def get_pr_reviewers(
        self, repo_full_name: str, pr_number: int
    ) -> list[PRReviewer]:
        """Build reviewer list from PR's participants + reviewers arrays."""
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}",
        )
        pr = resp.json()

        state_map = {
            "approved": "approved",
            "changes_requested": "changes_requested",
        }

        out: dict[str, PRReviewer] = {}

        # Participants with an explicit state win (approved / changes_requested)
        for p in pr.get("participants") or []:
            user = p.get("user") or {}
            login = user.get("account_id") or user.get("uuid") or user.get("display_name")
            if not login:
                continue
            raw_state = (p.get("state") or "").lower()
            state = state_map.get(raw_state, "commented" if raw_state else "pending")
            out[login] = PRReviewer(
                username=user.get("nickname") or user.get("display_name") or login,
                display_name=user.get("display_name") or login,
                avatar_url=((user.get("links") or {}).get("avatar") or {}).get("href"),
                is_bot=(user.get("type") == "app"),
                state=state,
            )

        # Requested-but-not-participated reviewers → pending
        for user in pr.get("reviewers") or []:
            login = user.get("account_id") or user.get("uuid") or user.get("display_name")
            if not login or login in out:
                continue
            out[login] = PRReviewer(
                username=user.get("nickname") or user.get("display_name") or login,
                display_name=user.get("display_name") or login,
                avatar_url=((user.get("links") or {}).get("avatar") or {}).get("href"),
                is_bot=(user.get("type") == "app"),
                state="pending",
            )

        return list(out.values())

    async def get_pr_checks(
        self, repo_full_name: str, pr_number: int
    ) -> list[PRCheck]:
        """Fetch build statuses for a PR."""
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}/statuses",
        )
        values = (resp.json() or {}).get("values") or []

        # state -> (status, conclusion)
        state_map = {
            "SUCCESSFUL": ("completed", "success"),
            "FAILED": ("completed", "failure"),
            "STOPPED": ("completed", "cancelled"),
            "INPROGRESS": ("in_progress", None),
        }

        checks: list[PRCheck] = []
        for v in values:
            raw_state = (v.get("state") or "").upper()
            status, conclusion = state_map.get(raw_state, ("queued", None))
            name = v.get("key") or v.get("name") or "check"
            checks.append(PRCheck(
                name=name,
                status=status,
                conclusion=conclusion,
                duration_seconds=None,
                details_url=v.get("url"),
            ))
        return checks

    async def get_pr_commits(
        self, repo_full_name: str, pr_number: int
    ) -> list[Commit]:
        """List commits on a Bitbucket Cloud PR."""
        resp = await self._request(
            "GET",
            f"/repositories/{repo_full_name}/pullrequests/{pr_number}/commits",
        )
        out: list[Commit] = []
        for item in (resp.json() or {}).get("values") or []:
            sha = item.get("hash", "")
            ts_raw = item.get("date")
            try:
                ts = datetime.fromisoformat(ts_raw.replace("Z", "+00:00")) if ts_raw else datetime.utcnow()
            except Exception:
                ts = datetime.utcnow()
            author_node = (item.get("author") or {}).get("user") or {}
            author = (author_node.get("display_name")
                      or (item.get("author") or {}).get("raw") or "unknown")
            url = ((item.get("links") or {}).get("html") or {}).get("href")
            out.append(Commit(
                sha=sha,
                message=(item.get("message") or "").split("\n", 1)[0],
                author=author,
                timestamp=ts,
                url=url,
            ))
        return out

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _parse_pr(data: dict[str, Any]) -> PullRequest:
        state_map = {
            "OPEN": PRState.OPEN,
            "MERGED": PRState.MERGED,
            "DECLINED": PRState.CLOSED,
        }
        state = state_map.get(data.get("state", "OPEN"), PRState.OPEN)

        return PullRequest(
            number=data["id"],
            title=data["title"],
            body=data.get("description", ""),
            state=state,
            source_branch=data["source"]["branch"]["name"],
            target_branch=data["destination"]["branch"]["name"],
            author=data["author"]["display_name"],
            url=data["links"]["html"]["href"],
        )
