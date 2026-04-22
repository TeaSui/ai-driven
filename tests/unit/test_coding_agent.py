"""Unit tests for the CodingAgent."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.coding.agent import CodingAgent
from ai_driven.claude.prompts.coding_agent import CODING_AGENT_SYSTEM_PROMPT


class TestToolRegistration:
    """Verify all expected tools are registered."""

    def setup_method(self) -> None:
        self.agent = CodingAgent(jira_client=MagicMock(), scm_client=MagicMock())

    def test_core_tools_registered(self) -> None:
        names = self.agent._registry.tool_names
        expected = [
            "jira.get_issue",
            "jira.add_comment",
            "repo.list_files",
            "repo.read_file",
            "code.search",
            "github.create_pr",
            "jira.update_status",
            "file.write",
            "git.create_branch",
            "sandbox.execute",
        ]
        for tool in expected:
            assert tool in names, f"Tool '{tool}' not registered"

    def test_tool_count_at_least_10(self) -> None:
        """At least the 10 tools should be registered."""
        assert len(self.agent._registry.tool_names) >= 10


class TestSystemPrompt:
    """Verify system prompt setup."""

    def test_returns_coding_system_prompt(self) -> None:
        agent = CodingAgent()
        prompt = agent._get_system_prompt({})
        assert prompt == CODING_AGENT_SYSTEM_PROMPT

    def test_system_prompt_contains_phases(self) -> None:
        assert "PHASE 1" in CODING_AGENT_SYSTEM_PROMPT
        assert "PHASE 2" in CODING_AGENT_SYSTEM_PROMPT
        assert "PHASE 3" in CODING_AGENT_SYSTEM_PROMPT
        assert "PHASE 4" in CODING_AGENT_SYSTEM_PROMPT


class TestBuildInitialMessage:
    """Verify initial message construction."""

    def test_includes_jira_key_and_repo(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message({
            "jira_key": "PROJ-123",
            "repo_full_name": "org/repo",
        })
        assert "PROJ-123" in msg
        assert "org/repo" in msg

    def test_includes_extra_instructions(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message({
            "jira_key": "PROJ-1",
            "repo_full_name": "org/repo",
            "instructions": "Use TypeScript",
        })
        assert "Use TypeScript" in msg

    def test_handles_empty_input(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message({})
        assert isinstance(msg, str)


class TestToolExecution:
    """Test individual tool handlers with mocked clients."""

    @pytest.fixture
    def jira_client(self) -> MagicMock:
        client = MagicMock()
        issue = MagicMock()
        issue.key = "TEST-1"
        issue.summary = "Test issue"
        issue.description = "Description"
        issue.status = "To Do"
        issue.acceptance_criteria = "Must work"
        issue.labels = ["backend"]
        issue.subtasks = []
        client.get_issue = AsyncMock(return_value=issue)
        client.add_comment = AsyncMock(return_value=MagicMock(id="comment-1"))
        client.transition_issue = AsyncMock()
        return client

    @pytest.fixture
    def scm_client(self) -> MagicMock:
        client = MagicMock()
        return client

    @pytest.fixture
    def agent(self, jira_client: MagicMock, scm_client: MagicMock) -> CodingAgent:
        return CodingAgent(jira_client=jira_client, scm_client=scm_client)

    async def test_get_jira_issue_tool(self, agent: CodingAgent, jira_client: MagicMock) -> None:
        result = await agent._registry.execute("jira.get_issue", {"issue_key": "TEST-1"})
        jira_client.get_issue.assert_called_once_with("TEST-1")
        assert result["key"] == "TEST-1"
        assert result["summary"] == "Test issue"

    async def test_add_jira_comment_tool(self, agent: CodingAgent, jira_client: MagicMock) -> None:
        result = await agent._registry.execute(
            "jira.add_comment", {"issue_key": "TEST-1", "body": "Progress update"}
        )
        jira_client.add_comment.assert_called_once_with("TEST-1", "Progress update")
        assert result["id"] == "comment-1"
        assert result["status"] == "posted"

    async def test_get_jira_issue_no_client(self) -> None:
        agent = CodingAgent(jira_client=None)
        result = await agent._registry.execute("jira.get_issue", {"issue_key": "TEST-1"})
        assert "error" in result

    async def test_list_files_no_client(self) -> None:
        agent = CodingAgent(scm_client=None)
        result = await agent._registry.execute(
            "repo.list_files", {"repo_full_name": "org/repo"}
        )
        assert result[0].get("error")

    async def test_read_file_tool(self, agent: CodingAgent, scm_client: MagicMock) -> None:
        file_mock = MagicMock()
        file_mock.path = "src/main.py"
        file_mock.content = "print('hello')"
        file_mock.size = 14
        scm_client.read_file = AsyncMock(return_value=file_mock)

        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "org/repo", "path": "src/main.py"}
        )
        assert result["path"] == "src/main.py"
        assert result["content"] == "print('hello')"

    async def test_search_code_tool(self, agent: CodingAgent, scm_client: MagicMock) -> None:
        file_mock = MagicMock()
        file_mock.path = "src/utils.py"
        file_mock.content = "def helper(): pass"
        scm_client.search_code = AsyncMock(return_value=[file_mock])

        result = await agent._registry.execute(
            "code.search", {"repo_full_name": "org/repo", "query": "helper"}
        )
        assert len(result) == 1
        assert result[0]["path"] == "src/utils.py"

    async def test_create_pr_tool(self, agent: CodingAgent, scm_client: MagicMock) -> None:
        pr_mock = MagicMock()
        pr_mock.number = 42
        pr_mock.url = "https://github.com/org/repo/pull/42"
        pr_mock.title = "feat: add feature"
        scm_client.create_pull_request = AsyncMock(return_value=pr_mock)

        result = await agent._registry.execute(
            "github.create_pr",
            {
                "repo_full_name": "org/repo",
                "title": "feat: add feature",
                "body": "Description",
                "source_branch": "feature/TEST-1",
            },
        )
        assert result["number"] == 42
        assert "github.com" in result["url"]


class TestUpdateJiraStatusTool:
    """Test update_jira_status tool."""

    @pytest.fixture
    def jira_client(self) -> MagicMock:
        client = MagicMock()
        client.transition_issue = AsyncMock()
        return client

    async def test_transitions_issue(self, jira_client: MagicMock) -> None:
        agent = CodingAgent(jira_client=jira_client, scm_client=MagicMock())
        result = await agent._registry.execute(
            "jira.update_status",
            {"issue_key": "PROJ-123", "transition_name": "In Progress"},
        )
        jira_client.transition_issue.assert_awaited_once_with("PROJ-123", "In Progress")
        assert result == {"status": "transitioned", "to": "In Progress"}

    async def test_returns_error_without_jira_client(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "jira.update_status",
            {"issue_key": "PROJ-1", "transition_name": "Done"},
        )
        assert result == {"error": "Jira client not configured"}


class TestWriteFileTool:
    """Test write_file tool."""

    async def test_commits_file_via_scm(self) -> None:
        scm = MagicMock()
        scm.create_or_update_file = AsyncMock()
        agent = CodingAgent(jira_client=None, scm_client=scm)

        result = await agent._registry.execute(
            "file.write",
            {
                "repo_full_name": "org/repo",
                "path": "src/main.py",
                "content": "print('hello')",
                "commit_message": "Add main.py",
                "branch": "feature/PROJ-1",
            },
        )
        scm.create_or_update_file.assert_awaited_once_with(
            "org/repo", "src/main.py", "print('hello')", "Add main.py", "feature/PROJ-1",
        )
        assert result == {"status": "committed", "path": "src/main.py"}

    async def test_returns_error_without_scm_client(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "file.write",
            {
                "repo_full_name": "org/repo",
                "path": "f.py",
                "content": "",
                "commit_message": "m",
                "branch": "b",
            },
        )
        assert result == {"error": "SCM client not configured"}


class TestCreateBranchTool:
    """Test create_branch tool."""

    async def test_creates_branch_default_ref(self) -> None:
        scm = MagicMock()
        scm.create_branch = AsyncMock()
        agent = CodingAgent(jira_client=None, scm_client=scm)

        result = await agent._registry.execute(
            "git.create_branch",
            {"repo_full_name": "org/repo", "branch_name": "feature/PROJ-1"},
        )
        scm.create_branch.assert_awaited_once_with("org/repo", "feature/PROJ-1", "main")
        assert result == {"status": "created", "branch": "feature/PROJ-1"}

    async def test_creates_branch_custom_ref(self) -> None:
        scm = MagicMock()
        scm.create_branch = AsyncMock()
        agent = CodingAgent(jira_client=None, scm_client=scm)

        result = await agent._registry.execute(
            "git.create_branch",
            {"repo_full_name": "org/repo", "branch_name": "feature/X", "from_ref": "develop"},
        )
        scm.create_branch.assert_awaited_once_with("org/repo", "feature/X", "develop")
        assert result == {"status": "created", "branch": "feature/X"}

    async def test_returns_error_without_scm_client(self) -> None:
        agent = CodingAgent(jira_client=None, scm_client=None)
        result = await agent._registry.execute(
            "git.create_branch",
            {"repo_full_name": "org/repo", "branch_name": "b"},
        )
        assert result == {"error": "SCM client not configured"}


class TestReadFileTruncation:
    """Test that large files are truncated at 10KB."""

    async def test_small_file_not_truncated(self) -> None:
        scm = MagicMock()
        file_mock = MagicMock()
        file_mock.path = "small.py"
        file_mock.content = "x" * 5000
        file_mock.size = 5000
        scm.read_file = AsyncMock(return_value=file_mock)
        agent = CodingAgent(jira_client=None, scm_client=scm)

        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "org/repo", "path": "small.py"},
        )
        assert result["content"] == "x" * 5000

    async def test_large_file_truncated_at_10kb(self) -> None:
        scm = MagicMock()
        file_mock = MagicMock()
        file_mock.path = "big.py"
        file_mock.content = "y" * 20000
        file_mock.size = 20000
        scm.read_file = AsyncMock(return_value=file_mock)
        agent = CodingAgent(jira_client=None, scm_client=scm)

        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "org/repo", "path": "big.py"},
        )
        assert result["content"].startswith("y" * 10240)
        assert "[... truncated, 20000 bytes total]" in result["content"]
        assert result["size"] == 20000

    async def test_file_at_boundary_not_truncated(self) -> None:
        scm = MagicMock()
        file_mock = MagicMock()
        file_mock.path = "boundary.py"
        file_mock.content = "z" * 10240
        file_mock.size = 10240
        scm.read_file = AsyncMock(return_value=file_mock)
        agent = CodingAgent(jira_client=None, scm_client=scm)

        result = await agent._registry.execute(
            "repo.read_file", {"repo_full_name": "org/repo", "path": "boundary.py"},
        )
        # size == 10240, not > 10240, so no truncation
        assert result["content"] == "z" * 10240


class TestAgentRun:
    """Test the full agent.run() flow with mocked Claude client."""

    async def test_run_success(self) -> None:
        mock_response = MagicMock()
        mock_response.text = "I have completed the implementation."
        mock_response.has_tool_use = False
        mock_response.usage = MagicMock(input_tokens=100, output_tokens=50)

        with patch("ai_driven.agents.base.ClaudeClient") as mock_claude_cls:
            mock_claude = MagicMock()
            mock_claude.run_agent_loop = AsyncMock(return_value=mock_response)
            mock_claude_cls.return_value = mock_claude

            agent = CodingAgent(claude_client=mock_claude)
            result = await agent.run({"jira_key": "TEST-1", "repo_full_name": "org/repo"})

        assert result.success is True
        assert "completed" in result.output.lower()

    async def test_run_failure(self) -> None:
        with patch("ai_driven.agents.base.ClaudeClient") as mock_claude_cls:
            mock_claude = MagicMock()
            mock_claude.run_agent_loop = AsyncMock(side_effect=RuntimeError("API error"))
            mock_claude_cls.return_value = mock_claude

            agent = CodingAgent(claude_client=mock_claude)
            result = await agent.run({"jira_key": "TEST-1", "repo_full_name": "org/repo"})

        assert result.success is False
        assert "API error" in result.error
