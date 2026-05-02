"""AI Coding Agent — takes Jira tickets, generates code, creates PRs."""

from __future__ import annotations

import logging
import uuid
from typing import Any

from ai_driven.agents._shared.repo_tools import (
    build_repo_overview,
    list_files_batched,
    read_files_batched,
)
from ai_driven.agents.base import BaseAgent
from ai_driven.agents.coding.prompt_composer import compose_coding_prompt
from ai_driven.agents.coding.sandbox import BaseSandboxExecutor, create_sandbox
from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.orchestrator.repository import ArtifactRepository
from ai_driven.claude.prompts.coding_agent import CODING_AGENT_SYSTEM_PROMPT
from ai_driven.config import get_settings
from ai_driven.db.postgres.repository import TaskRepository
from ai_driven.db.postgres.session import async_session_factory
from ai_driven.scm.exceptions import SCMValidationError

logger = logging.getLogger(__name__)


class CodingAgent(BaseAgent):
    """AI Coding Agent that implements features from Jira tickets."""

    agent_type = "coding"

    def __init__(
        self,
        jira_client: Any = None,
        scm_client: Any = None,
        sandbox: BaseSandboxExecutor | None = None,
        **kwargs: Any,
    ) -> None:
        self._jira = jira_client
        self._scm = scm_client
        self._sandbox = sandbox or create_sandbox()
        # Phase 2 prompt-composition state. Populated at the top of run()
        # from the task_artifacts table (when orchestrator_mode != "off")
        # and consumed synchronously inside _get_system_prompt. Default
        # empty-dict means the base prompt is used unchanged.
        self._prior_artifacts: dict[str, Artifact] = {}
        super().__init__(**kwargs)

    def _register_tools(self) -> None:
        """Register coding-specific tools."""

        @self._registry.tool(
            name="jira.get_issue",
            description="Fetch full Jira issue details including description, acceptance criteria, and comments.",
            input_schema={
                "type": "object",
                "properties": {"issue_key": {"type": "string", "description": "Jira issue key, e.g. 'PROJ-123'"}},
                "required": ["issue_key"],
            },
        )
        async def get_jira_issue(issue_key: str) -> dict[str, Any]:
            if not self._jira:
                return {"error": "Jira client not configured"}
            issue = await self._jira.get_issue(issue_key)
            return {
                "key": issue.key,
                "summary": issue.summary,
                "description": issue.description,
                "status": issue.status,
                "acceptance_criteria": issue.acceptance_criteria,
                "labels": issue.labels,
                "subtasks": issue.subtasks,
            }

        @self._registry.tool(
            name="jira.add_comment",
            description="Add a comment to a Jira issue to report progress.",
            input_schema={
                "type": "object",
                "properties": {
                    "issue_key": {"type": "string"},
                    "body": {"type": "string", "description": "Comment text"},
                },
                "required": ["issue_key", "body"],
            },
        )
        async def add_jira_comment(issue_key: str, body: str) -> dict[str, str]:
            if not self._jira:
                return {"error": "Jira client not configured"}
            comment = await self._jira.add_comment(issue_key, body)
            return {"id": comment.id, "status": "posted"}

        @self._registry.tool(
            name="repo.list_files",
            description=(
                "List files and directories in one or more repository paths. "
                "Accepts a single `path` OR `paths` (a list). Supports recursive "
                "traversal via `recursive=true` + optional `max_depth`, and glob "
                "filtering via `pattern` (e.g. '**/*.py'). Use recursive + pattern "
                "to avoid dozens of single-path calls."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "path": {"type": "string", "default": ""},
                    "paths": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": (
                            "List of paths — alternative to `path`. "
                            "Exactly one of path/paths should be set."
                        ),
                    },
                    "recursive": {
                        "type": "boolean",
                        "default": False,
                        "description": "Walk subdirectories under each path.",
                    },
                    "max_depth": {
                        "type": "integer",
                        "default": 3,
                        "description": (
                            "Max recursion depth when `recursive=true`. "
                            "Capped at 5 to bound cost."
                        ),
                    },
                    "pattern": {
                        "type": "string",
                        "description": (
                            "Optional glob pattern filter (fnmatch syntax), "
                            "e.g. '**/*.py' or 'src/**/test_*.go'."
                        ),
                    },
                    "ref": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name"],
            },
        )
        async def list_repo_files(
            repo_full_name: str,
            path: str = "",
            paths: list[str] | None = None,
            recursive: bool = False,
            max_depth: int = 3,
            pattern: str | None = None,
            ref: str = "main",
        ) -> list[dict[str, Any]]:
            if not self._scm:
                return [{"error": "SCM client not configured"}]
            return await list_files_batched(
                self._scm, repo_full_name,
                paths=paths if paths is not None else [path],
                recursive=recursive, max_depth=max_depth,
                pattern=pattern, ref=ref,
            )

        @self._registry.tool(
            name="repo.read_file",
            description=(
                "Read the contents of one or more files from the repository. "
                "Accepts a single `path` OR `paths` (a list). When `paths` is "
                "used, returns a list of file objects — call this once with a "
                "batch instead of N times with single paths."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "path": {"type": "string"},
                    "paths": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": (
                            "List of file paths — alternative to `path`. "
                            "Exactly one of path/paths should be set."
                        ),
                    },
                    "ref": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name"],
            },
        )
        async def read_file(
            repo_full_name: str,
            path: str | None = None,
            paths: list[str] | None = None,
            ref: str = "main",
        ) -> dict[str, Any] | list[dict[str, Any]]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            target_paths = paths if paths is not None else ([path] if path else [])
            if not target_paths:
                return {"error": "must provide `path` or non-empty `paths`"}
            results = await read_files_batched(self._scm, repo_full_name, target_paths, ref)
            # Preserve single-path shape for backward compat; return list for batch.
            return results[0] if paths is None and len(results) == 1 else results

        @self._registry.tool(
            name="repo.overview",
            description=(
                "Return a compact directory tree of the repo plus top-level "
                "manifests (package.json / pyproject.toml / go.mod / Cargo.toml / "
                "etc.). Call this ONCE at the EXPLORE phase to see the whole "
                "codebase layout in a single tool call — skip manual directory-by-"
                "directory walks."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "max_depth": {
                        "type": "integer",
                        "default": 3,
                        "description": "How deep to walk the tree. 3 is usually enough; 5 max.",
                    },
                    "ref": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name"],
            },
        )
        async def repo_overview(
            repo_full_name: str, max_depth: int = 3, ref: str = "main",
        ) -> dict[str, Any]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            return await build_repo_overview(self._scm, repo_full_name, max_depth, ref)

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
        async def search_code(repo_full_name: str, query: str) -> list[dict[str, Any]]:
            if not self._scm:
                return [{"error": "SCM client not configured"}]
            results = await self._scm.search_code(repo_full_name, query)
            return [{"path": r.path, "content": r.content[:500]} for r in results]

        @self._registry.tool(
            name="github.create_pr",
            description="Create a new pull request after code changes are committed and pushed.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "title": {"type": "string"},
                    "body": {"type": "string"},
                    "source_branch": {"type": "string"},
                    "target_branch": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name", "title", "body", "source_branch"],
            },
        )
        async def create_pull_request(
            repo_full_name: str, title: str, body: str,
            source_branch: str, target_branch: str = "main",
        ) -> dict[str, Any]:
            return await self._persist_and_open_pr(
                "github",
                repo_full_name=repo_full_name,
                title=title,
                body=body,
                source_branch=source_branch,
                target_branch=target_branch,
            )

        @self._registry.tool(
            name="bitbucket.create_pr",
            description="Create a new pull request on Bitbucket after code changes are committed and pushed.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "title": {"type": "string"},
                    "body": {"type": "string"},
                    "source_branch": {"type": "string"},
                    "target_branch": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name", "title", "body", "source_branch"],
            },
        )
        async def create_bitbucket_pr(
            repo_full_name: str, title: str, body: str,
            source_branch: str, target_branch: str = "main",
        ) -> dict[str, Any]:
            return await self._persist_and_open_pr(
                "bitbucket",
                repo_full_name=repo_full_name,
                title=title,
                body=body,
                source_branch=source_branch,
                target_branch=target_branch,
            )

        @self._registry.tool(
            name="jira.update_status",
            description="Transition a Jira issue to a new status.",
            input_schema={
                "type": "object",
                "properties": {
                    "issue_key": {"type": "string", "description": "Jira issue key, e.g. 'PROJ-123'"},
                    "transition_name": {"type": "string", "description": "Target status name, e.g. 'In Progress'"},
                },
                "required": ["issue_key", "transition_name"],
            },
        )
        async def update_jira_status(issue_key: str, transition_name: str) -> dict[str, str]:
            if not self._jira:
                return {"error": "Jira client not configured"}
            await self._jira.transition_issue(issue_key, transition_name)
            return {"status": "transitioned", "to": transition_name}

        @self._registry.tool(
            name="file.write",
            description="Create or update a file in the repository and commit the change.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "path": {"type": "string", "description": "File path within the repository"},
                    "content": {"type": "string", "description": "Full file content to write"},
                    "commit_message": {"type": "string", "description": "Git commit message"},
                    "branch": {"type": "string", "description": "Branch to commit to"},
                },
                "required": ["repo_full_name", "path", "content", "commit_message", "branch"],
            },
        )
        async def write_file(
            repo_full_name: str, path: str, content: str,
            commit_message: str, branch: str,
        ) -> dict[str, str]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            await self._scm.create_or_update_file(
                repo_full_name, path, content, commit_message, branch,
            )
            return {"status": "committed", "path": path}

        @self._registry.tool(
            name="file.write_batch",
            description=(
                "Create or update MULTIPLE files in the repository in ONE commit. "
                "Strongly prefer this over multiple file.write calls when the "
                "changes are part of the same logical unit (e.g. adding a feature "
                "that touches docs + frontend + backend). Produces one clean "
                "commit instead of N."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "files": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "path": {"type": "string"},
                                "content": {"type": "string"},
                            },
                            "required": ["path", "content"],
                        },
                        "minItems": 1,
                    },
                    "commit_message": {"type": "string"},
                    "branch": {"type": "string"},
                },
                "required": ["repo_full_name", "files", "commit_message", "branch"],
            },
        )
        async def write_files_batch(
            repo_full_name: str,
            files: list[dict[str, str]],
            commit_message: str,
            branch: str,
        ) -> dict[str, Any]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            await self._scm.create_or_update_files_batch(
                repo_full_name, files, commit_message, branch,
            )
            return {
                "status": "committed",
                "count": len(files),
                "paths": [f["path"] for f in files],
            }

        @self._registry.tool(
            name="git.create_branch",
            description="Create a new branch in the repository.",
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "branch_name": {"type": "string", "description": "Name of the new branch"},
                    "from_ref": {"type": "string", "default": "main", "description": "Base ref to branch from"},
                },
                "required": ["repo_full_name", "branch_name"],
            },
        )
        async def create_branch(
            repo_full_name: str, branch_name: str, from_ref: str = "main",
        ) -> dict[str, str]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            await self._scm.create_branch(repo_full_name, branch_name, from_ref)
            return {"status": "created", "branch": branch_name}

        @self._registry.tool(
            name="sandbox.execute",
            description="Run a shell command in the sandboxed environment (build, test, lint).",
            input_schema={
                "type": "object",
                "properties": {
                    "command": {"type": "string"},
                    "working_dir": {"type": "string", "default": "."},
                    "timeout_seconds": {"type": "integer", "default": 120},
                },
                "required": ["command"],
            },
        )
        async def execute_in_sandbox(
            command: str, working_dir: str = ".", timeout_seconds: int = 120,
        ) -> dict[str, Any]:
            result = await self._sandbox.run_command(
                command, working_dir, timeout_seconds
            )
            return {
                "stdout": result.stdout,
                "stderr": result.stderr,
                "exit_code": result.exit_code,
            }

        # Register RAG semantic search tool if enabled
        self._register_rag_tool()

    def _register_rag_tool(self, force_enable: bool = False) -> None:
        """Register semantic code search tool if RAG is enabled."""
        settings = get_settings()
        if not settings.rag_enabled and not force_enable:
            return

        @self._registry.tool(
            name="code.semantic_search",
            description=(
                "Search the repository code semantically using natural language. "
                "Returns the most relevant code chunks ranked by hybrid vector + keyword similarity. "
                "Use for finding implementations, patterns, or understanding how something works."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Natural language search query, e.g. 'authentication middleware' or 'database connection setup'",
                    },
                    "repo_full_name": {"type": "string", "description": "Repository full name, e.g. 'owner/repo'"},
                    "top_k": {"type": "integer", "default": 10, "description": "Number of results to return"},
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

                async with async_session_factory() as session:
                    # Look up repo_id from repo_full_name
                    from sqlalchemy import select, text
                    result = await session.execute(
                        text("SELECT id FROM repositories WHERE full_name = :name"),
                        {"name": repo_full_name},
                    )
                    row = result.first()
                    if not row:
                        return [{"error": f"Repository '{repo_full_name}' not indexed. Add it in Admin first."}]

                    retriever = CodeRetriever(session)
                    results = await retriever.search(query, row.id, top_k=top_k)
                    return [
                        {
                            "file_path": r.file_path,
                            "content": r.content,
                            "score": round(r.score, 4),
                            "chunk_index": r.chunk_index,
                        }
                        for r in results
                    ]
            except Exception as e:
                logger.warning("RAG search failed: %s", e)
                return [{"error": f"Semantic search unavailable: {e}"}]

    async def _register_mcp_tools(self) -> None:
        """Register MCP tools if MCP is enabled."""
        settings = get_settings()
        if not settings.mcp_enabled:
            return
        try:
            from ai_driven.mcp.tool_bridge import register_mcp_tools
            count = await register_mcp_tools(self._registry)
            if count:
                logger.info("CodingAgent: registered %d MCP tools", count)
        except Exception as e:
            logger.warning("MCP tool registration failed: %s", e)

    async def _auto_index_repo(self, repo_full_name: str, task_id: str | None) -> bool:
        """Auto-index repo for RAG if it exceeds the file threshold.

        Returns True if RAG was activated (tool registered).
        """
        settings = get_settings()
        if not settings.rag_auto_enable:
            return False
        if settings.rag_enabled:
            return False  # already enabled globally

        if not self._scm or not repo_full_name:
            return False

        try:
            from ai_driven.db.postgres.session import async_session_factory
            from sqlalchemy import text

            async with async_session_factory() as session:
                # Look up repo in DB
                result = await session.execute(
                    text("SELECT id FROM repositories WHERE full_name = :name"),
                    {"name": repo_full_name},
                )
                row = result.first()
                if not row:
                    return False
                repo_id = row.id

                # Check if already indexed (count existing embeddings)
                count_result = await session.execute(
                    text("SELECT COUNT(*) FROM code_embeddings WHERE repo_id = :rid"),
                    {"rid": repo_id},
                )
                existing_count = count_result.scalar() or 0
                if existing_count > 0:
                    logger.info("Repo %s already indexed (%d chunks), enabling RAG", repo_full_name, existing_count)
                    self._register_rag_tool(force_enable=True)
                    return True

                # Check file count via SCM
                try:
                    entries = await self._scm.list_files(repo_full_name, "", "main")
                    file_count = sum(1 for e in entries if not e.is_directory)
                except Exception:
                    file_count = 0

                if file_count < settings.rag_auto_enable_file_threshold:
                    return False

                # Publish indexing start event
                self._task_id = task_id
                await self._publish_event("rag_indexing_start", {
                    "repo": repo_full_name,
                    "estimated_files": file_count,
                })

                # Index the repo
                from ai_driven.rag.indexer import CodeIndexer

                # We need a local clone path — check Redis
                redis = await self._get_redis_client()
                clone_path = None
                if redis:
                    clone_path = await redis.get(f"repo_clone:{repo_full_name}")
                    if clone_path == "pending":
                        clone_path = None

                if not clone_path:
                    logger.info("No local clone available for auto-indexing %s", repo_full_name)
                    return False

                indexer = CodeIndexer(session)
                chunk_count = await indexer.index_repo(clone_path, repo_id)
                await session.commit()

                await self._publish_event("rag_indexing_complete", {
                    "repo": repo_full_name,
                    "chunks": chunk_count,
                })

                logger.info("Auto-indexed %s: %d chunks", repo_full_name, chunk_count)
                self._register_rag_tool(force_enable=True)

                # Store indexing status in Redis for ReviewAgent
                if redis:
                    import json
                    ctx_payload = json.dumps({
                        "repo_full_name": repo_full_name,
                        "clone_path": clone_path,
                        "indexed": True,
                        "chunks": chunk_count,
                    })
                    await redis.set(f"repo_context:{repo_full_name}", ctx_payload, ex=86400)
                    # Also key by task_id so ReviewAgent can look up via parent_task_id
                    if task_id:
                        await redis.set(f"repo_context:{task_id}", ctx_payload, ex=86400)

                return True

        except Exception:
            logger.warning("Auto-indexing failed for %s", repo_full_name, exc_info=True)
            return False

    # ------------------------------------------------------------------
    # PR persistence + crash-safe idempotency (T19b)
    # ------------------------------------------------------------------

    @staticmethod
    def _detail_says_pr_already_exists(detail: dict | None) -> bool:
        """Return True iff an SCMValidationError.detail signals 'PR already exists'.

        Covers BOTH providers:

        GitHub (422 from POST /repos/{r}/pulls) shape:
          {"message": "Validation Failed",
           "errors": [{"message": "A pull request already exists for user:branch",
                       "resource": "PullRequest", ...}]}

        Bitbucket (400/422 from POST /repositories/{r}/pullrequests) shape:
          {"type": "error",
           "error": {"message": "...pull request... already exists..."}}

        Deliberately substring-checks JSON-body fields rather than stringifying
        the exception and grepping str(e) — that approach was fragile and was
        called out in the T19b red-team. Non-matching 422s propagate so a
        schema error / title-too-long / etc. is NOT silently swallowed.
        """
        if not isinstance(detail, dict):
            return False
        for err in detail.get("errors") or []:
            msg = (err or {}).get("message") if isinstance(err, dict) else None
            if isinstance(msg, str) and "already exists" in msg.lower():
                return True
        bb_msg = ((detail.get("error") or {}).get("message")
                  if isinstance(detail.get("error"), dict) else None)
        if isinstance(bb_msg, str) and "already exists" in bb_msg.lower():
            return True
        top_msg = detail.get("message")
        if isinstance(top_msg, str) and "already exists" in top_msg.lower():
            # Final fallback — some Bitbucket/GitHub error envelopes put it
            # at the top level. Only trust this when no structured errors[]
            # was present (we've already checked those above).
            return True
        return False

    async def _load_persisted_pr_ref(self) -> dict[str, Any] | None:
        """Return ``{pr_number, pr_url}`` if already persisted for this task, else None.

        Read-side of the crash-safe idempotency guard: if SQS redelivers a
        coding task after the worker died post-PR-create, we must not call
        ``scm.create_pull_request`` a second time. We detect that by reading
        ``Task.output_data.pr_number`` first.
        """
        if not self._task_id:
            return None
        try:
            async with async_session_factory() as session:
                repo = TaskRepository(session)
                task = await repo.get_by_id(uuid.UUID(self._task_id))
                if task is None:
                    return None
                out = task.output_data or {}
                if out.get("pr_number") is not None:
                    return {
                        "pr_number": out.get("pr_number"),
                        "pr_url": out.get("pr_url"),
                    }
        except Exception:
            logger.warning(
                "coding_agent.load_persisted_pr_ref_failed",
                exc_info=True,
            )
        return None

    async def _persist_pr_ref(self, pr_number: int, pr_url: str) -> None:
        """Write ``{pr_number, pr_url}`` into ``Task.output_data`` atomically.

        One patch, two keys, one UPDATE — never split. T22 (QA) will need to
        read this back; partial writes (pr_number without pr_url, or vice
        versa) would force QA to handle extra edge cases.

        DB errors are logged and swallowed. We already have a successful PR
        on the SCM side — failing the tool call would mean the model thinks
        it has to retry PR creation, which would then hit the "already
        exists" recovery path. Letting the tool return the PR ref and simply
        losing persistence is the less-bad failure mode: QA won't get the
        PR ref but the PR itself still exists and is linkable via the SCM
        UI.
        """
        if not self._task_id:
            return
        try:
            async with async_session_factory() as session:
                repo = TaskRepository(session)
                await repo.update_output_data(
                    uuid.UUID(self._task_id),
                    {"pr_number": pr_number, "pr_url": pr_url},
                )
                await session.commit()
        except Exception:
            logger.warning(
                "coding_agent.persist_pr_ref_failed pr_number=%s",
                pr_number,
                exc_info=True,
            )

    async def _persist_and_open_pr(
        self,
        scm_kind: str,
        *,
        repo_full_name: str,
        title: str,
        body: str,
        source_branch: str,
        target_branch: str,
    ) -> dict[str, Any]:
        """Shared implementation for ``github.create_pr`` and ``bitbucket.create_pr``.

        Order of operations:
          1. Check ``Task.output_data.pr_number`` — if set, this is an SQS
             redelivery or a retried Claude tool call. Return the persisted
             ref and skip the SCM API entirely.
          2. Call ``scm.create_pull_request``. On a plain 200, persist both
             ``pr_number`` and ``pr_url`` as one atomic patch.
          3. If the create raises ``SCMValidationError`` whose structured
             detail indicates "pull request already exists", call
             ``find_pr_by_branch`` to recover the PR number + URL, persist,
             and return. If recovery fails, re-raise the original error —
             something else is wrong.
          4. Any other error (401, 403, 500, validation errors NOT about
             duplicate PRs) propagates unchanged. We catch only the
             structured already-exists signal.

        ``scm_kind`` is used for logging/observability; the self._scm client
        is chosen by the caller (CodingAgent is constructed with a single
        scm_client per run, not per tool).
        """
        if not self._scm:
            return {"error": "SCM client not configured"}

        persisted = await self._load_persisted_pr_ref()
        if persisted is not None:
            logger.info(
                "coding_agent.pr_idempotent_skip scm=%s task=%s pr=%s",
                scm_kind, self._task_id, persisted.get("pr_number"),
            )
            return {
                "number": persisted.get("pr_number"),
                "url": persisted.get("pr_url"),
                "title": title,
                "idempotent": True,
            }

        try:
            pr = await self._scm.create_pull_request(
                repo_full_name, title, body, source_branch, target_branch,
            )
        except SCMValidationError as exc:
            if not self._detail_says_pr_already_exists(exc.detail):
                raise
            # 422 "already exists" — recover the existing PR ref.
            try:
                found = await self._scm.find_pr_by_branch(
                    repo_full_name, source_branch
                )
            except Exception:
                logger.warning(
                    "coding_agent.find_pr_by_branch_failed scm=%s branch=%s",
                    scm_kind, source_branch, exc_info=True,
                )
                raise exc
            if found is None:
                # The SCM said "already exists" but we can't find it. Don't
                # lie to the caller — re-raise so the run fails loudly rather
                # than silently returning a bogus PR ref.
                raise exc
            pr_number, pr_url = found
            await self._persist_pr_ref(pr_number, pr_url)
            return {
                "number": pr_number,
                "url": pr_url,
                "title": title,
                "recovered": True,
            }

        await self._persist_pr_ref(pr.number, pr.url)
        return {"number": pr.number, "url": pr.url, "title": pr.title}

    async def run(
        self,
        task_input: dict[str, Any],
        *,
        task_id: str | None = None,
        model: str | None = None,
        max_iterations: int | None = None,
        use_thinking: bool = False,
        stop_sequences: list[str] | None = None,
        initial_cost: float = 0.0,
    ) -> Any:
        """Enable extended thinking and register async tools before running.

        ``initial_cost`` is passed through to ``BaseAgent.run`` so the T-A2
        cost-ceiling-compounding path (consumer.py seeds it from
        ``sum(task_artifacts.cost_usd)``) actually reaches the ceiling check.
        Without this forward, CodingAgent silently dropped the kwarg with
        a TypeError (smoke-tested 2026-05-01 on KAN-17).
        """
        # Register MCP tools (async, must be done here)
        await self._register_mcp_tools()

        # --- Phase 2 prompt composition: load specialist artifacts ----------
        # Sync/async bridge: _get_system_prompt(context) is a SYNC method
        # invoked from BaseAgent.run (base.py:375). We cannot await inside it.
        # So we load artifacts asynchronously HERE, once per run, and stash
        # them on self._prior_artifacts for _get_system_prompt to read.
        #
        # Shadow-mode gate: in Phase 1, shadow meant "orchestrator runs with
        # zero pipeline effect". Phase 2 redefines shadow as "non-blocking +
        # consumes artifacts for context" — only `orchestrator_mode="off"`
        # bypasses artifact loading. `live` is the mode that will start
        # halting/gating on specialist verdicts (not done here).
        #
        # Race tolerance: the orchestrator runs in a separate process. There
        # is no happens-before guarantee that its artifact writes are visible
        # when CodingAgent starts reading. When artifacts are absent we fall
        # back to {} — compose_coding_prompt({}) returns the base prompt
        # unchanged, which is the safe-by-default behavior. T20 will fix
        # the race properly with dispatch-when-done SQS semantics.
        self._prior_artifacts = {}
        if task_id and get_settings().orchestrator_mode != "off":
            try:
                async with async_session_factory() as session:
                    repo = ArtifactRepository(session)
                    self._prior_artifacts = await repo.list_for_task(
                        uuid.UUID(task_id)
                    )
            except Exception:
                logger.warning(
                    "coding_agent.load_prior_artifacts_failed — using base prompt",
                    exc_info=True,
                )
                self._prior_artifacts = {}

        # Store clone path in Redis so ReviewAgent can reuse it
        repo_full_name = task_input.get("repo_full_name", "")
        if repo_full_name and self._scm:
            try:
                redis = await self._get_redis_client()
                if redis:
                    clone_key = f"repo_clone:{repo_full_name}"
                    await redis.set(clone_key, "pending", ex=86400)
            except Exception:
                pass

        # Auto-index repo for RAG if it's large enough
        await self._auto_index_repo(repo_full_name, task_id)

        # Pre-load a compact repo overview so the model sees the whole
        # directory tree + key manifests on iteration 1 instead of burning
        # a dozen tool calls walking list_files directory by directory.
        # Best-effort: failures just skip the injection.
        if repo_full_name and self._scm and "repo_map" not in task_input:
            try:
                ref = task_input.get("default_branch") or "main"
                overview = await build_repo_overview(
                    self._scm, repo_full_name, max_depth=3, ref=ref,
                )
                task_input = {**task_input, "repo_map": overview}
            except Exception as exc:  # noqa: BLE001
                logger.warning("repo.overview pre-load failed: %s", exc)

        return await super().run(
            task_input,
            task_id=task_id,
            model=model,
            max_iterations=max_iterations,
            use_thinking=True,
            stop_sequences=stop_sequences,
            initial_cost=initial_cost,
        )

    def _get_system_prompt(self, context: dict[str, Any]) -> str:
        # Start from the static coding-agent system prompt, then layer on
        # specialist artifact blocks loaded by run() (Phase 2 orchestrator
        # composition). compose_coding_prompt with an empty dict is a
        # no-op, so the base prompt path stays byte-identical when no
        # artifacts exist.
        prompt = compose_coding_prompt(
            CODING_AGENT_SYSTEM_PROMPT, self._prior_artifacts
        )
        mcp_section = self._build_mcp_tools_section()
        if mcp_section:
            prompt += f"\n\n{mcp_section}"
        return prompt

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

    def _get_tool_names(self) -> list[str]:
        return self._registry.tool_names

    def _build_initial_message(self, task_input: dict[str, Any]) -> str:
        """Build the initial user message for a coding run.

        Structure follows .claude/skills/building-with-the-claude-api/
        references/prompt-engineering.md:
          * Leads with a direct-action instruction line.
          * Wraps variable context in descriptive XML tags (<user_request>,
            <repository>, <jira_ticket>, <transition>, <additional>).
          * Consolidates workflow + rules + budget discipline under two
            tag blocks (<workflow>, <rules>) so the model treats them as
            structured guidance, not free-form prose.
          * Interpolates SCM / ticket specifics only when they exist —
            empty branches produce no lines at all.
        """
        jira_key = task_input.get("jira_key", "")
        repo = task_input.get("repo_full_name", "")
        scm_type = task_input.get("scm_type", "github")
        pr_tool = "bitbucket.create_pr" if scm_type == "bitbucket" else "github.create_pr"
        user_message = task_input.get("message", "")
        extra = task_input.get("instructions", "")
        from_status = task_input.get("from_status", "")
        to_status = task_input.get("to_status", "")

        parts: list[str] = []

        # Direct-action instruction line — no rambling context, no question.
        if jira_key:
            parts.append(f"Implement the Jira ticket {jira_key} and open a pull request.")
        elif user_message:
            parts.append("Implement the user request below and open a pull request.")
        else:
            parts.append("Complete the task described below and open a pull request.")
        parts.append("")

        # Variable context in descriptive XML tags. Keep each block short.
        if user_message:
            parts.append("<user_request>")
            parts.append(user_message)
            parts.append("</user_request>")
            parts.append("")

        if repo:
            parts.append("<repository>")
            parts.append(f"full_name: {repo}")
            parts.append(f"scm_type: {scm_type}")
            parts.append(f"pr_tool: {pr_tool}")
            parts.append("</repository>")
            parts.append("")

        if jira_key:
            parts.append("<jira_ticket>")
            parts.append(f"key: {jira_key}")
            parts.append(f"branch_name: feature/{jira_key}")
            parts.append(f"pr_title_format: {jira_key}: <summary-from-jira>")
            if from_status and to_status:
                parts.append(f"status_transition: {from_status} -> {to_status}")
            parts.append("</jira_ticket>")
            parts.append("")

        # Pre-loaded repo overview (populated by run() via build_repo_overview).
        # Lets the model see the whole directory tree + top-level manifests
        # on iteration 1 so it skips the "walk every subdirectory" phase.
        repo_map = task_input.get("repo_map")
        if repo_map and isinstance(repo_map, dict):
            parts.append("<repo_map>")
            tree = repo_map.get("tree") or []
            for e in tree:
                if e.get("_truncated"):
                    parts.append(f"[... {e.get('reason', 'truncated')}]")
                    continue
                if e.get("error"):
                    continue
                suffix = "/" if e.get("is_directory") else ""
                parts.append(f"{e.get('path', '')}{suffix}")
            manifests = repo_map.get("manifests") or {}
            for mpath, mcontent in manifests.items():
                parts.append("")
                parts.append(f"--- {mpath} ---")
                # Keep each manifest short — the repo_map block is about
                # orientation, not deep reading.
                snippet = mcontent if len(mcontent) < 1500 else mcontent[:1500] + "\n[... truncated]"
                parts.append(snippet)
            parts.append("</repo_map>")
            parts.append("")

        # Process-steps block — numbered because order matters.
        parts.append("<workflow>")
        if jira_key:
            parts.append("1. UNDERSTAND: call jira.get_issue to fetch ticket details, "
                         "acceptance criteria, and comments.")
        else:
            parts.append("1. UNDERSTAND: read the user_request above; do NOT call "
                         "jira.get_issue (no ticket provided).")
        parts.append("2. EXPLORE: call repo.overview ONCE to get the whole repo "
                     "tree plus top-level manifests in a single tool call. Then "
                     "use code.semantic_search for 'find the X pattern' queries, "
                     "and batch reads via repo.read_file with `paths` (a list) "
                     "instead of one path per call. Only fall back to single-"
                     "path repo.list_files when you need to dig into one subtree.")
        parts.append("3. PLAN: pick an approach that matches existing conventions and "
                     "the test strategy already in the repo.")
        parts.append("4. IMPLEMENT: call git.create_branch, then commit changes. "
                     "When writing 2+ files for the same logical change, call "
                     "file.write_batch ONCE with all files (produces one commit). "
                     "Only use file.write for a single isolated change. Run tests "
                     "via sandbox.execute.")
        parts.append(f"5. DELIVER: call {pr_tool} to open the PR. Post a Jira comment "
                     "with the PR link if a ticket exists.")
        parts.append("</workflow>")
        parts.append("")

        # Output-guidelines block — the "must be" list for a correct run.
        parts.append("<rules>")
        parts.append("- Pass the exact repository.full_name from the <repository> block "
                     "into every tool call. Do not substitute or guess a different name.")
        if jira_key:
            parts.append("- The user_request above takes priority if it conflicts with "
                         "or goes beyond the Jira description.")
        else:
            parts.append("- Do NOT invent a Jira ticket key — work only from the "
                         "user_request above.")
        parts.append("- Follow existing code patterns and conventions found in the repo; "
                     "do not refactor unrelated files.")
        parts.append("- Write clean, tested code. Include tests for every new code path.")
        parts.append("- Opening the PR (step 5) is the single most important action. A "
                     "partial branch with a PR beats a complete branch with no PR.")
        parts.append(f"- Budget: stop writing files and call {pr_tool} by the two-thirds "
                     "mark of your iteration budget. If incomplete, open the PR anyway "
                     "with a TODO section in the body listing what remains.")
        parts.append(f"- Never end the run without calling {pr_tool}. If blocked, open a "
                     "draft PR referencing what is left.")
        parts.append("</rules>")

        if extra:
            parts.append("")
            parts.append("<additional_instructions>")
            parts.append(extra)
            parts.append("</additional_instructions>")

        return "\n".join(parts)
