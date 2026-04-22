"""Integration tests for the coding agent end-to-end flow."""

from __future__ import annotations

import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.coding.agent import CodingAgent
from ai_driven.claude.provider import MessageResponse, TextBlock, TokenUsage, ToolUseBlock


def _make_text_response(text: str) -> MessageResponse:
    return MessageResponse(
        model="claude-sonnet-4-6",
        content=[TextBlock(type="text", text=text)],
        stop_reason="end_turn",
        usage=TokenUsage(input_tokens=100, output_tokens=50),
    )


def _make_tool_response(tool_calls: list[dict]) -> MessageResponse:
    blocks = [
        ToolUseBlock(id=f"tool-{i}", name=tc["name"], input=tc["input"])
        for i, tc in enumerate(tool_calls)
    ]
    raw = [{"type": "tool_use", "id": f"tool-{i}", "name": tc["name"], "input": tc["input"]}
           for i, tc in enumerate(tool_calls)]
    return MessageResponse(
        model="claude-sonnet-4-6",
        content=blocks,
        stop_reason="tool_use",
        usage=TokenUsage(input_tokens=200, output_tokens=100),
        raw_content=raw,
    )


class TestCodingFlowWithMockedClaude:
    """Test the full coding agent flow with mocked Claude and real tool dispatch."""

    @pytest.fixture
    def jira_client(self) -> MagicMock:
        client = MagicMock()
        issue = MagicMock()
        issue.key = "TEST-42"
        issue.summary = "Add health check endpoint"
        issue.description = "Add a /healthz endpoint that returns 200 OK"
        issue.status = "In Progress"
        issue.acceptance_criteria = "GET /healthz returns 200 with JSON body"
        issue.labels = ["backend"]
        issue.subtasks = []
        issue.comments = []
        client.get_issue = AsyncMock(return_value=issue)
        client.add_comment = AsyncMock(return_value=MagicMock(id="c-1"))
        client.transition_issue = AsyncMock()
        return client

    @pytest.fixture
    def scm_client(self) -> MagicMock:
        client = MagicMock()

        # list_files returns some directory entries
        entry = MagicMock()
        entry.path = "src/main.py"
        entry.name = "main.py"
        entry.is_directory = False
        entry.size = 500
        client.list_files = AsyncMock(return_value=[entry])

        # read_file returns file content
        file = MagicMock()
        file.path = "src/main.py"
        file.content = "from fastapi import FastAPI\napp = FastAPI()\n"
        file.size = 42
        client.read_file = AsyncMock(return_value=file)

        # search_code returns results
        client.search_code = AsyncMock(return_value=[])

        # create_branch
        client.create_branch = AsyncMock()

        # create_or_update_file
        client.create_or_update_file = AsyncMock(return_value={})

        # create_pull_request
        pr = MagicMock()
        pr.number = 10
        pr.url = "https://github.com/org/repo/pull/10"
        pr.title = "TEST-42: Add health check endpoint"
        client.create_pull_request = AsyncMock(return_value=pr)

        return client

    async def test_agent_calls_tools_in_sequence(
        self, jira_client: MagicMock, scm_client: MagicMock,
    ) -> None:
        """Simulate Claude calling tools in the expected order."""
        # Claude's first response: call get_jira_issue
        resp1 = _make_tool_response([
            {"name": "jira.get_issue", "input": {"issue_key": "TEST-42"}},
        ])

        # Claude's second response: call list_repo_files + read_file
        resp2 = _make_tool_response([
            {"name": "repo.list_files", "input": {"repo_full_name": "org/repo"}},
        ])

        # Claude's third response: call create_pull_request
        resp3 = _make_tool_response([
            {
                "name": "github.create_pr",
                "input": {
                    "repo_full_name": "org/repo",
                    "title": "TEST-42: Add health check",
                    "body": "Added /healthz endpoint",
                    "source_branch": "feature/TEST-42",
                },
            },
        ])

        # Claude's fourth response: add jira comment and final text
        resp4 = _make_tool_response([
            {
                "name": "jira.add_comment",
                "input": {"issue_key": "TEST-42", "body": "PR created: #10"},
            },
        ])

        # Claude's final response: text summary
        resp5 = _make_text_response(
            "I have implemented the health check endpoint and created PR #10."
        )

        # Mock the provider to return responses in sequence
        mock_provider = MagicMock()
        mock_provider.create_message = AsyncMock(
            side_effect=[resp1, resp2, resp3, resp4, resp5]
        )

        agent = CodingAgent(
            jira_client=jira_client,
            scm_client=scm_client,
        )
        # Replace the provider in the claude client
        agent._claude._provider = mock_provider

        result = await agent.run(
            {"jira_key": "TEST-42", "repo_full_name": "org/repo"},
            max_iterations=10,
        )

        assert result.success is True
        assert result.tool_calls >= 4  # At least: get_jira_issue, list_files, create_pr, add_comment
        assert result.iterations == 5  # 5 API calls

        # Verify tool calls happened
        jira_client.get_issue.assert_called_once_with("TEST-42")
        scm_client.list_files.assert_called_once()
        scm_client.create_pull_request.assert_called_once()
        jira_client.add_comment.assert_called_once()

    async def test_agent_handles_tool_error(
        self, jira_client: MagicMock, scm_client: MagicMock,
    ) -> None:
        """Agent should handle tool execution errors gracefully."""
        # Jira client raises an error
        jira_client.get_issue = AsyncMock(side_effect=Exception("Jira unavailable"))

        # Claude calls get_jira_issue which will fail
        resp1 = _make_tool_response([
            {"name": "jira.get_issue", "input": {"issue_key": "TEST-42"}},
        ])

        # Claude handles the error and stops
        resp2 = _make_text_response("I was unable to fetch the Jira ticket due to an error.")

        mock_provider = MagicMock()
        mock_provider.create_message = AsyncMock(side_effect=[resp1, resp2])

        agent = CodingAgent(
            jira_client=jira_client,
            scm_client=scm_client,
        )
        agent._claude._provider = mock_provider

        result = await agent.run(
            {"jira_key": "TEST-42", "repo_full_name": "org/repo"},
            max_iterations=10,
        )

        # Agent completes (Claude handles the error message)
        assert result.success is True
        assert result.iterations == 2

    async def test_agent_max_iterations(self) -> None:
        """Agent should stop after max_iterations even if Claude keeps requesting tools."""
        tool_resp = _make_tool_response([
            {"name": "jira.get_issue", "input": {"issue_key": "TEST-1"}},
        ])

        mock_provider = MagicMock()
        # Always return tool use (infinite loop scenario)
        mock_provider.create_message = AsyncMock(return_value=tool_resp)

        jira_client = MagicMock()
        jira_client.get_issue = AsyncMock(return_value=MagicMock(
            key="TEST-1", summary="test", description="", status="",
            acceptance_criteria="", labels=[], subtasks=[],
        ))

        agent = CodingAgent(jira_client=jira_client)
        agent._claude._provider = mock_provider

        result = await agent.run(
            {"jira_key": "TEST-1", "repo_full_name": "org/repo"},
            max_iterations=3,
        )

        # Should have made exactly 3 iterations
        assert result.iterations == 3
        assert mock_provider.create_message.call_count == 3
