"""Tests for WorkflowAgent (Task 5.10)."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.workflow.agent import WorkflowAgent


class TestWorkflowAgentToolRegistration:
    """Verify workflow agent registers orchestration tools."""

    def test_has_tools_registered(self) -> None:
        agent = WorkflowAgent()
        names = agent._registry.tool_names
        assert len(names) >= 3

    def test_dispatch_coding_agent_tool(self) -> None:
        agent = WorkflowAgent()
        assert "workflow.dispatch_coding" in agent._registry.tool_names

    def test_dispatch_review_agent_tool(self) -> None:
        agent = WorkflowAgent()
        assert "workflow.dispatch_review" in agent._registry.tool_names

    def test_check_task_status_tool(self) -> None:
        agent = WorkflowAgent()
        assert "workflow.check_task_status" in agent._registry.tool_names

    def test_notify_team_tool(self) -> None:
        agent = WorkflowAgent()
        assert "slack.notify_team" in agent._registry.tool_names


class TestWorkflowAgentSystemPrompt:
    def test_system_prompt_contains_workflow_keywords(self) -> None:
        agent = WorkflowAgent()
        prompt = agent._get_system_prompt({})
        prompt_lower = prompt.lower()
        assert "workflow" in prompt_lower or "orchestrat" in prompt_lower
        assert "coding" in prompt_lower or "review" in prompt_lower


class TestWorkflowAgentInitialMessage:
    def test_builds_message_from_input(self) -> None:
        agent = WorkflowAgent()
        msg = agent._build_initial_message({"message": "Process PROJ-123"})
        assert "PROJ-123" in msg

    def test_handles_dict_input(self) -> None:
        agent = WorkflowAgent()
        msg = agent._build_initial_message({"jira_key": "X-1", "action": "code"})
        assert isinstance(msg, str)
