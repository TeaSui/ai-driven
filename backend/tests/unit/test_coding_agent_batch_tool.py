"""Verify CodingAgent exposes file.write_batch and wires it to the SCM client."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.agents.coding.agent import CodingAgent


class TestBatchToolRegistration:
    def test_file_write_batch_tool_registered(self) -> None:
        agent = CodingAgent(jira_client=MagicMock(), scm_client=MagicMock())
        assert "file.write_batch" in agent._registry.tool_names

    def test_file_write_batch_schema_shape(self) -> None:
        agent = CodingAgent(jira_client=MagicMock(), scm_client=MagicMock())
        tool = agent._registry.get_tool("file.write_batch")
        assert tool is not None
        schema = tool.schema["input_schema"]
        props = schema["properties"]
        assert set(schema["required"]) == {
            "repo_full_name", "files", "commit_message", "branch",
        }
        assert props["files"]["type"] == "array"
        assert props["files"]["items"]["required"] == ["path", "content"]
        assert props["files"]["minItems"] == 1


class TestBatchToolInvocation:
    async def test_tool_calls_scm_batch_method(self) -> None:
        scm = MagicMock()
        scm.create_or_update_files_batch = AsyncMock(
            return_value={"sha": "abc1234", "files": ["a.py", "b.py"]}
        )
        agent = CodingAgent(jira_client=MagicMock(), scm_client=scm)
        tool = agent._registry.get_tool("file.write_batch")
        assert tool is not None

        result = await tool.handler(
            repo_full_name="owner/repo",
            files=[
                {"path": "a.py", "content": "print(1)"},
                {"path": "b.py", "content": "print(2)"},
            ],
            commit_message="feat: two files",
            branch="feat/x",
        )

        scm.create_or_update_files_batch.assert_awaited_once_with(
            "owner/repo",
            [
                {"path": "a.py", "content": "print(1)"},
                {"path": "b.py", "content": "print(2)"},
            ],
            "feat: two files",
            "feat/x",
        )
        assert result["status"] == "committed"
        assert result["count"] == 2
        assert result["paths"] == ["a.py", "b.py"]

    async def test_tool_returns_error_without_scm(self) -> None:
        agent = CodingAgent(jira_client=MagicMock(), scm_client=None)
        tool = agent._registry.get_tool("file.write_batch")
        assert tool is not None
        result = await tool.handler(
            repo_full_name="owner/repo",
            files=[{"path": "a", "content": "c"}],
            commit_message="m",
            branch="main",
        )
        assert "error" in result


class TestPromptMentionsBatchTool:
    def test_system_prompt_nudges_batch(self) -> None:
        from ai_driven.claude.prompts.coding_agent import CODING_AGENT_SYSTEM_PROMPT
        assert "file.write_batch" in CODING_AGENT_SYSTEM_PROMPT

    def test_initial_message_mentions_batch(self) -> None:
        agent = CodingAgent()
        msg = agent._build_initial_message({
            "jira_key": "PROJ-1",
            "repo_full_name": "org/repo",
        })
        assert "file.write_batch" in msg
