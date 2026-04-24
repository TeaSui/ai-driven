"""Tool registry — register, lookup, and execute tools for Claude."""

from __future__ import annotations

import logging
from typing import Any, Callable, Awaitable

logger = logging.getLogger(__name__)

ToolHandler = Callable[..., Awaitable[Any]]


class ToolDefinition:
    """A registered tool with its schema and handler."""

    def __init__(
        self,
        name: str,
        description: str,
        input_schema: dict[str, Any],
        handler: ToolHandler,
    ) -> None:
        self.name = name
        self.description = description
        self.input_schema = input_schema
        self.handler = handler

    @property
    def schema(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "input_schema": self.input_schema,
        }


class ToolRegistry:
    """Registry for tools available to Claude agents."""

    def __init__(self) -> None:
        self._tools: dict[str, ToolDefinition] = {}

    def register(
        self,
        name: str,
        description: str,
        input_schema: dict[str, Any],
        handler: ToolHandler,
    ) -> None:
        if name in self._tools:
            logger.warning(f"Overwriting existing tool: {name}")
        self._tools[name] = ToolDefinition(name, description, input_schema, handler)

    def tool(
        self,
        name: str,
        description: str,
        input_schema: dict[str, Any],
    ) -> Callable[[ToolHandler], ToolHandler]:
        """Decorator to register a tool handler."""
        def decorator(func: ToolHandler) -> ToolHandler:
            self.register(name, description, input_schema, func)
            return func
        return decorator

    def get_schemas(self, tool_names: list[str] | None = None) -> list[dict[str, Any]]:
        if tool_names is None:
            return [t.schema for t in self._tools.values()]
        return [self._tools[n].schema for n in tool_names if n in self._tools]

    def get_tool(self, name: str) -> ToolDefinition | None:
        return self._tools.get(name)

    async def execute(self, name: str, input_data: dict[str, Any]) -> Any:
        tool = self._tools.get(name)
        if tool is None:
            raise ValueError(f"Unknown tool: {name}")

        logger.debug(f"Executing tool {name} with input: {input_data}")

        # Strategy: **always try the real handler first**. If it fails:
        #   - Return the actual error message to the model so it can adapt
        #     (retry with different args, skip the step, etc.)
        #   - Only fall back to canned mock data when there's genuinely no
        #     client configured (e.g. missing credentials entirely).
        try:
            return await tool.handler(**input_data)
        except Exception as ex:
            from ai_driven.config import get_settings
            if not get_settings().debug:
                raise

            error_msg = f"{type(ex).__name__}: {str(ex)[:200]}"

            # Check if this is a "no client configured" error vs a real API error.
            # Real API errors (404, validation, etc.) should go back to the model
            # so it can adapt. Missing-client errors get mock fallback.
            is_missing_client = (
                "not configured" in str(ex).lower()
                or "no client" in str(ex).lower()
                or isinstance(ex, (ImportError, ModuleNotFoundError))
            )

            if is_missing_client:
                canned = _get_mock_tool_result(name, input_data)
                if canned is not None:
                    logger.warning(
                        f"[debug] tool {name} has no client configured; "
                        f"returning canned mock result"
                    )
                    return canned

            # Return the real error to the model so it can adapt its approach
            logger.warning(
                f"[debug] tool {name} failed ({error_msg}); "
                f"returning error to model"
            )
            return {
                "error": True,
                "tool": name,
                "message": error_msg,
            }

    @property
    def tool_names(self) -> list[str]:
        return list(self._tools.keys())

    def __len__(self) -> int:
        return len(self._tools)


# ── Debug-mode canned tool results ────────────────────────────────────────────
#
# Keys are (partial) tool-name matches. Lookup is: exact match first, then
# longest suffix-match (so `get_jira_issue` matches `jira.get_issue` too, and a
# freshly-registered `github_create_pr` finds the `create_pr` key). Values are
# JSON-serializable Python objects — the agent loop wraps them into tool_result
# blocks so MockProvider can continue to the next step.
_MOCK_TOOL_RESULTS: dict[str, Any] = {
    # Jira
    "jira.get_issue": {
        "key": "GTX-4829",
        "summary": "Fix race condition in ledger balance cache",
        "status": "Ready for Dev",
        "assignee": "ai-agent",
        "description": "Intermittent failures when two concurrent transfers read the same account balance. Check-then-set without locking.",
        "acceptance_criteria": ["Race condition fixed", "Unit tests cover concurrent access"],
    },
    "jira.add_comment": {"id": "comment-mock-1", "body": "AI agent posted PR link."},
    "jira.update_status": {"key": "GTX-4829", "status": "In Review", "updated": True},
    "jira.create_incident": {"key": "INC-789", "url": "https://jira.example.com/browse/INC-789"},
    "jira.transition_issue": {"key": "GTX-4829", "new_status": "Done"},
    # SCM / GitHub / Bitbucket
    "repo.list_files": {
        "files": [
            "src/api/endpoints.py", "src/services/cache.py", "src/services/handler.py",
            "src/models/schema.py", "tests/test_cache.py", "tests/test_endpoints.py",
        ],
    },
    "repo.read_file": {
        "content": "class BalanceCache:\n    def __init__(self):\n        self._cache = {}\n\n    async def get(self, account_id):\n        if account_id in self._cache:\n            return self._cache[account_id]\n        balance = await self._load(account_id)\n        self._cache[account_id] = balance\n        return balance\n",
        "lines": 42,
    },
    "repo.write_file": {"success": True, "path": "mock/path", "bytes_written": 512},
    "repo.search_code": {"matches": [{"file": "src/services/cache.py", "line": 8, "text": "class BalanceCache:"}]},
    "code.search": {"matches": [{"file": "src/services/cache.py", "line": 8, "text": "class BalanceCache:"}]},
    "file.write": {"success": True, "bytes_written": 487},
    "git.create_branch": {"branch": "fix/mock-branch", "sha": "a3f21c8"},
    "github.create_pr": {
        "pr_number": 2419,
        "pr_url": "https://github.com/org/repo/pull/2419",
        "title": "fix(ledger): guard BalanceCache.get() with async lock",
        "state": "open",
    },
    "github.get_pr_diff": {
        "files_changed": 3,
        "additions": 42,
        "deletions": 17,
        "diff": "diff --git a/src/services/cache.py ...\n+    async with self._locks[account_id]:\n-    # old code",
    },
    "github.post_pr_comment": {"id": "review-comment-mock-1", "created": True},
    "github.approve_pr": {"approved": True, "pr_number": 42},
    "github.request_changes": {"requested": True, "pr_number": 42},
    "github.merge_pr": {"merged": True, "sha": "b4f12d9", "pr_number": 2419},
    # Sandbox
    "sandbox.execute": {"exit_code": 0, "stdout": "===== 12 passed in 1.84s =====\n", "stderr": ""},
    "sandbox.run_tests": {"exit_code": 0, "passed": 12, "failed": 0, "duration_ms": 1840},
    # DataDog
    "datadog.query_logs": {
        "logs": [
            {"timestamp": "2026-04-23T10:12:04Z", "service": "user-api", "status": "error",
             "message": "OperationalError: QueryTimeout after 30s"},
            {"timestamp": "2026-04-23T10:12:08Z", "service": "user-api", "status": "error",
             "message": "500 response from /api/v1/balance"},
        ],
        "count": 2,
    },
    "datadog.query_metrics": {
        "series": [{"metric": "http.request.errors", "points": [[1700000000, 0.02], [1700001000, 0.41], [1700002000, 0.63]]}],
    },
    "datadog.get_service_health": {
        "service": "user-api", "status": "degraded", "error_rate": 0.41, "p95_latency_ms": 2800,
    },
    "deploy.get_recent": {
        "deployments": [
            {"sha": "v2.3.1", "merged_at": "2026-04-23T10:05:00Z", "author": "alice"},
            {"sha": "v2.3.0", "merged_at": "2026-04-22T14:12:00Z", "author": "bob"},
        ],
    },
    # Slack
    "slack.notify_team": {"ok": True, "channel": "#engineering", "ts": "mock-ts-1"},
    "slack.notify_oncall": {"ok": True, "channel": "#oncall", "ts": "mock-ts-2"},
    "slack.post_message": {"ok": True, "ts": "mock-ts-3"},
    # Workflow orchestration
    "workflow.dispatch_coding": {"task_id": "coding-task-mock-001", "status": "queued", "agent_type": "coding"},
    "workflow.dispatch_review": {"task_id": "review-task-mock-001", "status": "queued", "agent_type": "review"},
    "workflow.check_task_status": {"task_id": "mock", "status": "completed"},
    # RAG
    "semantic_search_code": {
        "results": [
            {"file": "src/services/cache.py", "start_line": 8, "end_line": 24,
             "score": 0.87, "snippet": "class BalanceCache:\n    async def get(self, account_id):..."},
        ],
    },
}


def _get_mock_tool_result(name: str, input_data: dict[str, Any]) -> Any:
    """Return a canned result for a tool call in debug mode, or None to fall through.

    Matches: exact name, then tool-name suffix (last dot segment), then the
    final underscore-segment. E.g. `get_jira_issue` → `jira.get_issue` →
    `get_issue` all resolve.
    """
    if name in _MOCK_TOOL_RESULTS:
        return _MOCK_TOOL_RESULTS[name]
    # Prefix-stripped match (e.g. "github.create_pr" → "create_pr")
    tail = name.rsplit(".", 1)[-1]
    if tail in _MOCK_TOOL_RESULTS:
        return _MOCK_TOOL_RESULTS[tail]
    # Tail-contains match (e.g. "create_pr" in any stored key that ends with it)
    for key, value in _MOCK_TOOL_RESULTS.items():
        if key.endswith(tail) or tail.endswith(key.rsplit(".", 1)[-1]):
            return value
    return None
