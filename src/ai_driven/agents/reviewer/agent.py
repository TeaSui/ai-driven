"""AI Code Review Bot -- auto-reviews PRs and posts comments."""

from __future__ import annotations

import logging
from typing import Any

from ai_driven.agents.base import BaseAgent
from ai_driven.agents.reviewer.config import ReviewConfig
from ai_driven.claude.prompts.review_agent import REVIEW_AGENT_SYSTEM_PROMPT
from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

# 50 KB threshold for diff truncation
_MAX_DIFF_BYTES = 50 * 1024
# Per-file patch truncation limit when total diff exceeds threshold
_PER_FILE_PATCH_LIMIT = 2048
# Line count threshold for using planning model
_LARGE_DIFF_LINE_THRESHOLD = 500


def _truncate_diff_files(files: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Truncate file patches if total diff text exceeds 50KB."""
    total_size = sum(len((f.get("patch") or "").encode("utf-8")) for f in files)
    if total_size <= _MAX_DIFF_BYTES:
        return files

    truncated = []
    for f in files:
        patch = f.get("patch") or ""
        if len(patch.encode("utf-8")) > _PER_FILE_PATCH_LIMIT:
            f = {**f, "patch": patch[:_PER_FILE_PATCH_LIMIT] + "\n[truncated]"}
        truncated.append(f)
    return truncated


class ReviewAgent(BaseAgent):
    """AI Code Review Bot that reviews pull requests."""

    agent_type = "review"

    def __init__(
        self,
        scm_client: Any = None,
        review_config: ReviewConfig | None = None,
        local_repo_path: str | None = None,
        **kwargs: Any,
    ) -> None:
        self._scm = scm_client
        self._review_config = review_config or ReviewConfig()
        self._local_repo_path = local_repo_path
        super().__init__(**kwargs)

    def _register_tools(self) -> None:
        @self._registry.tool(
            name="github.get_pr_diff",
            description="Fetch the diff of a pull request.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                },
                "required": ["repo_full_name", "pr_number"],
            },
        )
        async def get_pr_diff(repo_full_name: str, pr_number: int) -> dict[str, Any]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            diff = await self._scm.get_pr_diff(repo_full_name, pr_number)
            files = [
                {
                    "path": f.path,
                    "status": f.status,
                    "additions": f.additions,
                    "deletions": f.deletions,
                    "patch": f.patch,
                }
                for f in diff.files
            ]
            files = _truncate_diff_files(files)
            return {
                "files": files,
                "total_additions": diff.total_additions,
                "total_deletions": diff.total_deletions,
            }

        @self._registry.tool(
            name="bitbucket.get_pr_diff",
            description="Fetch the diff of a Bitbucket pull request.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                },
                "required": ["repo_full_name", "pr_number"],
            },
        )
        async def get_bitbucket_pr_diff(repo_full_name: str, pr_number: int) -> dict[str, Any]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            diff = await self._scm.get_pr_diff(repo_full_name, pr_number)
            files = [
                {
                    "path": f.path,
                    "status": f.status,
                    "additions": f.additions,
                    "deletions": f.deletions,
                    "patch": f.patch,
                }
                for f in diff.files
            ]
            files = _truncate_diff_files(files)
            return {
                "files": files,
                "total_additions": diff.total_additions,
                "total_deletions": diff.total_deletions,
            }

        @self._registry.tool(
            name="repo.read_file",
            description="Read a file from the repository for additional context.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "path": {"type": "string"},
                    "ref": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name", "path"],
            },
        )
        async def read_file(
            repo_full_name: str, path: str, ref: str = "main"
        ) -> dict[str, Any]:
            # Try local filesystem first (from CodingAgent clone)
            local_path = await self._resolve_local_path(repo_full_name)
            if local_path:
                import os
                file_path = os.path.join(local_path, path)
                if os.path.isfile(file_path):
                    try:
                        with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
                            content = f.read()
                        return {"path": path, "content": content, "source": "local"}
                    except Exception:
                        pass  # Fall through to SCM

            if not self._scm:
                return {"error": "SCM client not configured"}
            file = await self._scm.read_file(repo_full_name, path, ref)
            return {"path": file.path, "content": file.content}

        @self._registry.tool(
            name="github.post_pr_comment",
            description="Post a review comment on the pull request (general or inline on a specific file/line).",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                    "body": {"type": "string"},
                    "file_path": {"type": "string"},
                    "line": {"type": "integer"},
                },
                "required": ["repo_full_name", "pr_number", "body"],
            },
        )
        async def post_pr_comment(
            repo_full_name: str,
            pr_number: int,
            body: str,
            file_path: str | None = None,
            line: int | None = None,
        ) -> dict[str, str]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            comment = await self._scm.post_pr_comment(
                repo_full_name,
                pr_number,
                body,
                file_path,
                line,
            )
            return {"id": comment.id, "status": "posted"}

        @self._registry.tool(
            name="bitbucket.post_pr_comment",
            description="Post a review comment on a Bitbucket pull request (general or inline on a specific file/line).",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                    "body": {"type": "string"},
                    "file_path": {"type": "string"},
                    "line": {"type": "integer"},
                },
                "required": ["repo_full_name", "pr_number", "body"],
            },
        )
        async def post_bitbucket_pr_comment(
            repo_full_name: str,
            pr_number: int,
            body: str,
            file_path: str | None = None,
            line: int | None = None,
        ) -> dict[str, str]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            comment = await self._scm.post_pr_comment(
                repo_full_name,
                pr_number,
                body,
                file_path,
                line,
            )
            return {"id": comment.id, "status": "posted"}

        @self._registry.tool(
            name="repo.list_files",
            description="List files and directories in a repository path.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "path": {"type": "string", "default": ""},
                    "ref": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name"],
            },
        )
        async def list_repo_files(
            repo_full_name: str, path: str = "", ref: str = "main"
        ) -> list[dict[str, Any]]:
            if not self._scm:
                return [{"error": "SCM client not configured"}]
            entries = await self._scm.list_files(repo_full_name, path, ref)
            return [
                {
                    "path": e.path,
                    "name": e.name,
                    "is_directory": e.is_directory,
                    "size": e.size,
                }
                for e in entries
            ]

        @self._registry.tool(
            name="code.search",
            description="Search for code patterns across the repository.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "query": {"type": "string"},
                },
                "required": ["repo_full_name", "query"],
            },
        )
        async def search_code(
            repo_full_name: str, query: str
        ) -> list[dict[str, Any]]:
            if not self._scm:
                return [{"error": "SCM client not configured"}]
            results = await self._scm.search_code(repo_full_name, query)
            return [{"path": r.path, "content": r.content[:500]} for r in results]

        @self._registry.tool(
            name="github.approve_pr",
            description="Submit an approving review on the pull request. Use when no critical issues are found.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                    "body": {"type": "string", "default": ""},
                },
                "required": ["repo_full_name", "pr_number"],
            },
        )
        async def approve_pr(
            repo_full_name: str, pr_number: int, body: str = ""
        ) -> dict[str, str]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            await self._scm.approve_pr(repo_full_name, pr_number, body)
            return {"status": "approved", "pr_number": str(pr_number)}

        @self._registry.tool(
            name="bitbucket.approve_pr",
            description="Submit an approving review on a Bitbucket pull request. Use when no critical issues are found.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                    "body": {"type": "string", "default": ""},
                },
                "required": ["repo_full_name", "pr_number"],
            },
        )
        async def approve_bitbucket_pr(
            repo_full_name: str, pr_number: int, body: str = ""
        ) -> dict[str, str]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            await self._scm.approve_pr(repo_full_name, pr_number, body)
            return {"status": "approved", "pr_number": str(pr_number)}

        @self._registry.tool(
            name="github.request_changes",
            description="Submit a request-changes review on the pull request. Use when critical issues are found.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                    "body": {"type": "string", "default": ""},
                },
                "required": ["repo_full_name", "pr_number"],
            },
        )
        async def request_changes(
            repo_full_name: str, pr_number: int, body: str = ""
        ) -> dict[str, str]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            await self._scm.request_changes(repo_full_name, pr_number, body)
            return {"status": "changes_requested", "pr_number": str(pr_number)}

        @self._registry.tool(
            name="bitbucket.request_changes",
            description="Submit a request-changes review on a Bitbucket pull request. Use when critical issues are found.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                    "body": {"type": "string", "default": ""},
                },
                "required": ["repo_full_name", "pr_number"],
            },
        )
        async def request_bitbucket_changes(
            repo_full_name: str, pr_number: int, body: str = ""
        ) -> dict[str, str]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            await self._scm.request_changes(repo_full_name, pr_number, body)
            return {"status": "changes_requested", "pr_number": str(pr_number)}

    async def _resolve_local_path(self, repo_full_name: str) -> str | None:
        """Check Redis for a local clone path set by CodingAgent."""
        if self._local_repo_path:
            return self._local_repo_path
        try:
            redis = await self._get_redis_client()
            if redis:
                path = await redis.get(f"repo_clone:{repo_full_name}")
                if path and path != "pending":
                    import os
                    if os.path.isdir(path):
                        return path
        except Exception:
            pass
        return None

    async def _maybe_enable_rag(self, repo_full_name: str, parent_task_id: str | None = None) -> None:
        """Register RAG search tool if the repo was indexed by CodingAgent.

        Looks up context first by ``parent_task_id`` (set when ReviewAgent is
        spawned by a CodingAgent task), then falls back to ``repo_full_name``.
        """
        if not repo_full_name and not parent_task_id:
            return
        try:
            import json as _json
            redis = await self._get_redis_client()
            if not redis:
                return

            ctx_raw: str | None = None
            # Prefer parent_task_id lookup so the correct indexed state is found
            # even when multiple repos share the same full_name across tasks.
            if parent_task_id:
                ctx_raw = await redis.get(f"repo_context:{parent_task_id}")
            if not ctx_raw and repo_full_name:
                ctx_raw = await redis.get(f"repo_context:{repo_full_name}")
            if not ctx_raw:
                return
            ctx = _json.loads(ctx_raw)
            if ctx.get("indexed"):
                self._register_rag_tool()
                logger.info(
                    "ReviewAgent: RAG enabled for %s (%d chunks)",
                    ctx.get("repo_full_name", repo_full_name),
                    ctx.get("chunks", 0),
                )
        except Exception:
            pass

    def _register_rag_tool(self) -> None:
        """Register semantic code search tool for ReviewAgent."""
        if "code.semantic_search" in self._registry.tool_names:
            return

        @self._registry.tool(
            name="code.semantic_search",
            description=(
                "Search the repository code semantically using natural language. "
                "Returns the most relevant code chunks ranked by hybrid vector + keyword similarity."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Natural language search query"},
                    "repo_full_name": {"type": "string", "description": "Repository full name, e.g. 'owner/repo'"},
                    "top_k": {"type": "integer", "default": 10},
                },
                "required": ["query", "repo_full_name"],
            },
        )
        async def semantic_search_code(
            query: str, repo_full_name: str, top_k: int = 10,
        ) -> list[dict[str, Any]]:
            try:
                from ai_driven.db.postgres.session import async_session_factory
                from ai_driven.rag.retriever import CodeRetriever
                from sqlalchemy import text

                async with async_session_factory() as session:
                    result = await session.execute(
                        text("SELECT id FROM repositories WHERE full_name = :name"),
                        {"name": repo_full_name},
                    )
                    row = result.first()
                    if not row:
                        return [{"error": f"Repository '{repo_full_name}' not indexed."}]
                    retriever = CodeRetriever(session)
                    results = await retriever.search(query, row.id, top_k=top_k)
                    return [
                        {"file_path": r.file_path, "content": r.content, "score": round(r.score, 4), "chunk_index": r.chunk_index}
                        for r in results
                    ]
            except Exception as e:
                logger.warning("RAG search failed: %s", e)
                return [{"error": f"Semantic search unavailable: {e}"}]

    async def run(
        self,
        task_input: dict[str, Any],
        *,
        task_id: str | None = None,
        model: str | None = None,
        max_iterations: int | None = None,
        use_thinking: bool = False,
        stop_sequences: list[str] | None = None,
    ) -> Any:
        """Override to enforce JSON output by stopping after the closing code fence."""
        # Register MCP tools (async, must be done here)
        await self._register_mcp_tools()

        # Enable RAG if CodingAgent has indexed this repo.
        # Accept parent_task_id to look up repo context by the originating task.
        repo_full_name = task_input.get("repo_full_name", "")
        parent_task_id: str | None = task_input.get("parent_task_id")
        await self._maybe_enable_rag(repo_full_name, parent_task_id=parent_task_id)

        result = await super().run(
            task_input,
            task_id=task_id,
            model=model or self._select_model(task_input),
            max_iterations=max_iterations,
            use_thinking=use_thinking,
            stop_sequences=stop_sequences if stop_sequences is not None else ["```"],
        )

        # Persist a Review row so the Code Review tab can surface this run.
        # Do this after super().run() so the conversation + output text are
        # already populated. Failures here must NOT fail the task — the PR
        # comments have already been posted and the agent did its job.
        if task_id:
            try:
                await self._persist_review_row(task_id, task_input, result)
            except Exception:
                logger.exception(
                    "ReviewAgent: failed to persist Review row (non-fatal); "
                    "task %s will still appear in the task list but not in "
                    "the Code Review tab.", task_id,
                )

        return result

    async def _persist_review_row(
        self, task_id: str, task_input: dict[str, Any], result: Any
    ) -> None:
        """Harvest the agent output + tool calls and write a Review row."""
        import json as _json
        import re
        import uuid as _uuid
        from ai_driven.db.postgres.session import async_session_factory
        from ai_driven.db.postgres.repository import ReviewRepository

        repo_full_name = task_input.get("repo_full_name", "")
        raw_pr = task_input.get("pr_number")

        # pr_number must be an int. New-Run UI sometimes sends a Jira key
        # ("KAN-10") which is nonsense here — refuse to persist in that case
        # rather than silently mislabeling the Review row. The agent may
        # still have posted comments to whatever number it extracted; that's
        # a separate UI-validation bug.
        pr_number: int | None = None
        if isinstance(raw_pr, int):
            pr_number = raw_pr
        elif isinstance(raw_pr, str):
            # Accept pure-int strings; reject anything with letters.
            if raw_pr.isdigit():
                pr_number = int(raw_pr)
            else:
                logger.warning(
                    "ReviewAgent: pr_number=%r is not an integer; "
                    "skipping Review persistence to avoid bad data. "
                    "The UI should send int, not a Jira key.", raw_pr,
                )
                return

        if not repo_full_name or pr_number is None:
            logger.warning(
                "ReviewAgent: missing repo_full_name / pr_number; skipping "
                "Review persistence. repo=%r pr=%r",
                repo_full_name, raw_pr,
            )
            return

        # Parse the model's structured JSON output. Prompt asks for
        #   { "verdict": "approve|request_changes|comment",
        #     "summary": "...",
        #     "findings": [ {severity, category, file_path, line, body, ...}, ... ],
        #     "positive_highlights": [...] }
        verdict: str = "commented"
        summary: str = ""
        comments: list[dict[str, Any]] = []
        output_text = getattr(result, "output", "") or ""

        # Strip a leading code fence if present.
        m = re.search(r"\{.*\}", output_text, re.DOTALL)
        if m:
            try:
                parsed = _json.loads(m.group(0))
                summary = str(parsed.get("summary") or "")[:2000]
                raw_verdict = str(parsed.get("verdict") or "").lower()
                # Normalize to values the Review model + UI expect.
                if raw_verdict in ("approve", "approved"):
                    verdict = "approved"
                elif raw_verdict in (
                    "request_changes", "changes_requested", "changes"
                ):
                    verdict = "changes_requested"
                else:
                    verdict = "commented"
                # The prompt's JSON schema uses various key names depending
                # on model + prompt drift. Accept findings / comments / issues
                # so we don't silently drop data.
                findings_list: list[Any] = []
                for k in ("findings", "comments", "issues"):
                    v = parsed.get(k)
                    if isinstance(v, list):
                        findings_list = v
                        break
                for f in findings_list:
                    if not isinstance(f, dict):
                        continue
                    comments.append({
                        "file_path": f.get("file_path") or f.get("path") or f.get("file") or "",
                        "line_number": f.get("line") or f.get("line_number"),
                        "severity": (f.get("severity") or "info").lower(),
                        "category": (f.get("category") or "correctness").lower(),
                        "confidence": f.get("confidence", 0.8),
                        "title": f.get("title") or "",
                        "body": f.get("body") or f.get("message") or "",
                        "suggested_code": f.get("suggested_code") or f.get("suggestedCode"),
                    })
            except Exception:
                logger.info(
                    "ReviewAgent: could not parse final JSON; Review row "
                    "will have an empty comments_json. Summary will fall "
                    "back to the raw output."
                )
                summary = output_text[:2000]

        pr_url = f"https://github.com/{repo_full_name}/pull/{pr_number}"

        async with async_session_factory() as session:
            repo_ = ReviewRepository(session)
            await repo_.create(
                id=_uuid.uuid4(),
                task_id=_uuid.UUID(task_id),
                pr_url=pr_url,
                pr_number=pr_number,
                repo_full_name=repo_full_name,
                status=verdict,
                summary=summary,
                comments_json=comments,
                issues_found=len(comments),
                critical_issues=sum(
                    1 for c in comments
                    if c.get("severity") in ("critical", "high", "warn", "warning")
                ),
            )
            await session.commit()
            logger.info(
                "ReviewAgent: persisted Review row for task=%s pr=%s/%s "
                "verdict=%s findings=%d",
                task_id, repo_full_name, pr_number, verdict, len(comments),
            )

    async def _register_mcp_tools(self) -> None:
        """Register MCP tools if MCP is enabled."""
        settings = get_settings()
        if not settings.mcp_enabled:
            return
        try:
            from ai_driven.mcp.tool_bridge import register_mcp_tools
            count = await register_mcp_tools(self._registry)
            if count:
                logger.info("ReviewAgent: registered %d MCP tools", count)
        except Exception as e:
            logger.warning("MCP tool registration failed: %s", e)

    def _build_mcp_tools_section(self) -> str:
        """Build an XML section listing available MCP integration tools."""
        mcp_tools = [n for n in self._registry.tool_names if n.startswith("mcp_")]
        if not mcp_tools:
            return ""
        lines = ["<available_integrations>"]
        for name in mcp_tools:
            tool_def = self._registry.get_tool(name)
            desc = tool_def.schema.get("description", "") if tool_def else ""
            lines.append(f"- {name}: {desc}")
        lines.append("</available_integrations>")
        return "\n".join(lines)

    def _get_system_prompt(self, context: dict[str, Any]) -> str:
        base_prompt = REVIEW_AGENT_SYSTEM_PROMPT
        team_section = self._review_config.to_prompt_section()
        prompt = f"{base_prompt}\n\n{team_section}"
        mcp_section = self._build_mcp_tools_section()
        if mcp_section:
            prompt += f"\n\n{mcp_section}"
        return prompt

    def _get_tool_names(self) -> list[str]:
        return self._registry.tool_names

    def _select_model(self, task_input: dict[str, Any]) -> str:
        """Select model based on diff size -- use planning model for large diffs."""
        settings = get_settings()
        diff_lines = task_input.get("diff_lines", 0)
        if diff_lines > _LARGE_DIFF_LINE_THRESHOLD:
            logger.info(
                "Large diff (%d lines) -- using planning model", diff_lines
            )
            return settings.claude_planning_model
        return settings.claude_default_model

    def _build_initial_message(self, task_input: dict[str, Any]) -> str:
        repo = task_input.get("repo_full_name", "")
        pr_number = task_input.get("pr_number", "")
        pr_title = task_input.get("pr_title", "")
        pr_description = task_input.get("pr_description", "")
        scm_type = task_input.get("scm_type", "github")

        # Provider-specific tool names for the SCM-write parts of the review
        # workflow. The repo is already registered with an scm_type; using the
        # matching tool keeps telemetry honest and routes correctly even if
        # the scm_client fallback chain ever changes.
        if scm_type == "bitbucket":
            diff_tool = "bitbucket.get_pr_diff"
            comment_tool = "bitbucket.post_pr_comment"
            approve_tool = "bitbucket.approve_pr"
            changes_tool = "bitbucket.request_changes"
        else:
            diff_tool = "github.get_pr_diff"
            comment_tool = "github.post_pr_comment"
            approve_tool = "github.approve_pr"
            changes_tool = "github.request_changes"

        parts = [
            f"Please review pull request #{pr_number} in repository {repo} "
            f"(hosted on {scm_type}).",
        ]

        if pr_title:
            parts.append(f"\nPR Title: {pr_title}")
        if pr_description:
            parts.append(f"PR Description: {pr_description}")

        parts.append(
            f"\nWorkflow:"
            f"\n1. Fetch the PR diff using {diff_tool}"
            f"\n2. Analyze each changed file against the review checklist"
            f"\n3. Use repo.read_file or repo.list_files for additional context when needed"
            f"\n4. Use code.search to find related patterns in the codebase"
            f"\n5. Post inline findings with {comment_tool}; submit the final review with {approve_tool} or {changes_tool}"
            f"\n6. Respond with the structured JSON review result"
        )

        return "\n".join(parts)
