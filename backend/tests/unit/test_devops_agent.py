"""Unit tests for the DevOpsAgent."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.agents.devops.agent import DevOpsAgent
from ai_driven.claude.prompts.devops_agent import DEVOPS_AGENT_SYSTEM_PROMPT


class TestToolRegistration:
    """Verify all expected tools are registered."""

    def setup_method(self) -> None:
        self.agent = DevOpsAgent(
            jira_client=MagicMock(),
            datadog_client=MagicMock(),
            scm_client=MagicMock(),
            slack_notifier=MagicMock(),
        )

    def test_all_tools_registered(self) -> None:
        names = self.agent._registry.tool_names
        expected = [
            "datadog.query_logs",
            "datadog.query_metrics",
            "deploy.get_recent",
            "datadog.get_service_health",
            "jira.create_incident",
            "slack.notify_oncall",
            "deploy.suggest_rollback",
        ]
        for tool in expected:
            assert tool in names, f"Tool '{tool}' not registered"

    def test_tool_count(self) -> None:
        assert len(self.agent._registry.tool_names) == 7


class TestSystemPrompt:
    """Verify system prompt setup."""

    def test_returns_devops_system_prompt(self) -> None:
        agent = DevOpsAgent()
        prompt = agent._get_system_prompt({})
        assert prompt == DEVOPS_AGENT_SYSTEM_PROMPT

    def test_system_prompt_contains_steps(self) -> None:
        assert "STEP 1" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "STEP 2" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "STEP 3" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "STEP 4" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "STEP 5" in DEVOPS_AGENT_SYSTEM_PROMPT

    def test_system_prompt_references_tools(self) -> None:
        assert "datadog.query_logs" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "datadog.query_metrics" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "datadog.get_service_health" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "deploy.get_recent" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "deploy.suggest_rollback" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "jira.create_incident" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "slack.notify_oncall" in DEVOPS_AGENT_SYSTEM_PROMPT

    def test_system_prompt_contains_incident_format(self) -> None:
        assert "incident_report_format" in DEVOPS_AGENT_SYSTEM_PROMPT
        assert "severity" in DEVOPS_AGENT_SYSTEM_PROMPT


class TestBuildInitialMessage:
    """Verify initial message construction."""

    def test_includes_alert_info(self) -> None:
        agent = DevOpsAgent()
        msg = agent._build_initial_message({
            "title": "High error rate",
            "alert_type": "metric_alert",
        })
        assert "High error rate" in msg
        assert "metric_alert" in msg

    def test_includes_service_and_repo(self) -> None:
        agent = DevOpsAgent()
        msg = agent._build_initial_message({
            "title": "Alert",
            "service": "payment-api",
            "repo_full_name": "org/payment-api",
        })
        assert "payment-api" in msg
        assert "org/payment-api" in msg

    def test_handles_empty_input(self) -> None:
        agent = DevOpsAgent()
        msg = agent._build_initial_message({})
        assert isinstance(msg, str)
        assert "Unknown alert" in msg


class TestQueryDatadogLogsTool:
    """Test query_datadog_logs tool."""

    async def test_returns_logs(self) -> None:
        dd = MagicMock()
        dd.search_logs = AsyncMock(return_value=[
            {"message": "error 1"},
            {"message": "error 2"},
        ])
        agent = DevOpsAgent(datadog_client=dd)
        result = await agent._registry.execute(
            "datadog.query_logs",
            {"query": "service:api status:error", "from_ts": "2026-04-20T00:00:00Z", "to_ts": "2026-04-20T01:00:00Z"},
        )
        dd.search_logs.assert_awaited_once_with(
            "service:api status:error", "2026-04-20T00:00:00Z", "2026-04-20T01:00:00Z", 50,
        )
        assert result["count"] == 2

    async def test_returns_error_without_client(self) -> None:
        agent = DevOpsAgent(datadog_client=None)
        result = await agent._registry.execute(
            "datadog.query_logs",
            {"query": "test", "from_ts": "t1", "to_ts": "t2"},
        )
        assert result == {"error": "DataDog client not configured"}


class TestQueryDatadogMetricsTool:
    """Test query_datadog_metrics tool."""

    async def test_returns_metrics(self) -> None:
        dd = MagicMock()
        dd.query_metrics = AsyncMock(return_value={"series": [], "status": "ok"})
        agent = DevOpsAgent(datadog_client=dd)
        result = await agent._registry.execute(
            "datadog.query_metrics",
            {"query": "avg:cpu{*}", "from_ts": 1000, "to_ts": 2000},
        )
        dd.query_metrics.assert_awaited_once_with("avg:cpu{*}", 1000, 2000)
        assert result["status"] == "ok"

    async def test_returns_error_without_client(self) -> None:
        agent = DevOpsAgent(datadog_client=None)
        result = await agent._registry.execute(
            "datadog.query_metrics",
            {"query": "q", "from_ts": 1, "to_ts": 2},
        )
        assert result == {"error": "DataDog client not configured"}


class TestGetRecentDeploymentsTool:
    """Test get_recent_deployments tool."""

    async def test_returns_deployments(self) -> None:
        scm = MagicMock()
        scm.list_recent_merges = AsyncMock(return_value=[
            {"title": "PR #1", "merged_at": "2026-04-19T10:00:00Z"},
        ])
        agent = DevOpsAgent(scm_client=scm)
        result = await agent._registry.execute(
            "deploy.get_recent",
            {"repo_full_name": "org/repo"},
        )
        scm.list_recent_merges.assert_awaited_once_with("org/repo", 10)
        assert result["count"] == 1

    async def test_returns_error_without_client(self) -> None:
        agent = DevOpsAgent(scm_client=None)
        result = await agent._registry.execute(
            "deploy.get_recent",
            {"repo_full_name": "org/repo"},
        )
        assert result == {"error": "SCM client not configured"}


class TestGetServiceHealthTool:
    """Test get_service_health tool."""

    async def test_returns_health(self) -> None:
        dd = MagicMock()
        dd.get_service_summary = AsyncMock(return_value={"status": "healthy"})
        dd.get_active_monitors = AsyncMock(return_value=[])
        agent = DevOpsAgent(datadog_client=dd)
        result = await agent._registry.execute(
            "datadog.get_service_health",
            {"service_name": "my-api"},
        )
        assert result["service"] == "my-api"
        assert result["summary"]["status"] == "healthy"
        assert result["active_monitors"] == []

    async def test_returns_error_without_client(self) -> None:
        agent = DevOpsAgent(datadog_client=None)
        result = await agent._registry.execute(
            "datadog.get_service_health",
            {"service_name": "my-api"},
        )
        assert result == {"error": "DataDog client not configured"}


class TestCreateJiraIncidentTool:
    """Test create_jira_incident tool."""

    async def test_creates_incident(self) -> None:
        jira = MagicMock()
        issue = MagicMock()
        issue.key = "OPS-42"
        issue.url = "https://jira.example.com/browse/OPS-42"
        jira.create_issue = AsyncMock(return_value=issue)
        agent = DevOpsAgent(jira_client=jira)

        result = await agent._registry.execute(
            "jira.create_incident",
            {
                "project_key": "OPS",
                "title": "Service outage",
                "description": "Full description",
                "priority": "Highest",
                "labels": ["incident"],
            },
        )
        jira.create_issue.assert_awaited_once_with(
            project_key="OPS",
            summary="Service outage",
            description="Full description",
            issue_type="Bug",
            priority="Highest",
            labels=["incident"],
        )
        assert result["key"] == "OPS-42"
        assert result["status"] == "created"

    async def test_uses_default_labels(self) -> None:
        jira = MagicMock()
        issue = MagicMock()
        issue.key = "OPS-1"
        issue.url = "https://jira.example.com/browse/OPS-1"
        jira.create_issue = AsyncMock(return_value=issue)
        agent = DevOpsAgent(jira_client=jira)

        await agent._registry.execute(
            "jira.create_incident",
            {"project_key": "OPS", "title": "Alert", "description": "desc"},
        )
        call_kwargs = jira.create_issue.call_args[1]
        assert call_kwargs["labels"] == ["incident", "auto-triage"]

    async def test_returns_error_without_client(self) -> None:
        agent = DevOpsAgent(jira_client=None)
        result = await agent._registry.execute(
            "jira.create_incident",
            {"project_key": "OPS", "title": "t", "description": "d"},
        )
        assert result == {"error": "Jira client not configured"}


class TestNotifyOncallTool:
    """Test notify_oncall tool."""

    async def test_sends_notification(self) -> None:
        slack = MagicMock()
        slack.notify_phase_change = AsyncMock()
        agent = DevOpsAgent(slack_notifier=slack)
        agent._task_id = "task-123"

        result = await agent._registry.execute(
            "slack.notify_oncall",
            {"message": "P1 incident in payment-api"},
        )
        slack.notify_phase_change.assert_awaited_once()
        assert result["status"] == "sent"

    async def test_returns_error_without_notifier(self) -> None:
        agent = DevOpsAgent(slack_notifier=None)
        result = await agent._registry.execute(
            "slack.notify_oncall",
            {"message": "test"},
        )
        assert result == {"error": "Slack notifier not configured"}


class TestSuggestRollbackTool:
    """Test suggest_rollback tool."""

    async def test_identifies_rollback_target(self) -> None:
        scm = MagicMock()
        scm.list_recent_merges = AsyncMock(return_value=[
            {"title": "feat: new feature", "merged_at": "2026-04-20T10:00:00Z"},
            {"title": "fix: stable release", "merged_at": "2026-04-19T10:00:00Z"},
        ])
        agent = DevOpsAgent(scm_client=scm)

        result = await agent._registry.execute(
            "deploy.suggest_rollback",
            {"repo_full_name": "org/repo", "incident_start_ts": "2026-04-20T09:30:00Z"},
        )
        assert result["rollback_target"]["title"] == "fix: stable release"
        assert len(result["suspect_deployments"]) == 1

    async def test_no_recent_deployments(self) -> None:
        scm = MagicMock()
        scm.list_recent_merges = AsyncMock(return_value=[])
        agent = DevOpsAgent(scm_client=scm)

        result = await agent._registry.execute(
            "deploy.suggest_rollback",
            {"repo_full_name": "org/repo", "incident_start_ts": "2026-04-20T09:00:00Z"},
        )
        assert result["rollback_target"] is None
        assert "No recent deployments" in result["suggestion"]

    async def test_returns_error_without_client(self) -> None:
        agent = DevOpsAgent(scm_client=None)
        result = await agent._registry.execute(
            "deploy.suggest_rollback",
            {"repo_full_name": "org/repo", "incident_start_ts": "t"},
        )
        assert result == {"error": "SCM client not configured"}
