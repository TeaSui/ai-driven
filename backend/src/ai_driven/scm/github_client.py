"""GitHub SCM implementation using httpx with retry, rate-limit awareness, and App auth."""

from __future__ import annotations

import asyncio
import base64
import logging
import time
from datetime import datetime, timezone
from typing import Any

import httpx
import jwt
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
    PRReviewer,
    PRComment,
    PRDiff,
    PRState,
    PullRequest,
)

logger = logging.getLogger(__name__)

GITHUB_API_BASE = "https://api.github.com"

_RATE_LIMIT_WARNING_THRESHOLD = 100


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


def _check_rate_limit(response: httpx.Response) -> None:
    """Log a warning when GitHub rate-limit remaining drops below threshold."""
    remaining = response.headers.get("X-RateLimit-Remaining")
    if remaining is not None:
        try:
            remaining_int = int(remaining)
        except (ValueError, TypeError):
            return
        if remaining_int < _RATE_LIMIT_WARNING_THRESHOLD:
            reset_at = response.headers.get("X-RateLimit-Reset", "unknown")
            logger.warning(
                "GitHub rate limit low: %d remaining (resets at %s)",
                remaining_int,
                reset_at,
            )


def _raise_for_status(response: httpx.Response) -> None:
    """Raise specific SCM exceptions based on HTTP status codes."""
    status = response.status_code
    if status < 400:
        _check_rate_limit(response)
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

    if status == 429 or (
        status == 403
        and response.headers.get("X-RateLimit-Remaining") == "0"
    ):
        retry_after = int(response.headers.get("Retry-After", "60"))
        raise SCMRateLimitError(retry_after=retry_after)

    if status == 401:
        raise SCMAuthError("Authentication failed")

    if status == 403:
        raise SCMAuthError(f"Forbidden: {response.url}")

    if status >= 500:
        response.raise_for_status()

    raise SCMError(f"GitHub API error {status}: {response.text[:200]}")


class GitHubClient:
    """GitHub API client implementing the SCM interface."""

    def __init__(self, token: str) -> None:
        self._http = httpx.AsyncClient(
            base_url=GITHUB_API_BASE,
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
            timeout=30.0,
        )

    @classmethod
    async def from_app(
        cls, app_id: int, private_key: str, installation_id: int
    ) -> GitHubClient:
        """Create a client authenticated as a GitHub App installation.

        Generates a JWT from the App credentials, then exchanges it for an
        installation access token.
        """
        now = int(time.time())
        payload = {
            "iat": now - 60,
            "exp": now + (10 * 60),
            "iss": str(app_id),
        }
        app_jwt = jwt.encode(payload, private_key, algorithm="RS256")

        async with httpx.AsyncClient(
            base_url=GITHUB_API_BASE,
            headers={
                "Authorization": f"Bearer {app_jwt}",
                "Accept": "application/vnd.github+json",
            },
            timeout=30.0,
        ) as tmp_client:
            resp = await tmp_client.post(
                f"/app/installations/{installation_id}/access_tokens"
            )
            _raise_for_status(resp)
            token = resp.json()["token"]

        return cls(token=token)

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
    ) -> httpx.Response:
        """Execute an HTTP request with retry and error handling."""
        resp = await self._http.request(method, url, params=params, json=json)
        _raise_for_status(resp)
        return resp

    # ------------------------------------------------------------------
    # File operations
    # ------------------------------------------------------------------

    async def list_files(
        self, repo_full_name: str, path: str = "", ref: str = "main"
    ) -> list[DirectoryEntry]:
        """List files and directories at a path, with pagination support."""
        entries: list[DirectoryEntry] = []
        url: str | None = f"/repos/{repo_full_name}/contents/{path}"
        params: dict[str, Any] = {"ref": ref}

        while url is not None:
            resp = await self._request("GET", url, params=params)
            data = resp.json()

            if isinstance(data, dict):
                entries.append(
                    DirectoryEntry(
                        path=data["path"],
                        name=data["name"],
                        is_directory=data["type"] == "dir",
                        size=data.get("size", 0),
                    )
                )
                break

            entries.extend(
                DirectoryEntry(
                    path=item["path"],
                    name=item["name"],
                    is_directory=item["type"] == "dir",
                    size=item.get("size", 0),
                )
                for item in data
            )

            url = self._next_link(resp)
            params = {}  # params are embedded in the Link URL

        return entries

    async def read_file(
        self, repo_full_name: str, path: str, ref: str = "main"
    ) -> FileContent:
        resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/contents/{path}",
            params={"ref": ref},
        )
        data = resp.json()

        content = ""
        if data.get("encoding") == "base64" and data.get("content"):
            content = base64.b64decode(data["content"]).decode("utf-8")

        return FileContent(
            path=data["path"],
            content=content,
            encoding="utf-8",
            size=data.get("size", 0),
        )

    async def search_code(
        self, repo_full_name: str, query: str
    ) -> list[FileContent]:
        resp = await self._request(
            "GET",
            "/search/code",
            params={"q": f"{query} repo:{repo_full_name}"},
        )
        items = resp.json().get("items", [])

        results = []
        for item in items[:10]:
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
        sha: str | None = None,
    ) -> dict[str, Any]:
        """Create or update a file via the Contents API.

        When *sha* is not provided, we first check whether the file already
        exists on the target branch. GitHub's Contents API rejects updates
        to existing files without the current blob SHA ("sha wasn't supplied"),
        so auto-detecting it means callers don't have to do two round trips.
        """
        encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")

        # Auto-detect sha for existing files on the branch.
        if sha is None:
            try:
                probe = await self._request(
                    "GET",
                    f"/repos/{repo_full_name}/contents/{path}",
                    params={"ref": branch},
                )
                if probe.status_code == 200:
                    probe_json = probe.json()
                    if isinstance(probe_json, dict) and probe_json.get("type") == "file":
                        sha = probe_json.get("sha")
            except Exception:
                # File doesn't exist yet (404) or probe failed — fall through
                # and attempt a create (no sha).
                pass

        body: dict[str, Any] = {
            "message": message,
            "content": encoded,
            "branch": branch,
        }
        if sha is not None:
            body["sha"] = sha

        resp = await self._request(
            "PUT",
            f"/repos/{repo_full_name}/contents/{path}",
            json=body,
        )
        return resp.json()

    async def create_or_update_files_batch(
        self,
        repo_full_name: str,
        files: list[dict[str, str]],
        message: str,
        branch: str = "main",
    ) -> dict[str, Any]:
        """Write multiple files in a SINGLE commit via the Git Data API.

        Atomic 5-step flow (plus a 6th PATCH to move the ref):

          1. GET /repos/{repo}/git/ref/heads/{branch} → parent commit SHA
          2. GET /repos/{repo}/git/commits/{parent_sha} → parent tree SHA
          3. POST /repos/{repo}/git/blobs  (one per file, run in parallel with
             asyncio.gather to avoid N× serial latency)
          4. POST /repos/{repo}/git/trees  with base_tree = parent tree SHA
          5. POST /repos/{repo}/git/commits with parents = [parent commit SHA]
          6. PATCH /repos/{repo}/git/refs/heads/{branch}  (force=false — we
             fast-forward from the parent we just read; a concurrent push
             between step 1 and step 6 legitimately fails the PATCH and we
             surface that error so the caller can retry.)

        Duplicate paths are deduped (last wins) to match the semantics of N
        sequential ``file.write`` calls.
        """
        if not files:
            return {"sha": None, "message": message, "files": []}

        # Dedupe by path — last entry wins, preserving call order.
        deduped: dict[str, str] = {}
        for f in files:
            path = f["path"]
            content = f["content"]
            deduped[path] = content
        ordered_paths = list(deduped.keys())

        # Step 1: parent commit SHA from the branch ref.
        ref_resp = await self._request(
            "GET", f"/repos/{repo_full_name}/git/ref/heads/{branch}"
        )
        parent_commit_sha = ref_resp.json()["object"]["sha"]

        # Step 2: parent tree SHA from the parent commit.
        commit_resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/git/commits/{parent_commit_sha}",
        )
        parent_tree_sha = commit_resp.json()["tree"]["sha"]

        # Step 3: create a blob per file, in parallel.
        async def _create_blob(content: str) -> str:
            resp = await self._request(
                "POST",
                f"/repos/{repo_full_name}/git/blobs",
                json={"content": content, "encoding": "utf-8"},
            )
            return resp.json()["sha"]

        blob_shas = await asyncio.gather(
            *[_create_blob(deduped[p]) for p in ordered_paths]
        )

        # Step 4: build a new tree layered on the parent tree.
        tree_entries = [
            {
                "path": path,
                "mode": "100644",
                "type": "blob",
                "sha": sha,
            }
            for path, sha in zip(ordered_paths, blob_shas, strict=True)
        ]
        tree_resp = await self._request(
            "POST",
            f"/repos/{repo_full_name}/git/trees",
            json={"base_tree": parent_tree_sha, "tree": tree_entries},
        )
        new_tree_sha = tree_resp.json()["sha"]

        # Step 5: create the commit pointing at that tree.
        commit_resp = await self._request(
            "POST",
            f"/repos/{repo_full_name}/git/commits",
            json={
                "message": message,
                "tree": new_tree_sha,
                "parents": [parent_commit_sha],
            },
        )
        new_commit_sha = commit_resp.json()["sha"]

        # Step 6: move the branch ref forward. force=false — fail loudly if
        # someone else pushed concurrently rather than clobbering their work.
        await self._request(
            "PATCH",
            f"/repos/{repo_full_name}/git/refs/heads/{branch}",
            json={"sha": new_commit_sha, "force": False},
        )

        return {
            "sha": new_commit_sha,
            "message": message,
            "files": ordered_paths,
        }

    # ------------------------------------------------------------------
    # Branch operations
    # ------------------------------------------------------------------

    async def create_branch(
        self, repo_full_name: str, branch_name: str, from_ref: str = "main"
    ) -> None:
        ref_resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/git/ref/heads/{from_ref}",
        )
        sha = ref_resp.json()["object"]["sha"]

        try:
            await self._request(
                "POST",
                f"/repos/{repo_full_name}/git/refs",
                json={"ref": f"refs/heads/{branch_name}", "sha": sha},
            )
        except SCMValidationError as e:
            if "already exists" in str(e).lower():
                # Branch exists — update it to point at the latest base ref SHA
                logger.info("Branch %s already exists, updating to %s", branch_name, sha[:8])
                await self._request(
                    "PATCH",
                    f"/repos/{repo_full_name}/git/refs/heads/{branch_name}",
                    json={"sha": sha, "force": True},
                )
            else:
                raise

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
            f"/repos/{repo_full_name}/pulls",
            json={
                "title": title,
                "body": body,
                "head": source_branch,
                "base": target_branch,
            },
        )
        return self._parse_pr(resp.json())

    async def get_pull_request(
        self, repo_full_name: str, pr_number: int
    ) -> PullRequest:
        """Fetch a single pull request by number."""
        resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/pulls/{pr_number}",
        )
        return self._parse_pr(resp.json())

    async def list_pull_requests(
        self,
        repo_full_name: str,
        state: str = "open",
        page: int = 1,
        per_page: int = 30,
    ) -> list[PullRequest]:
        """List pull requests with state filter and pagination."""
        resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/pulls",
            params={"state": state, "page": page, "per_page": per_page},
        )
        return [self._parse_pr(pr) for pr in resp.json()]

    async def find_pr_by_branch(
        self, repo_full_name: str, branch_ref: str
    ) -> tuple[int, str] | None:
        """Look up an open PR by its source branch.

        Used to recover a PR number/URL after a 422 "pull request already
        exists" response — GitHub's 422 body only echoes the branch ref, not
        the PR number, so we have to follow up with a GET filtered by
        ``head``.

        ``branch_ref`` may be passed as a bare branch name (e.g. ``feat/x``)
        or already qualified with the owner prefix (``owner:feat/x``) — both
        forms are accepted by GitHub's ``head`` filter so we forward as-is.

        Returns ``(pr_number, url)`` or ``None`` if no open PR matches.
        """
        if "/" not in repo_full_name or not branch_ref:
            return None
        owner = repo_full_name.split("/", 1)[0]
        head = branch_ref if ":" in branch_ref else f"{owner}:{branch_ref}"
        resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/pulls",
            params={"state": "open", "head": head, "per_page": 1},
        )
        items = resp.json() or []
        if not items:
            return None
        pr = items[0]
        return (int(pr["number"]), pr.get("html_url") or "")

    async def get_pr_diff(
        self, repo_full_name: str, pr_number: int
    ) -> PRDiff:
        resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/pulls/{pr_number}/files",
        )
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
        if file_path and line:
            resp = await self._request(
                "POST",
                f"/repos/{repo_full_name}/pulls/{pr_number}/comments",
                json={
                    "body": body,
                    "path": file_path,
                    "line": line,
                    "side": "RIGHT",
                },
            )
        else:
            resp = await self._request(
                "POST",
                f"/repos/{repo_full_name}/issues/{pr_number}/comments",
                json={"body": body},
            )

        data = resp.json()
        return PRComment(
            id=str(data["id"]),
            body=data["body"],
            author=data["user"]["login"],
            file_path=data.get("path"),
            line=data.get("line"),
        )

    async def merge_pull_request(
        self, repo_full_name: str, pr_number: int, merge_method: str = "squash"
    ) -> None:
        await self._request(
            "PUT",
            f"/repos/{repo_full_name}/pulls/{pr_number}/merge",
            json={"merge_method": merge_method},
        )

    async def approve_pr(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        """Submit an APPROVE review."""
        await self._request(
            "POST",
            f"/repos/{repo_full_name}/pulls/{pr_number}/reviews",
            json={"event": "APPROVE", "body": body or "Approved by AI-Driven Review Bot"},
        )

    async def request_changes(
        self, repo_full_name: str, pr_number: int, body: str = ""
    ) -> None:
        """Submit a REQUEST_CHANGES review."""
        await self._request(
            "POST",
            f"/repos/{repo_full_name}/pulls/{pr_number}/reviews",
            json={"event": "REQUEST_CHANGES", "body": body or "Changes requested by AI-Driven Review Bot"},
        )

    async def get_commit_diff(
        self, repo_full_name: str, commit_sha: str
    ) -> PRDiff:
        """Get the diff for a single commit."""
        resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/commits/{commit_sha}",
        )
        data = resp.json()
        files = [
            DiffFile(
                path=f["filename"],
                status=f["status"],
                additions=f.get("additions", 0),
                deletions=f.get("deletions", 0),
                patch=f.get("patch", ""),
            )
            for f in data.get("files", [])
        ]
        return PRDiff(
            files=files,
            total_additions=sum(f.additions for f in files),
            total_deletions=sum(f.deletions for f in files),
        )

    async def list_recent_merges(
        self, repo_full_name: str, limit: int = 10
    ) -> list[dict[str, Any]]:
        """List recently merged PRs as a deployment proxy."""
        resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/pulls",
            params={"state": "closed", "sort": "updated", "direction": "desc", "per_page": limit},
        )
        merged = []
        for pr in resp.json():
            if pr.get("merged_at"):
                merged.append({
                    "number": pr["number"],
                    "title": pr["title"],
                    "merged_at": pr["merged_at"],
                    "author": pr["user"]["login"],
                    "merge_commit_sha": pr.get("merge_commit_sha", ""),
                })
        return merged[:limit]

    async def get_pr_reviewers(
        self, repo_full_name: str, pr_number: int
    ) -> list[PRReviewer]:
        """Merge requested-reviewers (pending) with submitted reviews (latest state per user)."""
        requested_resp, reviews_resp = (
            await self._request(
                "GET", f"/repos/{repo_full_name}/pulls/{pr_number}/requested_reviewers"
            ),
            await self._request(
                "GET", f"/repos/{repo_full_name}/pulls/{pr_number}/reviews"
            ),
        )

        state_map = {
            "APPROVED": "approved",
            "CHANGES_REQUESTED": "changes_requested",
            "COMMENTED": "commented",
            "DISMISSED": "dismissed",
        }

        # Latest review per user
        by_user: dict[str, PRReviewer] = {}
        comment_counts: dict[str, int] = {}
        for rv in reviews_resp.json() or []:
            user = rv.get("user") or {}
            login = user.get("login")
            if not login:
                continue
            if (rv.get("state") or "") == "COMMENTED":
                comment_counts[login] = comment_counts.get(login, 0) + 1
            mapped = state_map.get(rv.get("state") or "", "commented")
            by_user[login] = PRReviewer(
                username=login,
                display_name=user.get("name") or login,
                avatar_url=user.get("avatar_url"),
                is_bot=(user.get("type") == "Bot"),
                state=mapped,
                comment_count=comment_counts.get(login, 0),
            )

        # Pending reviewers (requested but no review yet) — don't overwrite existing
        requested = requested_resp.json() or {}
        for user in (requested.get("users") or []):
            login = user.get("login")
            if not login or login in by_user:
                continue
            by_user[login] = PRReviewer(
                username=login,
                display_name=user.get("name") or login,
                avatar_url=user.get("avatar_url"),
                is_bot=(user.get("type") == "Bot"),
                state="pending",
            )

        return list(by_user.values())

    async def get_pr_checks(
        self, repo_full_name: str, pr_number: int
    ) -> list[PRCheck]:
        """Fetch check-runs for the PR's head commit."""
        pr_resp = await self._request(
            "GET", f"/repos/{repo_full_name}/pulls/{pr_number}"
        )
        sha = (pr_resp.json().get("head") or {}).get("sha")
        if not sha:
            return []

        resp = await self._request(
            "GET",
            f"/repos/{repo_full_name}/commits/{sha}/check-runs",
        )
        runs = (resp.json() or {}).get("check_runs") or []
        checks: list[PRCheck] = []
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
            checks.append(PRCheck(
                name=run.get("name") or "check",
                status=run.get("status") or "queued",
                conclusion=run.get("conclusion"),
                duration_seconds=duration,
                details_url=run.get("html_url"),
            ))
        return checks

    async def get_pr_commits(
        self, repo_full_name: str, pr_number: int
    ) -> list[Commit]:
        """List commits on a PR."""
        resp = await self._request(
            "GET", f"/repos/{repo_full_name}/pulls/{pr_number}/commits"
        )
        out: list[Commit] = []
        for item in resp.json() or []:
            sha = item.get("sha", "")
            commit = item.get("commit") or {}
            author = ((commit.get("author") or {}).get("name")
                      or (item.get("author") or {}).get("login")
                      or "unknown")
            ts_raw = (commit.get("author") or {}).get("date")
            try:
                ts = datetime.fromisoformat(ts_raw.replace("Z", "+00:00")) if ts_raw else datetime.now(timezone.utc)
            except Exception:
                ts = datetime.now(timezone.utc)
            out.append(Commit(
                sha=sha,
                message=(commit.get("message") or "").split("\n", 1)[0],
                author=author,
                timestamp=ts,
                url=item.get("html_url"),
            ))
        return out

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _next_link(response: httpx.Response) -> str | None:
        """Extract the 'next' URL from the Link header if present."""
        link_header = response.headers.get("Link", "")
        for part in link_header.split(","):
            if 'rel="next"' in part:
                url = part.split(";")[0].strip().strip("<>")
                return url
        return None

    @staticmethod
    def _parse_pr(data: dict[str, Any]) -> PullRequest:
        state_map = {"open": PRState.OPEN, "closed": PRState.CLOSED}
        state = state_map.get(data["state"], PRState.OPEN)
        if data.get("merged"):
            state = PRState.MERGED

        created_at = None
        if data.get("created_at"):
            created_at = datetime.fromisoformat(
                data["created_at"].replace("Z", "+00:00")
            )
        updated_at = None
        if data.get("updated_at"):
            updated_at = datetime.fromisoformat(
                data["updated_at"].replace("Z", "+00:00")
            )

        return PullRequest(
            number=data["number"],
            title=data["title"],
            body=data.get("body", ""),
            state=state,
            source_branch=data["head"]["ref"],
            target_branch=data["base"]["ref"],
            author=data["user"]["login"],
            url=data["html_url"],
            created_at=created_at,
            updated_at=updated_at,
        )
