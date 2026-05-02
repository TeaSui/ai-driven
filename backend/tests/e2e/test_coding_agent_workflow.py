"""End-to-end workflow tests for Feature 1: AI Coding Agent.

Tests the full flow: trigger → plan → code → PR → Jira update → notify.
All external services are mocked — no real API calls.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.base import AgentResult
from ai_driven.agents.coding.agent import CodingAgent
from ai_driven.agents.coding.planner import ImplementationPlan, PlanningAgent
from ai_driven.claude.provider import (
    MessageResponse,
    TextBlock,
    TokenUsage,
    ToolUseBlock,
)

pytestmark = [pytest.mark.e2e, pytest.mark.asyncio]


# ---------------------------------------------------------------------------
# Helpers / Fakes
# ---------------------------------------------------------------------------


def _make_response(
    text: str = "",
    tool_calls: list[ToolUseBlock] | None = None,
    stop_reason: str = "end_turn",
    input_tokens: int = 100,
    output_tokens: int = 200,
) -> MessageResponse:
    content: list[Any] = []
    raw: list[Any] = []
    if tool_calls:
        content.extend(tool_calls)
        raw.extend(
            {"type": "tool_use", "id": t.id, "name": t.name, "input": t.input}
            for t in tool_calls
        )
        stop_reason = "tool_use"
    if text:
        content.append(TextBlock(text=text))
        raw.append({"type": "text", "text": text})
    return MessageResponse(
        content=content,
        stop_reason=stop_reason,
        usage=TokenUsage(input_tokens=input_tokens, output_tokens=output_tokens),
        model="claude-sonnet-4-6",
        raw_content=raw,
    )


@dataclass
class FakeJiraIssue:
    key: str = "PROJ-123"
    summary: str = "Add user profile endpoint"
    description: str = "Implement GET /api/users/:id"
    status: str = "Ready for Dev"
    acceptance_criteria: str = "Returns 200 with user JSON"
    labels: list[str] = field(default_factory=list)
    subtasks: list[str] = field(default_factory=list)


@dataclass
class FakeComment:
    id: str = "comment-1"


@dataclass
class FakeFileEntry:
    path: str
    name: str
    is_directory: bool = False
    size: int = 100


@dataclass
class FakeFile:
    path: str
    content: str
    size: int = 500


@dataclass
class FakeSearchResult:
    path: str
    content: str


@dataclass
class FakePR:
    number: int = 42
    url: str = "https://github.com/org/repo/pull/42"
    title: str = "PROJ-123: Add user profile endpoint"


def _build_mock_jira() -> AsyncMock:
    jira = AsyncMock()
    jira.get_issue = AsyncMock(return_value=FakeJiraIssue())
    jira.add_comment = AsyncMock(return_value=FakeComment())
    jira.transition_issue = AsyncMock()
    return jira


def _build_mock_scm() -> AsyncMock:
    scm = AsyncMock()
    scm.list_files = AsyncMock(
        return_value=[
            FakeFileEntry(path="src/api/users.py", name="users.py"),
            FakeFileEntry(path="src/models/user.py", name="user.py"),
            FakeFileEntry(path="tests/", name="tests", is_directory=True),
        ]
    )
    scm.read_file = AsyncMock(
        return_value=FakeFile(path="src/api/users.py", content="# user module")
    )
    scm.search_code = AsyncMock(
        return_value=[FakeSearchResult(path="src/api/base.py", content="class BaseRouter")]
    )
    scm.create_branch = AsyncMock()
    scm.create_or_update_file = AsyncMock()
    scm.create_pull_request = AsyncMock(return_value=FakePR())
    return scm


def _sequential_responder(responses: list[MessageResponse]):
    """Return a callable that yields responses in order."""
    idx = 0

    async def respond(**kwargs: Any) -> MessageResponse:
        nonlocal idx
        resp = responses[min(idx, len(responses) - 1)]
        idx += 1
        return resp

    return respond


# ---------------------------------------------------------------------------
# Happy Path: Jira Ticket → PR Created
# ---------------------------------------------------------------------------


class TestHappyPath:
    """Full flow: Jira trigger → get issue → explore repo → create branch →
    write file → create PR → update Jira → comment."""

    async def test_full_coding_workflow(self) -> None:
        jira = _build_mock_jira()
        scm = _build_mock_scm()
        agent = CodingAgent(jira_client=jira, scm_client=scm)

        responses = [
            # 1. Agent fetches Jira issue
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-1", name="jira.get_issue",
                             input={"issue_key": "PROJ-123"})
            ]),
            # 2. Agent lists repo files
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-2", name="repo.list_files",
                             input={"repo_full_name": "org/repo"})
            ]),
            # 3. Agent reads relevant file
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-3", name="repo.read_file",
                             input={"repo_full_name": "org/repo", "path": "src/api/users.py"})
            ]),
            # 4. Agent creates branch
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-4", name="git.create_branch",
                             input={"repo_full_name": "org/repo", "branch_name": "feature/PROJ-123"})
            ]),
            # 5. Agent writes code
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-5", name="file.write",
                             input={"repo_full_name": "org/repo", "path": "src/api/users.py",
                                    "content": "def get_user(id): ...", "commit_message": "feat: add user endpoint",
                                    "branch": "feature/PROJ-123"})
            ]),
            # 6. Agent creates PR
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-6", name="github.create_pr",
                             input={"repo_full_name": "org/repo", "title": "PROJ-123: Add user profile",
                                    "body": "Implements GET /api/users/:id",
                                    "source_branch": "feature/PROJ-123"})
            ]),
            # 7. Agent updates Jira status
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-7", name="jira.update_status",
                             input={"issue_key": "PROJ-123", "transition_name": "In Review"})
            ]),
            # 8. Agent adds Jira comment
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-8", name="jira.add_comment",
                             input={"issue_key": "PROJ-123", "body": "PR created: https://github.com/org/repo/pull/42"})
            ]),
            # 9. Final text response
            _make_response(text="Done! PR #42 created for PROJ-123."),
        ]

        agent._claude._provider.create_message = _sequential_responder(responses)

        result = await agent.run(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"},
            task_id="task-coding-1",
        )

        assert result.success is True
        assert result.tool_calls == 8
        jira.get_issue.assert_awaited_once_with("PROJ-123")
        scm.create_branch.assert_awaited_once()
        scm.create_or_update_file.assert_awaited_once()
        scm.create_pull_request.assert_awaited_once()
        jira.transition_issue.assert_awaited_once_with("PROJ-123", "In Review")
        jira.add_comment.assert_awaited_once()

    async def test_agent_result_has_metrics(self) -> None:
        agent = CodingAgent(jira_client=_build_mock_jira(), scm_client=_build_mock_scm())
        agent._claude._provider.create_message = _sequential_responder([
            _make_response(text="Done."),
        ])

        with patch("ai_driven.agents.base.emit_agent_metrics") as mock_emit:
            result = await agent.run(
                {"jira_key": "PROJ-123", "repo_full_name": "org/repo"},
                task_id="task-m",
            )
            assert result.success is True
            assert result.duration_seconds > 0
            mock_emit.assert_called_once()
            assert mock_emit.call_args[0][0] == "coding"
            assert mock_emit.call_args[1]["success"] is True


# ---------------------------------------------------------------------------
# Initial Message Construction
# ---------------------------------------------------------------------------


class TestInitialMessage:
    """Verify the coding agent builds correct initial messages."""

    async def test_includes_jira_key_and_repo(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-456", "repo_full_name": "org/repo"}
        )
        assert "PROJ-456" in msg
        assert "org/repo" in msg

    async def test_includes_workflow_steps(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message(
            {"jira_key": "X-1", "repo_full_name": "a/b"}
        )
        assert "UNDERSTAND" in msg
        assert "EXPLORE" in msg
        assert "PLAN" in msg
        assert "IMPLEMENT" in msg
        assert "DELIVER" in msg

    async def test_includes_branch_naming(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-789", "repo_full_name": "a/b"}
        )
        assert "feature/PROJ-789" in msg

    async def test_includes_transition_context(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message(
            {"jira_key": "X-1", "repo_full_name": "a/b",
             "from_status": "To Do", "to_status": "In Progress"}
        )
        assert "To Do" in msg
        assert "In Progress" in msg

    async def test_includes_additional_instructions(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message(
            {"jira_key": "X-1", "repo_full_name": "a/b",
             "instructions": "Use the factory pattern"}
        )
        assert "factory pattern" in msg


# ---------------------------------------------------------------------------
# Tool Execution — No Clients
# ---------------------------------------------------------------------------


class TestToolsWithoutClients:
    """Tools return error dicts when Jira/SCM clients are None."""

    async def test_get_jira_issue_no_jira(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute("jira.get_issue", {"issue_key": "X-1"})
        assert "error" in result

    async def test_list_repo_files_no_scm(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "repo.list_files", {"repo_full_name": "a/b"}
        )
        assert isinstance(result, list)
        assert result[0].get("error")

    async def test_read_file_no_scm(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "a/b", "path": "x.py"}
        )
        assert "error" in result

    async def test_create_pr_no_scm(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "github.create_pr",
            {"repo_full_name": "a/b", "title": "t", "body": "b", "source_branch": "f"},
        )
        assert "error" in result

    async def test_write_file_no_scm(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "file.write",
            {"repo_full_name": "a/b", "path": "x.py", "content": "c",
             "commit_message": "m", "branch": "b"},
        )
        assert "error" in result

    async def test_create_branch_no_scm(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "git.create_branch", {"repo_full_name": "a/b", "branch_name": "feature/X"}
        )
        assert "error" in result

    async def test_update_jira_status_no_jira(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "jira.update_status", {"issue_key": "X-1", "transition_name": "Done"}
        )
        assert "error" in result

    async def test_add_jira_comment_no_jira(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "jira.add_comment", {"issue_key": "X-1", "body": "test"}
        )
        assert "error" in result


# ---------------------------------------------------------------------------
# Tool Registration
# ---------------------------------------------------------------------------


class TestToolRegistration:
    """Verify all expected tools are registered."""

    async def test_all_tools_registered(self) -> None:
        agent = CodingAgent()
        names = set(agent._registry.tool_names)
        expected = {
            "jira.get_issue", "jira.add_comment", "repo.list_files",
            "repo.read_file", "code.search", "github.create_pr",
            "jira.update_status", "file.write", "git.create_branch",
            "sandbox.execute",
        }
        assert expected.issubset(names)

    async def test_tool_count(self) -> None:
        agent = CodingAgent()
        assert len(agent._registry.tool_names) == 10


# ---------------------------------------------------------------------------
# File Read Truncation
# ---------------------------------------------------------------------------


class TestReadFileTruncation:
    """Large files should be truncated at 10KB."""

    async def test_small_file_not_truncated(self) -> None:
        scm = AsyncMock()
        scm.read_file = AsyncMock(
            return_value=FakeFile(path="a.py", content="x" * 100, size=100)
        )
        agent = CodingAgent(scm_client=scm)
        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "a/b", "path": "a.py"}
        )
        assert len(result["content"]) == 100

    async def test_large_file_truncated(self) -> None:
        big_content = "x" * 20_000
        scm = AsyncMock()
        scm.read_file = AsyncMock(
            return_value=FakeFile(path="big.py", content=big_content, size=20_000)
        )
        agent = CodingAgent(scm_client=scm)
        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "a/b", "path": "big.py"}
        )
        assert "truncated" in result["content"]
        assert len(result["content"]) < 20_000


# ---------------------------------------------------------------------------
# Planning Agent
# ---------------------------------------------------------------------------


class TestPlanningAgent:
    """Test the planning phase with extended thinking."""

    async def test_create_plan_calls_claude_with_thinking(self) -> None:
        mock_client = AsyncMock()
        plan_json = json.dumps({
            "summary": "Add GET /users/:id endpoint",
            "files_to_create": [{"path": "src/api/users.py", "purpose": "User endpoint"}],
            "files_to_modify": [{"path": "src/main.py", "changes": "Register router"}],
            "test_strategy": "Unit test with mocked DB",
            "risks": ["Breaking existing endpoints"],
            "steps": ["Create model", "Create route", "Add tests"],
        })
        mock_client.chat = AsyncMock(
            return_value=_make_response(text=f"```json\n{plan_json}\n```")
        )

        planner = PlanningAgent(claude_client=mock_client)
        plan = await planner.create_plan(
            jira_summary="Add user profile",
            jira_description="Implement GET /users/:id",
            acceptance_criteria="Returns 200 with user JSON",
            repo_structure=["src/", "src/api/", "tests/"],
            relevant_code=[{"path": "src/api/base.py", "content": "class BaseRouter: ..."}],
        )

        assert plan.summary == "Add GET /users/:id endpoint"
        assert len(plan.files_to_create) == 1
        assert len(plan.steps) == 3
        assert plan.test_strategy == "Unit test with mocked DB"

        # Verify extended thinking was requested
        call_kwargs = mock_client.chat.call_args[1]
        assert call_kwargs["thinking"]["type"] == "enabled"
        assert call_kwargs["thinking"]["budget_tokens"] == 16000

    async def test_parse_plan_invalid_json_fallback(self) -> None:
        plan = PlanningAgent._parse_plan("This is not JSON.")
        assert plan.summary == "This is not JSON."
        assert plan.files_to_create == []

    async def test_implementation_plan_from_dict(self) -> None:
        plan = ImplementationPlan.from_dict({
            "summary": "Test",
            "steps": ["a", "b"],
        })
        assert plan.summary == "Test"
        assert len(plan.steps) == 2
        assert plan.risks == []

    async def test_build_prompt_caps_structure(self) -> None:
        planner = PlanningAgent()
        prompt = planner._build_prompt(
            "summary", "desc", "criteria",
            repo_structure=[f"file_{i}.py" for i in range(300)],
            relevant_code=[],
        )
        # Should cap at 200 lines of structure
        lines = [l for l in prompt.split("\n") if l.startswith("file_")]
        assert len(lines) <= 200

    async def test_build_prompt_caps_code_items(self) -> None:
        planner = PlanningAgent()
        prompt = planner._build_prompt(
            "summary", "desc", "criteria",
            repo_structure=[],
            relevant_code=[{"path": f"f{i}.py", "content": "x" * 5000} for i in range(15)],
        )
        # Should cap at 10 code items, each truncated to 3000 chars
        assert prompt.count("###") <= 10


# ---------------------------------------------------------------------------
# Agent Failure Scenarios
# ---------------------------------------------------------------------------


class TestFailureScenarios:
    """Test error handling and recovery."""

    async def test_claude_api_exception_returns_failed_result(self) -> None:
        agent = CodingAgent(jira_client=_build_mock_jira(), scm_client=_build_mock_scm())

        async def explode(**kwargs: Any) -> MessageResponse:
            raise RuntimeError("Claude API timeout")

        agent._claude._provider.create_message = explode

        with patch("ai_driven.agents.base.emit_agent_metrics"):
            result = await agent.run(
                {"jira_key": "PROJ-123", "repo_full_name": "org/repo"},
                task_id="task-fail",
            )

        assert result.success is False
        assert "timeout" in result.error.lower()

    async def test_tool_execution_error_doesnt_crash_agent(self) -> None:
        """If a tool raises, the error is captured as a tool_result and the loop continues."""
        jira = _build_mock_jira()
        jira.get_issue = AsyncMock(side_effect=Exception("Jira unavailable"))
        scm = _build_mock_scm()
        agent = CodingAgent(jira_client=jira, scm_client=scm)

        responses = [
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-1", name="jira.get_issue",
                             input={"issue_key": "PROJ-123"})
            ]),
            # After tool error, Claude should still get the error result and respond
            _make_response(text="Jira is unavailable. Cannot proceed."),
        ]
        agent._claude._provider.create_message = _sequential_responder(responses)

        result = await agent.run(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"},
            task_id="task-tool-err",
        )

        # Agent completes (the tool error is in the conversation, not a crash)
        assert result.success is True
        assert result.tool_calls == 1

    async def test_metrics_emitted_on_failure(self) -> None:
        agent = CodingAgent()

        async def explode(**kwargs: Any) -> MessageResponse:
            raise ValueError("boom")

        agent._claude._provider.create_message = explode

        with patch("ai_driven.agents.base.emit_agent_metrics") as mock_emit:
            result = await agent.run({"jira_key": "X-1", "repo_full_name": "a/b"})
            assert result.success is False
            mock_emit.assert_called_once()
            assert mock_emit.call_args[1]["success"] is False


# ---------------------------------------------------------------------------
# Event Publishing (Redis pub/sub)
# ---------------------------------------------------------------------------


class TestEventPublishing:
    """Verify agent publishes events for WebSocket streaming."""

    async def test_publishes_start_and_complete_events(self) -> None:
        agent = CodingAgent()
        agent._claude._provider.create_message = _sequential_responder([
            _make_response(text="Done"),
        ])

        published = []
        original_publish = agent._publish_event

        async def capture_event(event_type: str, data: dict) -> None:
            published.append(event_type)

        agent._publish_event = capture_event

        await agent.run(
            {"jira_key": "X-1", "repo_full_name": "a/b"},
            task_id="task-events",
        )

        assert "phase_change" in published
        assert "complete" in published

    async def test_publishes_error_event_on_failure(self) -> None:
        agent = CodingAgent()

        async def explode(**kwargs: Any) -> MessageResponse:
            raise RuntimeError("boom")

        agent._claude._provider.create_message = explode

        published = []

        async def capture_event(event_type: str, data: dict) -> None:
            published.append(event_type)

        agent._publish_event = capture_event

        await agent.run(
            {"jira_key": "X-1", "repo_full_name": "a/b"},
            task_id="task-err-events",
        )

        assert "phase_change" in published
        assert "error" in published

    async def test_no_events_without_task_id(self) -> None:
        agent = CodingAgent()
        agent._claude._provider.create_message = _sequential_responder([
            _make_response(text="Done"),
        ])

        # No task_id → _publish_event should be a no-op
        # We verify by checking _task_id remains None
        await agent.run({"jira_key": "X-1", "repo_full_name": "a/b"})
        # No crash is the verification — _publish_event exits early when _task_id is None


# ---------------------------------------------------------------------------
# System Prompt
# ---------------------------------------------------------------------------


class TestSystemPrompt:
    """Verify system prompt content."""

    async def test_system_prompt_is_non_trivial(self) -> None:
        agent = CodingAgent()
        prompt = agent._get_system_prompt({})
        assert len(prompt) > 100

    async def test_system_prompt_references_tools(self) -> None:
        agent = CodingAgent()
        prompt = agent._get_system_prompt({})
        # Should reference the workflow or coding tasks
        assert any(
            keyword in prompt.lower()
            for keyword in ["code", "implement", "jira", "pull request"]
        )
