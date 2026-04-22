"""Integration tests for the review agent end-to-end flow."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.agents.reviewer.agent import ReviewAgent
from ai_driven.claude.provider import MessageResponse, TextBlock, TokenUsage, ToolUseBlock


def _make_text_response(text: str) -> MessageResponse:
    return MessageResponse(
        model="claude-sonnet-4-6",
        content=[TextBlock(text=text)],
        stop_reason="end_turn",
        usage=TokenUsage(input_tokens=100, output_tokens=50),
    )


def _make_tool_response(tool_calls: list[dict]) -> MessageResponse:
    blocks = [
        ToolUseBlock(id=f"tool-{i}", name=tc["name"], input=tc["input"])
        for i, tc in enumerate(tool_calls)
    ]
    raw = [
        {"type": "tool_use", "id": f"tool-{i}", "name": tc["name"], "input": tc["input"]}
        for i, tc in enumerate(tool_calls)
    ]
    return MessageResponse(
        model="claude-sonnet-4-6",
        content=blocks,
        stop_reason="tool_use",
        usage=TokenUsage(input_tokens=200, output_tokens=100),
        raw_content=raw,
    )


class TestReviewFlowWithMockedClaude:
    """Test the full review agent flow with mocked Claude and real tool dispatch."""

    @pytest.fixture
    def scm_client(self) -> MagicMock:
        client = MagicMock()

        # get_pr_diff returns a diff with one changed file
        diff_file = MagicMock()
        diff_file.path = "src/handler.py"
        diff_file.status = "modified"
        diff_file.additions = 15
        diff_file.deletions = 2
        diff_file.patch = "@@ -10,5 +10,18 @@\n+    password = 'secret123'"
        diff_mock = MagicMock()
        diff_mock.files = [diff_file]
        diff_mock.total_additions = 15
        diff_mock.total_deletions = 2
        client.get_pr_diff = AsyncMock(return_value=diff_mock)

        # read_file returns file content
        file_mock = MagicMock()
        file_mock.path = "src/handler.py"
        file_mock.content = "from flask import Flask\napp = Flask(__name__)\n"
        client.read_file = AsyncMock(return_value=file_mock)

        # post_pr_comment returns a comment object
        comment_mock = MagicMock()
        comment_mock.id = "comment-42"
        client.post_pr_comment = AsyncMock(return_value=comment_mock)

        return client

    async def test_review_agent_calls_tools_in_sequence(
        self, scm_client: MagicMock,
    ) -> None:
        """Simulate Claude calling get_pr_diff -> read_file -> post_pr_comment -> summary."""
        # Step 1: Claude requests the diff
        resp1 = _make_tool_response([
            {"name": "github.get_pr_diff", "input": {"repo_full_name": "org/repo", "pr_number": 7}},
        ])

        # Step 2: Claude reads a file for context
        resp2 = _make_tool_response([
            {"name": "repo.read_file", "input": {"repo_full_name": "org/repo", "path": "src/handler.py"}},
        ])

        # Step 3: Claude posts an inline review comment
        resp3 = _make_tool_response([
            {
                "name": "github.post_pr_comment",
                "input": {
                    "repo_full_name": "org/repo",
                    "pr_number": 7,
                    "body": "Critical: Hardcoded password on line 11.",
                    "file_path": "src/handler.py",
                    "line": 11,
                },
            },
        ])

        # Step 4: Claude returns the summary text
        resp4 = _make_text_response(
            "Review complete. Found 1 critical issue: hardcoded password in src/handler.py."
        )

        mock_provider = MagicMock()
        mock_provider.create_message = AsyncMock(
            side_effect=[resp1, resp2, resp3, resp4],
        )

        agent = ReviewAgent(scm_client=scm_client)
        agent._claude._provider = mock_provider

        result = await agent.run(
            {"repo_full_name": "org/repo", "pr_number": 7},
            max_iterations=10,
        )

        assert result.success is True
        assert result.tool_calls >= 3
        assert result.iterations == 4

        # Verify the tools were actually called on the SCM client
        scm_client.get_pr_diff.assert_called_once_with("org/repo", 7)
        scm_client.read_file.assert_called_once()
        scm_client.post_pr_comment.assert_called_once()

    async def test_review_handles_empty_diff(self, scm_client: MagicMock) -> None:
        """When the diff has no files, Claude should still produce a summary."""
        # Override the diff to return empty files
        empty_diff = MagicMock()
        empty_diff.files = []
        empty_diff.total_additions = 0
        empty_diff.total_deletions = 0
        scm_client.get_pr_diff = AsyncMock(return_value=empty_diff)

        # Step 1: Claude requests the diff
        resp1 = _make_tool_response([
            {"name": "github.get_pr_diff", "input": {"repo_full_name": "org/repo", "pr_number": 3}},
        ])

        # Step 2: Claude sees empty diff and returns summary directly
        resp2 = _make_text_response(
            "The pull request has no file changes. Nothing to review."
        )

        mock_provider = MagicMock()
        mock_provider.create_message = AsyncMock(side_effect=[resp1, resp2])

        agent = ReviewAgent(scm_client=scm_client)
        agent._claude._provider = mock_provider

        result = await agent.run(
            {"repo_full_name": "org/repo", "pr_number": 3},
            max_iterations=10,
        )

        assert result.success is True
        assert result.iterations == 2
        scm_client.get_pr_diff.assert_called_once_with("org/repo", 3)
        # No review comments should have been posted
        scm_client.post_pr_comment.assert_not_called()
