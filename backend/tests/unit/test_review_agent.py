"""Unit tests for the ReviewAgent."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.agents.reviewer.agent import ReviewAgent
from ai_driven.claude.prompts.review_agent import REVIEW_AGENT_SYSTEM_PROMPT


class TestToolRegistration:
    """Verify all expected tools are registered."""

    def setup_method(self) -> None:
        self.agent = ReviewAgent(scm_client=MagicMock())

    def test_core_tools_registered(self) -> None:
        names = self.agent._registry.tool_names
        expected = ["github.get_pr_diff", "repo.read_file", "github.post_pr_comment"]
        for tool in expected:
            assert tool in names, f"Tool '{tool}' not registered"

    def test_tool_count_at_least_3(self) -> None:
        assert len(self.agent._registry.tool_names) >= 3


class TestSystemPrompt:
    """Verify system prompt setup."""

    def test_returns_review_system_prompt_with_team_rules(self) -> None:
        agent = ReviewAgent()
        prompt = agent._get_system_prompt({})
        # Prompt starts with the base review system prompt
        assert prompt.startswith(REVIEW_AGENT_SYSTEM_PROMPT)
        # And appends team rules section
        assert "<team_rules>" in prompt

    def test_system_prompt_contains_review_keywords(self) -> None:
        assert "review" in REVIEW_AGENT_SYSTEM_PROMPT.lower()
        assert "security" in REVIEW_AGENT_SYSTEM_PROMPT.lower()
        assert "comment" in REVIEW_AGENT_SYSTEM_PROMPT.lower()
        assert "diff" in REVIEW_AGENT_SYSTEM_PROMPT.lower()

    def test_system_prompt_contains_severity_levels(self) -> None:
        prompt_lower = REVIEW_AGENT_SYSTEM_PROMPT.lower()
        assert "critical" in prompt_lower
        assert "warning" in prompt_lower
        assert "suggestion" in prompt_lower


class TestBuildInitialMessage:
    """Verify initial message construction."""

    def test_includes_repo_and_pr_number(self) -> None:
        agent = ReviewAgent()
        msg = agent._build_initial_message({
            "repo_full_name": "org/repo",
            "pr_number": 42,
        })
        assert "org/repo" in msg
        assert "42" in msg

    def test_handles_empty_input(self) -> None:
        agent = ReviewAgent()
        msg = agent._build_initial_message({})
        assert isinstance(msg, str)


class TestToolExecution:
    """Test individual tool handlers with mocked SCM client."""

    @pytest.fixture
    def scm_client(self) -> MagicMock:
        client = MagicMock()
        return client

    @pytest.fixture
    def agent(self, scm_client: MagicMock) -> ReviewAgent:
        return ReviewAgent(scm_client=scm_client)

    async def test_get_pr_diff_returns_structured_data(
        self, agent: ReviewAgent, scm_client: MagicMock,
    ) -> None:
        diff_file = MagicMock()
        diff_file.path = "src/main.py"
        diff_file.status = "modified"
        diff_file.additions = 10
        diff_file.deletions = 3
        diff_file.patch = "@@ -1,5 +1,12 @@"
        diff_mock = MagicMock()
        diff_mock.files = [diff_file]
        diff_mock.total_additions = 10
        diff_mock.total_deletions = 3
        scm_client.get_pr_diff = AsyncMock(return_value=diff_mock)

        result = await agent._registry.execute(
            "github.get_pr_diff", {"repo_full_name": "org/repo", "pr_number": 1},
        )
        assert len(result["files"]) == 1
        assert result["files"][0]["path"] == "src/main.py"
        assert result["files"][0]["status"] == "modified"
        assert result["files"][0]["additions"] == 10
        assert result["files"][0]["deletions"] == 3
        assert result["total_additions"] == 10
        assert result["total_deletions"] == 3

    async def test_read_file_returns_content(
        self, agent: ReviewAgent, scm_client: MagicMock,
    ) -> None:
        file_mock = MagicMock()
        file_mock.path = "src/utils.py"
        file_mock.content = "def helper(): pass"
        scm_client.read_file = AsyncMock(return_value=file_mock)

        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "org/repo", "path": "src/utils.py"},
        )
        assert result["path"] == "src/utils.py"
        assert result["content"] == "def helper(): pass"

    async def test_post_pr_comment_returns_comment_id(
        self, agent: ReviewAgent, scm_client: MagicMock,
    ) -> None:
        comment_mock = MagicMock()
        comment_mock.id = "comment-99"
        scm_client.post_pr_comment = AsyncMock(return_value=comment_mock)

        result = await agent._registry.execute(
            "github.post_pr_comment",
            {"repo_full_name": "org/repo", "pr_number": 5, "body": "Looks good!"},
        )
        assert result["id"] == "comment-99"
        assert result["status"] == "posted"
        scm_client.post_pr_comment.assert_called_once_with(
            "org/repo", 5, "Looks good!", None, None,
        )

    async def test_get_pr_diff_error_when_no_scm(self) -> None:
        agent = ReviewAgent(scm_client=None)
        result = await agent._registry.execute(
            "github.get_pr_diff", {"repo_full_name": "org/repo", "pr_number": 1},
        )
        assert "error" in result

    async def test_read_file_error_when_no_scm(self) -> None:
        agent = ReviewAgent(scm_client=None)
        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "org/repo", "path": "f.py"},
        )
        assert "error" in result

    async def test_post_pr_comment_error_when_no_scm(self) -> None:
        agent = ReviewAgent(scm_client=None)
        result = await agent._registry.execute(
            "github.post_pr_comment",
            {"repo_full_name": "org/repo", "pr_number": 1, "body": "hi"},
        )
        assert "error" in result
