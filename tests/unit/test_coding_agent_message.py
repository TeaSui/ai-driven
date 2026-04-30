"""Tests for CodingAgent._build_initial_message (Task 3.4).

Structure follows prompt-engineering.md: direct-action instruction line,
then XML-tagged context blocks (user_request / repository / jira_ticket),
then XML-tagged <workflow> and <rules> blocks.
"""

from __future__ import annotations

from ai_driven.agents.coding.agent import CodingAgent


class TestCodingAgentInitialMessage:
    """Test the XML-structured _build_initial_message output."""

    def _make_agent(self):
        return CodingAgent(jira_client=None, scm_client=None)

    def test_basic_message_includes_jira_key_and_repo(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"}
        )
        assert "PROJ-123" in msg
        assert "org/repo" in msg

    def test_message_opens_with_direct_action_instruction(self):
        """prompt-engineering.md: lead with a verb, not metadata."""
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"}
        )
        first_line = msg.splitlines()[0]
        assert first_line.startswith("Implement the Jira ticket")

    def test_message_uses_descriptive_xml_tags(self):
        """prompt-engineering.md: structure mixed content with XML tags."""
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo",
             "message": "ship the thing"}
        )
        for tag in ("<user_request>", "<repository>", "<jira_ticket>",
                    "<workflow>", "<rules>"):
            assert tag in msg, f"missing expected tag {tag}"

    def test_message_includes_workflow_steps(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"}
        )
        for phase in ("UNDERSTAND", "EXPLORE", "PLAN", "IMPLEMENT", "DELIVER"):
            assert phase in msg

    def test_message_includes_rules(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "PROJ-123", "repo_full_name": "org/repo"}
        )
        assert "feature/PROJ-123" in msg
        assert "PROJ-123: <summary-from-jira>" in msg
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
        assert "<additional_instructions>" in msg
        assert "Use Python 3.12 features" in msg

    def test_message_includes_status_transition_in_jira_block(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {
                "jira_key": "PROJ-123",
                "repo_full_name": "org/repo",
                "from_status": "To Do",
                "to_status": "In Progress",
            }
        )
        assert "status_transition: To Do -> In Progress" in msg

    def test_message_without_optional_fields_omits_those_blocks(self):
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"jira_key": "X-1", "repo_full_name": "a/b"}
        )
        assert "<additional_instructions>" not in msg
        assert "status_transition:" not in msg
        assert "<user_request>" not in msg

    def test_empty_input_still_yields_workflow_block(self):
        """Even with no context, the workflow scaffold must still render."""
        agent = self._make_agent()
        msg = agent._build_initial_message({})
        assert "<workflow>" in msg
        assert "Complete the task described below" in msg

    def test_non_jira_user_request_skips_jira_get_issue(self):
        """Rules block must explicitly forbid inventing a ticket key."""
        agent = self._make_agent()
        msg = agent._build_initial_message(
            {"repo_full_name": "org/repo", "message": "just fix the typo"}
        )
        assert "Do NOT invent a Jira ticket key" in msg
        assert "<jira_ticket>" not in msg
