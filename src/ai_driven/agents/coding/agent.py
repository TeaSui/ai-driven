"""AI Coding Agent — takes Jira tickets, generates code, creates PRs."""

from __future__ import annotations

import logging
from typing import Any

from ai_driven.agents.base import BaseAgent
from ai_driven.agents.coding.sandbox import BaseSandboxExecutor, create_sandbox
from ai_driven.claude.prompts.coding_agent import CODING_AGENT_SYSTEM_PROMPT
from ai_driven.config import get_settings

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
                {"path": e.path, "name": e.name, "is_directory": e.is_directory, "size": e.size}
                for e in entries
            ]

        @self._registry.tool(
            name="repo.read_file",
            description="Read the contents of a file from the repository.",
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
            if not self._scm:
                return {"error": "SCM client not configured"}
            file = await self._scm.read_file(repo_full_name, path, ref)
            content = file.content
            if file.size > 10240:
                content = content[:10240] + f"\n\n[... truncated, {file.size} bytes total]"
            return {"path": file.path, "content": content, "size": file.size}

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
            if not self._scm:
                return {"error": "SCM client not configured"}
            pr = await self._scm.create_pull_request(
                repo_full_name, title, body, source_branch, target_branch,
            )
            return {"number": pr.number, "url": pr.url, "title": pr.title}

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
            if not self._scm:
                return {"error": "SCM client not configured"}
            pr = await self._scm.create_pull_request(
                repo_full_name, title, body, source_branch, target_branch,
            )
            return {"number": pr.number, "url": pr.url, "title": pr.title}

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
        """Enable extended thinking and register async tools before running."""
        # Register MCP tools (async, must be done here)
        await self._register_mcp_tools()

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

        return await super().run(
            task_input,
            task_id=task_id,
            model=model,
            max_iterations=max_iterations,
            use_thinking=True,
            stop_sequences=stop_sequences,
        )

    def _get_system_prompt(self, context: dict[str, Any]) -> str:
        prompt = CODING_AGENT_SYSTEM_PROMPT
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
        """Build comprehensive initial message with task context."""
        jira_key = task_input.get("jira_key", "")
        repo = task_input.get("repo_full_name", "")
        # User's original chat message — the primary intent
        user_message = task_input.get("message", "")
        extra = task_input.get("instructions", "")
        from_status = task_input.get("from_status", "")
        to_status = task_input.get("to_status", "")

        parts: list[str] = []

        # Lead with the user's own words so the model treats them as the
        # primary objective, not just Jira metadata.
        if user_message:
            parts.append(f"**User request:** {user_message}")
            parts.append("")

        if jira_key:
            parts.append(
                f"Jira ticket: {jira_key}  ·  Repository: {repo}"
            )
            parts.append(
                "Use jira.get_issue to fetch the ticket details — but the user's request above "
                "takes priority when it conflicts with or goes beyond the Jira description."
            )
        elif repo:
            parts.append(f"Repository: {repo}")

        parts += [
            "",
            "Follow this workflow:",
            "1. UNDERSTAND: Use jira.get_issue to fetch the ticket details, acceptance criteria, and comments.",
            "2. EXPLORE: Use repo.list_files and repo.read_file to understand the codebase structure and relevant code.",
            "3. PLAN: Think through the implementation approach, considering existing patterns and test strategy.",
            "4. IMPLEMENT: Use git.create_branch to create a feature branch, then file.write to make changes.",
            "5. DELIVER: Use github.create_pr to submit the changes, then jira.add_comment to update the ticket.",
            "",
            "Important rules:",
            "- Follow existing code patterns and conventions found in the repository.",
            f"- Create a feature branch named `feature/{jira_key}` from the default branch." if jira_key else "",
            "- Write clean, tested code that matches the acceptance criteria.",
            f"- The PR title should be `{jira_key}: <summary>` where summary comes from Jira." if jira_key else "",
            "- Post a Jira comment with the PR link when done." if jira_key else "",
        ]

        # Remove empty strings from conditional lines
        parts = [p for p in parts if p or p == ""]

        if from_status and to_status:
            parts.append(
                f"\nTransition context: ticket is moving from '{from_status}' to '{to_status}'."
            )

        if extra:
            parts.append(f"\nAdditional instructions: {extra}")

        return "\n".join(parts)
