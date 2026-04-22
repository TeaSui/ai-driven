"""Tests for CodingAgent._build_initial_message (Task 3.4)."""

from __future__ import annotations

import pytest

from ai_driven.agents.coding.agent import CodingAgent


class TestCodingAgentInitialMessage:
    """Test the comprehensive _build_initial_message method."""

    def _make_agent(self):
        """Create a CodingAgent."""
        return CodingAgent(jira_client=None, scm_client=None)

    def test_basic_message_includes_jira_key_and_repo(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"}
        )
        assert "PROJ-123" in msg
        assert "org/repo" in msg

    def test_message_includes_workflow_steps(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"}
        )
        assert "UNDERSTAND" in msg
        assert "EXPLORE" in msg
        assert "PLAN" in msg
        assert "IMPLEMENT" in msg
        assert "DELIVER" in msg

    def test_message_includes_rules(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"}
        )
        assert "feature/PROJ-123" in msg
        assert "PROJ-123: <summary>" in msg
        assert "Jira comment" in msg

    def test_message_includes_additional_instructions(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {
                "jira_key": "PROJ-123",
                "repo_full_name": "org/repo",
                "instructions": "Use Python 3.12 features",
            }
        )
        assert "Use Python 3.12 features" in msg

    def test_message_includes_transition_context(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {
                "jira_key": "PROJ-123",
                "repo_full_name": "org/repo",
                "from_status": "To Do",
                "to_status": "In Progress",
            }
        )
        assert "To Do" in msg
        assert "In Progress" in msg

    def test_message_without_optional_fields(self):
        agent = self._make_agent()
        msg = agent._build_initial_message({"jira_key": "X-1", "repo_full_name": "a/b"})
        assert "Additional instructions" not in msg
        assert "Transition context" not in msg

    def test_empty_input(self):
        agent = self._make_agent()
        msg = agent._build_initial_message({})
        # Should not crash, just have empty fields
        assert "Follow this workflow" in msg
