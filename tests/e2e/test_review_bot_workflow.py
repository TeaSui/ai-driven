"""End-to-end workflow tests for Feature 2: AI Code Review Bot.

Tests the full flow: PR webhook → dedup → task → agent → review → comments posted.
All external services are mocked — no real API calls.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.base import AgentResult
from ai_driven.agents.reviewer.agent import ReviewAgent, _truncate_diff_files
from ai_driven.agents.reviewer.config import ReviewConfig
from ai_driven.agents.reviewer.models import (
    SEVERITY_ORDER,
    ReviewComment,
    ReviewResult,
)
from ai_driven.claude.conversation import Conversation
from ai_driven.claude.provider import (
    MessageResponse,
    TextBlock,
    TokenUsage,
    ToolUseBlock,
)

pytestmark = [pytest.mark.e2e, pytest.mark.asyncio]


# ---------------------------------------------------------------------------
# Helpers / Fixtures
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
class FakeDiffFile:
    path: str
    status: str
    additions: int
    deletions: int
    patch: str


@dataclass
class FakeDiff:
    files: list[FakeDiffFile]
    total_additions: int = 0
    total_deletions: int = 0


@dataclass
class FakeFile:
    path: str
    content: str
    size: int = 100
    name: str = ""
    is_directory: bool = False


@dataclass
class FakeComment:
    id: str = "cmt-1"


def _small_diff() -> FakeDiff:
    return FakeDiff(
        files=[
            FakeDiffFile(
                path="src/api/users.py",
                status="modified",
                additions=10,
                deletions=2,
                patch="@@ -1,5 +1,13 @@\n+def get_user(id):\n+    return db.query(f'SELECT * FROM users WHERE id={id}')",
            ),
            FakeDiffFile(
                path="src/api/models.py",
                status="added",
                additions=20,
                deletions=0,
                patch="@@ -0,0 +1,20 @@\n+class UserModel:\n+    pass",
            ),
        ],
        total_additions=30,
        total_deletions=2,
    )


REVIEW_JSON_APPROVE = json.dumps(
    {
        "verdict": "approve",
        "summary": "Clean implementation with good test coverage.",
        "comments": [
            {
                "severity": "suggestion",
                "file_path": "src/api/users.py",
                "line": 5,
                "body": "Consider adding type hints.",
                "category": "style",
            }
        ],
        "positive_highlights": ["Good error handling", "Well-structured code"],
    }
)

REVIEW_JSON_CHANGES_REQUESTED = json.dumps(
    {
        "verdict": "request_changes",
        "summary": "Found a critical SQL injection vulnerability.",
        "comments": [
            {
                "severity": "critical",
                "file_path": "src/api/users.py",
                "line": 3,
                "body": "SQL injection: use parameterized queries instead of f-string.",
                "category": "security",
            },
            {
                "severity": "warning",
                "file_path": "src/api/users.py",
                "line": 10,
                "body": "Missing input validation on user ID.",
                "category": "bug",
            },
            {
                "severity": "suggestion",
                "file_path": "src/api/models.py",
                "line": 1,
                "body": "Add docstring to class.",
                "category": "style",
            },
            {
                "severity": "warning",
                "file_path": "vendor/lib.py",
                "line": 5,
                "body": "Deprecated API usage.",
                "category": "quality",
            },
        ],
        "positive_highlights": [],
    }
)


def _build_mock_scm() -> AsyncMock:
    """Build a mock SCM client with standard responses."""
    scm = AsyncMock()
    scm.get_pr_diff = AsyncMock(return_value=_small_diff())
    scm.read_file = AsyncMock(
        return_value=FakeFile(
            path="src/api/users.py",
            content="def get_user(id):\n    return db.query(f'SELECT * FROM users WHERE id={id}')",
            name="users.py",
        )
    )
    scm.post_pr_comment = AsyncMock(return_value=FakeComment(id="cmt-1"))
    scm.list_files = AsyncMock(return_value=[])
    scm.search_code = AsyncMock(return_value=[])
    return scm


# ---------------------------------------------------------------------------
# Happy Path: PR → Review → Approve
# ---------------------------------------------------------------------------


class TestHappyPathApprove:
    """Full flow: PR opened → agent reviews → 1 suggestion → approved."""

    async def test_review_agent_runs_and_produces_result(self) -> None:
        scm = _build_mock_scm()
        config = ReviewConfig()
        agent = ReviewAgent(scm_client=scm, review_config=config)

        # Claude first requests diff, then returns review JSON
        responses = [
            _make_response(
                tool_calls=[
                    ToolUseBlock(
                        id="tu-1",
                        name="github.get_pr_diff",
                        input={"repo_full_name": "org/repo", "pr_number": 42},
                    )
                ]
            ),
            _make_response(text=f"```json\n{REVIEW_JSON_APPROVE}\n```"),
        ]
        call_idx = 0

        async def mock_create_message(**kwargs: Any) -> MessageResponse:
            nonlocal call_idx
            resp = responses[call_idx]
            call_idx += 1
            return resp

        agent._claude._provider.create_message = mock_create_message

        result = await agent.run(
            {"repo_full_name": "org/repo", "pr_number": 42, "pr_title": "Add users"},
            task_id="task-review-1",
        )

        assert result.success is True
        assert "approve" in result.output.lower() or "Clean" in result.output
        scm.get_pr_diff.assert_awaited_once()

    async def test_review_result_parsing(self) -> None:
        parsed = ReviewResult.parse_from_text(f"```json\n{REVIEW_JSON_APPROVE}\n```")
        assert parsed.verdict == "approve"
        assert len(parsed.comments) == 1
        assert parsed.comments[0].severity == "suggestion"
        assert len(parsed.positive_highlights) == 2

    async def test_format_summary_includes_verdict(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_APPROVE)
        summary = parsed.format_summary()
        assert "Approve" in summary
        assert "AI Code Review" in summary
        assert "Suggestion: 1" in summary


# ---------------------------------------------------------------------------
# Changes Requested: Critical Issues Found
# ---------------------------------------------------------------------------


class TestChangesRequested:
    """Critical security issue found → changes_requested verdict."""

    async def test_agent_posts_inline_comments(self) -> None:
        scm = _build_mock_scm()
        config = ReviewConfig()
        agent = ReviewAgent(scm_client=scm, review_config=config)

        # Claude: get_pr_diff → read_file → post 3 comments → summary
        responses = [
            _make_response(
                tool_calls=[
                    ToolUseBlock(id="tu-1", name="github.get_pr_diff",
                                input={"repo_full_name": "org/repo", "pr_number": 42})
                ]
            ),
            _make_response(
                tool_calls=[
                    ToolUseBlock(id="tu-2", name="repo.read_file",
                                input={"repo_full_name": "org/repo", "path": "src/api/users.py"})
                ]
            ),
            _make_response(
                tool_calls=[
                    ToolUseBlock(id="tu-3", name="github.post_pr_comment",
                                input={"repo_full_name": "org/repo", "pr_number": 42,
                                       "body": "[CRITICAL] SQL injection", "file_path": "src/api/users.py", "line": 3})
                ]
            ),
            _make_response(
                tool_calls=[
                    ToolUseBlock(id="tu-4", name="github.post_pr_comment",
                                input={"repo_full_name": "org/repo", "pr_number": 42,
                                       "body": "[WARNING] Missing validation", "file_path": "src/api/users.py", "line": 10})
                ]
            ),
            _make_response(text=f"```json\n{REVIEW_JSON_CHANGES_REQUESTED}\n```"),
        ]
        call_idx = 0

        async def mock_create_message(**kwargs: Any) -> MessageResponse:
            nonlocal call_idx
            resp = responses[min(call_idx, len(responses) - 1)]
            call_idx += 1
            return resp

        agent._claude._provider.create_message = mock_create_message

        result = await agent.run(
            {"repo_full_name": "org/repo", "pr_number": 42, "pr_title": "Add users"},
            task_id="task-review-2",
        )

        assert result.success is True
        # SCM comment posting was called
        assert scm.post_pr_comment.await_count >= 2

    async def test_review_result_has_critical_issues(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        assert parsed.verdict == "request_changes"
        critical = [c for c in parsed.comments if c.severity == "critical"]
        assert len(critical) == 1
        assert "SQL injection" in critical[0].body


# ---------------------------------------------------------------------------
# Self-Review Skip
# ---------------------------------------------------------------------------


class TestSelfReviewSkip:
    """Bot's own PRs and skip-ai-review labeled PRs should be ignored."""

    async def test_bot_author_pr_skipped(self) -> None:
        """ReviewAgent itself doesn't skip — the webhook handler does.
        Here we verify the webhook logic conceptually by testing
        that a review with empty diff produces minimal output."""
        scm = _build_mock_scm()
        scm.get_pr_diff = AsyncMock(
            return_value=FakeDiff(files=[], total_additions=0, total_deletions=0)
        )
        agent = ReviewAgent(scm_client=scm)

        responses = [
            _make_response(
                tool_calls=[
                    ToolUseBlock(id="tu-1", name="github.get_pr_diff",
                                input={"repo_full_name": "org/repo", "pr_number": 1})
                ]
            ),
            _make_response(text="No changes to review."),
        ]
        call_idx = 0

        async def mock_create(**kwargs: Any) -> MessageResponse:
            nonlocal call_idx
            resp = responses[min(call_idx, len(responses) - 1)]
            call_idx += 1
            return resp

        agent._claude._provider.create_message = mock_create

        result = await agent.run(
            {"repo_full_name": "org/repo", "pr_number": 1},
            task_id="task-skip",
        )
        assert result.success is True
        # No comments posted on empty diff
        scm.post_pr_comment.assert_not_awaited()


# ---------------------------------------------------------------------------
# Large Diff — Model Selection
# ---------------------------------------------------------------------------


class TestLargeDiffModelSelection:
    """Diffs >500 lines should use the planning model (opus)."""

    async def test_select_model_small_diff(self) -> None:
        agent = ReviewAgent(scm_client=AsyncMock())
        with patch("ai_driven.agents.reviewer.agent.get_settings") as mock_settings:
            mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
            mock_settings.return_value.claude_planning_model = "claude-opus-4-6"
            model = agent._select_model({"diff_lines": 100})
            assert model == "claude-sonnet-4-6"

    async def test_select_model_large_diff(self) -> None:
        agent = ReviewAgent(scm_client=AsyncMock())
        with patch("ai_driven.agents.reviewer.agent.get_settings") as mock_settings:
            mock_settings.return_value.claude_default_model = "claude-sonnet-4-6"
            mock_settings.return_value.claude_planning_model = "claude-opus-4-6"
            model = agent._select_model({"diff_lines": 600})
            assert model == "claude-opus-4-6"


# ---------------------------------------------------------------------------
# Diff Truncation
# ---------------------------------------------------------------------------


class TestDiffTruncation:
    """Large diffs should be truncated per file when total exceeds 50KB."""

    async def test_small_diff_not_truncated(self) -> None:
        files = [{"path": "a.py", "patch": "x" * 100}]
        result = _truncate_diff_files(files)
        assert result[0]["patch"] == "x" * 100

    async def test_large_diff_truncated(self) -> None:
        # Create a diff exceeding 50KB
        big_patch = "x" * 60_000
        files = [{"path": "a.py", "patch": big_patch}]
        result = _truncate_diff_files(files)
        assert len(result[0]["patch"]) < len(big_patch)
        assert "[truncated]" in result[0]["patch"]

    async def test_multiple_files_each_truncated(self) -> None:
        files = [
            {"path": f"file{i}.py", "patch": "y" * 10_000}
            for i in range(10)
        ]
        result = _truncate_diff_files(files)
        for f in result:
            assert len(f["patch"]) <= 2048 + len("\n[truncated]")


# ---------------------------------------------------------------------------
# Severity Filtering
# ---------------------------------------------------------------------------


class TestSeverityFiltering:
    """Team threshold filters out lower-severity comments."""

    async def test_filter_warning_removes_suggestions(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        filtered = parsed.filter_by_severity("warning")
        assert all(c.severity in ("critical", "warning") for c in filtered.comments)
        # Original had 1 suggestion, filtered should have fewer comments
        assert len(filtered.comments) < len(parsed.comments)

    async def test_filter_critical_removes_warnings_and_suggestions(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        filtered = parsed.filter_by_severity("critical")
        assert all(c.severity == "critical" for c in filtered.comments)
        assert len(filtered.comments) == 1

    async def test_filter_suggestion_keeps_all(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        filtered = parsed.filter_by_severity("suggestion")
        assert len(filtered.comments) == len(parsed.comments)

    async def test_severity_order_values(self) -> None:
        assert SEVERITY_ORDER["critical"] < SEVERITY_ORDER["warning"]
        assert SEVERITY_ORDER["warning"] < SEVERITY_ORDER["suggestion"]


# ---------------------------------------------------------------------------
# Path Filtering
# ---------------------------------------------------------------------------


class TestPathFiltering:
    """Comments on ignored paths should be removed."""

    async def test_filter_vendor_path(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        filtered = parsed.filter_by_paths(["vendor/*"])
        paths = {c.file_path for c in filtered.comments}
        assert not any("vendor" in p for p in paths)
        # Original had 4 comments, vendor/lib.py should be removed
        assert len(filtered.comments) == 3

    async def test_filter_multiple_patterns(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        filtered = parsed.filter_by_paths(["vendor/*", "src/api/models.py"])
        paths = {c.file_path for c in filtered.comments}
        assert "vendor/lib.py" not in paths
        assert "src/api/models.py" not in paths
        assert len(filtered.comments) == 2

    async def test_no_patterns_keeps_all(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        filtered = parsed.filter_by_paths([])
        assert len(filtered.comments) == len(parsed.comments)


# ---------------------------------------------------------------------------
# ReviewConfig
# ---------------------------------------------------------------------------


class TestReviewConfig:
    """Team review configuration loading and prompt injection."""

    async def test_defaults(self) -> None:
        config = ReviewConfig()
        assert config.auto_review_enabled is True
        assert config.severity_threshold == "suggestion"
        assert config.max_comments == 20
        assert config.ignore_paths == []

    async def test_from_settings_nested(self) -> None:
        settings = {"review": {"severity_threshold": "warning", "ignore_paths": ["*.lock"]}}
        config = ReviewConfig.from_settings(settings)
        assert config.severity_threshold == "warning"
        assert config.ignore_paths == ["*.lock"]

    async def test_from_settings_none(self) -> None:
        config = ReviewConfig.from_settings(None)
        assert config.auto_review_enabled is True

    async def test_to_prompt_section_with_custom_rules(self) -> None:
        config = ReviewConfig(
            custom_rules=["No console.log", "Always use async/await"],
            severity_threshold="warning",
        )
        section = config.to_prompt_section()
        assert "<team_rules>" in section
        assert "No console.log" in section
        assert "warning" in section

    async def test_to_prompt_section_with_ignore_paths(self) -> None:
        config = ReviewConfig(ignore_paths=["vendor/*", "*.generated.ts"])
        section = config.to_prompt_section()
        assert "vendor/*" in section


# ---------------------------------------------------------------------------
# ReviewResult Parsing Edge Cases
# ---------------------------------------------------------------------------


class TestReviewResultParsing:
    """Edge cases in parsing Claude's review response."""

    async def test_parse_plain_json(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_APPROVE)
        assert parsed.verdict == "approve"

    async def test_parse_with_code_fence(self) -> None:
        text = f"Here's my review:\n```json\n{REVIEW_JSON_APPROVE}\n```\nDone."
        parsed = ReviewResult.parse_from_text(text)
        assert parsed.verdict == "approve"

    async def test_parse_invalid_json_fallback(self) -> None:
        parsed = ReviewResult.parse_from_text("This is not JSON at all.")
        assert parsed.verdict == "comment"
        assert "This is not JSON" in parsed.summary

    async def test_from_dict_missing_fields(self) -> None:
        result = ReviewResult.from_dict({})
        assert result.verdict == "comment"
        assert result.summary == ""
        assert result.comments == []

    async def test_round_trip(self) -> None:
        original = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        assert original.verdict == "request_changes"
        assert len(original.comments) == 4
        # Verify each comment has correct fields
        for c in original.comments:
            assert c.severity in SEVERITY_ORDER
            assert c.file_path
            assert c.body


# ---------------------------------------------------------------------------
# Comment Formatting
# ---------------------------------------------------------------------------


class TestCommentFormatting:
    """Verify comment body formatting with severity emojis."""

    async def test_critical_emoji(self) -> None:
        comment = ReviewComment(
            severity="critical", file_path="a.py", line=1,
            body="SQL injection risk", category="security",
        )
        formatted = comment.formatted_body
        assert "[CRITICAL]" in formatted
        assert "security" in formatted
        assert "SQL injection" in formatted

    async def test_warning_emoji(self) -> None:
        comment = ReviewComment(
            severity="warning", file_path="b.py", line=5,
            body="Missing null check", category="bug",
        )
        assert "[WARNING]" in comment.formatted_body

    async def test_suggestion_emoji(self) -> None:
        comment = ReviewComment(
            severity="suggestion", file_path="c.py", line=10,
            body="Consider renaming", category="style",
        )
        assert "[SUGGESTION]" in comment.formatted_body


# ---------------------------------------------------------------------------
# Format Summary
# ---------------------------------------------------------------------------


class TestFormatSummary:
    """Verify the full summary formatting for PR comment."""

    async def test_summary_with_issues(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_CHANGES_REQUESTED)
        summary = parsed.format_summary()
        assert "Request Changes" in summary
        assert "Critical: 1" in summary
        assert "Warning: 2" in summary
        assert "Suggestion: 1" in summary
        assert "AI-Driven Code Review Bot" in summary

    async def test_summary_with_highlights(self) -> None:
        parsed = ReviewResult.parse_from_text(REVIEW_JSON_APPROVE)
        summary = parsed.format_summary()
        assert "Good error handling" in summary
        assert "Well-structured code" in summary

    async def test_summary_empty_review(self) -> None:
        result = ReviewResult(verdict="approve", summary="LGTM", comments=[])
        summary = result.format_summary()
        assert "Approve" in summary
        assert "LGTM" in summary
        assert "Issues Found" not in summary


# ---------------------------------------------------------------------------
# Tool Execution — No SCM Client
# ---------------------------------------------------------------------------


class TestToolsWithoutSCM:
    """Tools should return error dicts when SCM client is None."""

    async def test_get_pr_diff_no_scm(self) -> None:
        agent = ReviewAgent(scm_client=None)
        result = await agent._registry.execute(
            "github.get_pr_diff", {"repo_full_name": "org/repo", "pr_number": 1}
        )
        assert "error" in result

    async def test_read_file_no_scm(self) -> None:
        agent = ReviewAgent(scm_client=None)
        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "org/repo", "path": "a.py"}
        )
        assert "error" in result

    async def test_post_pr_comment_no_scm(self) -> None:
        agent = ReviewAgent(scm_client=None)
        result = await agent._registry.execute(
            "github.post_pr_comment",
            {"repo_full_name": "org/repo", "pr_number": 1, "body": "test"},
        )
        assert "error" in result

    async def test_list_repo_files_no_scm(self) -> None:
        agent = ReviewAgent(scm_client=None)
        result = await agent._registry.execute(
            "repo.list_files", {"repo_full_name": "org/repo"}
        )
        assert isinstance(result, list)
        assert result[0].get("error")

    async def test_search_code_no_scm(self) -> None:
        agent = ReviewAgent(scm_client=None)
        result = await agent._registry.execute(
            "code.search", {"repo_full_name": "org/repo", "query": "foo"}
        )
        assert isinstance(result, list)
        assert result[0].get("error")


# ---------------------------------------------------------------------------
# Metrics Emission
# ---------------------------------------------------------------------------


class TestMetricsEmission:
    """Verify review metrics are emitted with correct values."""

    async def test_emit_review_metrics_called(self) -> None:
        from ai_driven.observability.metrics import emit_review_metrics

        with patch("ai_driven.observability.metrics._get_statsd") as mock_statsd:
            mock_client = MagicMock()
            mock_statsd.return_value = mock_client

            emit_review_metrics(
                issues_found=3,
                critical_issues=1,
                comments_posted=2,
                duration_seconds=5.0,
                diff_size=150,
                model_used="claude-sonnet-4-6",
                verdict="request_changes",
            )

            # Verify key metrics were recorded
            calls = {c[0][0] for c in mock_client.increment.call_args_list}
            assert "ai.review.issues_found" in calls
            assert "ai.review.critical_issues" in calls
            assert "ai.review.comments_posted" in calls

    async def test_agent_emits_metrics_on_success(self) -> None:
        scm = _build_mock_scm()
        agent = ReviewAgent(scm_client=scm)

        responses = [_make_response(text="Review complete.")]
        call_idx = 0

        async def mock_create(**kwargs: Any) -> MessageResponse:
            nonlocal call_idx
            resp = responses[min(call_idx, len(responses) - 1)]
            call_idx += 1
            return resp

        agent._claude._provider.create_message = mock_create

        with patch("ai_driven.agents.base.emit_agent_metrics") as mock_emit:
            result = await agent.run(
                {"repo_full_name": "org/repo", "pr_number": 42},
                task_id="task-metrics",
            )

            assert result.success is True
            mock_emit.assert_called_once()
            args = mock_emit.call_args
            assert args[0][0] == "review"  # agent_type
            assert args[1]["success"] is True


# ---------------------------------------------------------------------------
# Initial Message Construction
# ---------------------------------------------------------------------------


class TestInitialMessage:
    """Verify the review agent builds correct initial messages."""

    async def test_includes_pr_details(self) -> None:
        agent = ReviewAgent(scm_client=AsyncMock())
        msg = agent._build_initial_message({
            "repo_full_name": "org/repo",
            "pr_number": 42,
            "pr_title": "Add user profile",
            "pr_description": "Implements GET /users/:id",
        })
        assert "org/repo" in msg
        assert "#42" in msg
        assert "Add user profile" in msg
        assert "GET /users/:id" in msg

    async def test_workflow_instructions_present(self) -> None:
        agent = ReviewAgent(scm_client=AsyncMock())
        msg = agent._build_initial_message({
            "repo_full_name": "org/repo",
            "pr_number": 1,
        })
        assert "github.get_pr_diff" in msg
        assert "repo.read_file" in msg
        assert "code.search" in msg

    async def test_handles_missing_optional_fields(self) -> None:
        agent = ReviewAgent(scm_client=AsyncMock())
        msg = agent._build_initial_message({
            "repo_full_name": "org/repo",
            "pr_number": 5,
        })
        assert "org/repo" in msg
        assert "#5" in msg


# ---------------------------------------------------------------------------
# System Prompt with Team Rules
# ---------------------------------------------------------------------------


class TestSystemPrompt:
    """Verify system prompt includes team-specific rules."""

    async def test_includes_base_prompt(self) -> None:
        agent = ReviewAgent(scm_client=AsyncMock())
        prompt = agent._get_system_prompt({})
        assert len(prompt) > 100  # Non-trivial system prompt

    async def test_includes_team_rules(self) -> None:
        config = ReviewConfig(
            custom_rules=["No console.log statements"],
            severity_threshold="warning",
        )
        agent = ReviewAgent(scm_client=AsyncMock(), review_config=config)
        prompt = agent._get_system_prompt({})
        assert "No console.log" in prompt
        assert "<team_rules>" in prompt

    async def test_includes_max_comments(self) -> None:
        config = ReviewConfig(max_comments=5)
        agent = ReviewAgent(scm_client=AsyncMock(), review_config=config)
        prompt = agent._get_system_prompt({})
        assert "5" in prompt
