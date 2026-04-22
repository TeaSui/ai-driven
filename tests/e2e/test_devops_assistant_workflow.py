"""End-to-end workflow tests for Feature 4: AI DevOps Assistant.

Tests the full flow: DataDog alert → triage → root cause → incident → notify.
All external services are mocked — no real API calls.
"""

from __future__ import annotations

import json
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.devops.agent import DevOpsAgent
from ai_driven.agents.devops.incident import IncidentReport
from ai_driven.claude.provider import (
    MessageResponse,
    TextBlock,
    TokenUsage,
    ToolUseBlock,
)

pytestmark = [pytest.mark.e2e, pytest.mark.asyncio]


# ---------------------------------------------------------------------------
# Helpers
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


def _sequential_responder(responses: list[MessageResponse]):
    idx = 0

    async def respond(**kwargs: Any) -> MessageResponse:
        nonlocal idx
        resp = responses[min(idx, len(responses) - 1)]
        idx += 1
        return resp

    return respond


def _build_mock_datadog() -> AsyncMock:
    dd = AsyncMock()
    dd.search_logs = AsyncMock(return_value=[
        {"timestamp": "2026-04-20T14:23:00Z", "message": "NullPointerException in UserProfileHandler",
         "service": "user-api", "level": "error"},
        {"timestamp": "2026-04-20T14:24:00Z", "message": "500 Internal Server Error",
         "service": "user-api", "level": "error"},
    ])
    dd.query_metrics = AsyncMock(return_value={
        "series": [{"metric": "trace.http.request.errors", "pointlist": [[1713621780, 0.072]]}],
        "query": "avg:trace.http.request.errors{service:user-api}",
    })
    dd.get_service_summary = AsyncMock(return_value={
        "service": "user-api", "error_rate": 0.072, "latency_p99": 450,
    })
    dd.get_active_monitors = AsyncMock(return_value=[
        {"name": "user-api error rate", "status": "Alert", "message": ">5% errors"},
    ])
    return dd


def _build_mock_jira() -> AsyncMock:
    jira = AsyncMock()
    issue = MagicMock()
    issue.key = "OPS-99"
    issue.url = "https://jira.example.com/browse/OPS-99"
    jira.create_issue = AsyncMock(return_value=issue)
    return jira


def _build_mock_scm() -> AsyncMock:
    scm = AsyncMock()
    scm.list_recent_merges = AsyncMock(return_value=[
        {"title": "feat: user profile v2.3.1", "merged_at": "2026-04-20T14:10:00Z",
         "sha": "abc123", "author": "dev1", "pr_url": "https://github.com/org/user-api/pull/55"},
        {"title": "chore: deps update v2.3.0", "merged_at": "2026-04-19T10:00:00Z",
         "sha": "def456", "author": "dev2", "pr_url": "https://github.com/org/user-api/pull/54"},
    ])
    return scm


def _build_mock_slack() -> AsyncMock:
    slack = AsyncMock()
    slack.notify_phase_change = AsyncMock()
    return slack


ALERT_INPUT = {
    "title": "API error rate >5% on user-api",
    "alert_type": "error",
    "service": "user-api",
    "repo_full_name": "org/user-api",
    "tags": ["service:user-api", "env:production"],
}


# ---------------------------------------------------------------------------
# Happy Path: Alert → Triage → P2 Incident
# ---------------------------------------------------------------------------


class TestHappyPath:
    """Full flow: DataDog alert → gather data → correlate → create incident."""

    async def test_full_devops_triage_workflow(self) -> None:
        dd = _build_mock_datadog()
        jira = _build_mock_jira()
        scm = _build_mock_scm()
        slack = _build_mock_slack()
        agent = DevOpsAgent(
            datadog_client=dd, jira_client=jira, scm_client=scm, slack_notifier=slack,
        )

        responses = [
            # 1. Query logs
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-1", name="datadog.query_logs",
                             input={"query": "service:user-api status:error",
                                    "from_ts": "2026-04-20T13:53:00Z",
                                    "to_ts": "2026-04-20T14:23:00Z"})
            ]),
            # 2. Query metrics
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-2", name="datadog.query_metrics",
                             input={"query": "avg:trace.http.request.errors{service:user-api}",
                                    "from_ts": 1713618180, "to_ts": 1713621780})
            ]),
            # 3. Get service health
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-3", name="datadog.get_service_health",
                             input={"service_name": "user-api"})
            ]),
            # 4. Get recent deployments
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-4", name="deploy.get_recent",
                             input={"repo_full_name": "org/user-api"})
            ]),
            # 5. Suggest rollback
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-5", name="deploy.suggest_rollback",
                             input={"repo_full_name": "org/user-api",
                                    "incident_start_ts": "2026-04-20T14:23:00Z"})
            ]),
            # 6. Create Jira incident
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-6", name="jira.create_incident",
                             input={"project_key": "OPS",
                                    "title": "API error rate spike after v2.3.1",
                                    "description": "Error rate increased from 0.1% to 7.2%",
                                    "priority": "High"})
            ]),
            # 7. Notify on-call
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-7", name="slack.notify_oncall",
                             input={"message": "P2 incident: API error rate spike. Jira: OPS-99"})
            ]),
            # 8. Final summary
            _make_response(text="Triage complete. P2 incident created: OPS-99"),
        ]

        agent._claude._provider.create_message = _sequential_responder(responses)

        result = await agent.run(ALERT_INPUT, task_id="task-devops-1")

        assert result.success is True
        assert result.tool_calls == 7
        dd.search_logs.assert_awaited_once()
        dd.query_metrics.assert_awaited_once()
        dd.get_service_summary.assert_awaited_once()
        scm.list_recent_merges.assert_awaited()
        jira.create_issue.assert_awaited_once()
        slack.notify_phase_change.assert_awaited_once()

    async def test_metrics_emitted(self) -> None:
        agent = DevOpsAgent(datadog_client=_build_mock_datadog())
        agent._claude._provider.create_message = _sequential_responder([
            _make_response(text="Triage complete."),
        ])

        with patch("ai_driven.agents.base.emit_agent_metrics") as mock_emit:
            result = await agent.run(ALERT_INPUT, task_id="task-m")
            assert result.success is True
            mock_emit.assert_called_once()
            assert mock_emit.call_args[0][0] == "devops"


# ---------------------------------------------------------------------------
# Tool Execution — No Clients
# ---------------------------------------------------------------------------


class TestToolsWithoutClients:
    """Tools return error dicts when clients are None."""

    async def test_query_logs_no_dd(self) -> None:
        agent = DevOpsAgent()
        result = await agent._registry.execute(
            "datadog.query_logs",
            {"query": "q", "from_ts": "t1", "to_ts": "t2"},
        )
        assert "error" in result

    async def test_query_metrics_no_dd(self) -> None:
        agent = DevOpsAgent()
        result = await agent._registry.execute(
            "datadog.query_metrics",
            {"query": "q", "from_ts": 1, "to_ts": 2},
        )
        assert "error" in result

    async def test_get_deployments_no_scm(self) -> None:
        agent = DevOpsAgent()
        result = await agent._registry.execute(
            "deploy.get_recent", {"repo_full_name": "a/b"},
        )
        assert "error" in result

    async def test_get_health_no_dd(self) -> None:
        agent = DevOpsAgent()
        result = await agent._registry.execute(
            "datadog.get_service_health", {"service_name": "svc"},
        )
        assert "error" in result

    async def test_create_incident_no_jira(self) -> None:
        agent = DevOpsAgent()
        result = await agent._registry.execute(
            "jira.create_incident",
            {"project_key": "OPS", "title": "t", "description": "d"},
        )
        assert "error" in result

    async def test_notify_no_slack(self) -> None:
        agent = DevOpsAgent()
        result = await agent._registry.execute(
            "slack.notify_oncall", {"message": "test"},
        )
        assert "error" in result

    async def test_rollback_no_scm(self) -> None:
        agent = DevOpsAgent()
        result = await agent._registry.execute(
            "deploy.suggest_rollback",
            {"repo_full_name": "a/b", "incident_start_ts": "2026-04-20T14:00:00Z"},
        )
        assert "error" in result


# ---------------------------------------------------------------------------
# Tool Registration
# ---------------------------------------------------------------------------


class TestToolRegistration:
    async def test_all_tools_registered(self) -> None:
        agent = DevOpsAgent()
        names = set(agent._registry.tool_names)
        expected = {
            "datadog.query_logs", "datadog.query_metrics",
            "deploy.get_recent", "datadog.get_service_health",
            "jira.create_incident", "slack.notify_oncall", "deploy.suggest_rollback",
        }
        assert expected == names

    async def test_tool_count(self) -> None:
        agent = DevOpsAgent()
        assert len(agent._registry.tool_names) == 7


# ---------------------------------------------------------------------------
# Suggest Rollback Logic
# ---------------------------------------------------------------------------


class TestSuggestRollback:
    """Test the rollback suggestion tool logic."""

    async def test_identifies_safe_deployment(self) -> None:
        scm = _build_mock_scm()
        agent = DevOpsAgent(scm_client=scm)
        # Incident started at 14:15, so v2.3.1 (14:10) is before incident
        # and v2.3.0 (previous day) is also before — first match is v2.3.1
        result = await agent._registry.execute(
            "deploy.suggest_rollback",
            {"repo_full_name": "org/user-api",
             "incident_start_ts": "2026-04-20T14:23:00Z"},
        )
        assert result["rollback_target"] is not None
        # The first deployment before incident_start_ts is the rollback target
        assert result["rollback_target"]["merged_at"] < "2026-04-20T14:23:00Z"
        assert "suggestion" in result

    async def test_no_deployments(self) -> None:
        scm = AsyncMock()
        scm.list_recent_merges = AsyncMock(return_value=[])
        agent = DevOpsAgent(scm_client=scm)
        result = await agent._registry.execute(
            "deploy.suggest_rollback",
            {"repo_full_name": "org/svc",
             "incident_start_ts": "2026-04-20T14:00:00Z"},
        )
        assert result["rollback_target"] is None
        assert "No recent deployments" in result["suggestion"]


# ---------------------------------------------------------------------------
# IncidentReport
# ---------------------------------------------------------------------------


class TestIncidentReport:
    """Test IncidentReport dataclass and serialisation."""

    def _sample_report(self) -> IncidentReport:
        return IncidentReport(
            severity="P2",
            title="API error rate spike after v2.3.1",
            timeline="14:10 - v2.3.1 deployed. 14:23 - error rate jumped to 7.2%.",
            root_cause_hypothesis="NullPointerException in UserProfileHandler.getProfile()",
            affected_services=["user-api"],
            remediation_immediate=["Rollback to v2.3.0"],
            remediation_longterm=["Add null check for optional profile fields"],
        )

    async def test_severity_validation(self) -> None:
        with pytest.raises(ValueError, match="severity must be one of"):
            IncidentReport(severity="P5", title="test", timeline="", root_cause_hypothesis="")

    async def test_title_validation(self) -> None:
        with pytest.raises(ValueError, match="title must not be empty"):
            IncidentReport(severity="P1", title="", timeline="", root_cause_hypothesis="")

    async def test_valid_severities(self) -> None:
        for sev in ("P1", "P2", "P3", "P4"):
            report = IncidentReport(severity=sev, title="test", timeline="", root_cause_hypothesis="")
            assert report.severity == sev

    async def test_to_dict_round_trip(self) -> None:
        original = self._sample_report()
        d = original.to_dict()
        restored = IncidentReport.from_dict(d)
        assert restored.severity == original.severity
        assert restored.title == original.title
        assert restored.affected_services == original.affected_services
        assert restored.remediation_immediate == original.remediation_immediate

    async def test_to_jira_description(self) -> None:
        report = self._sample_report()
        desc = report.to_jira_description()
        assert "[P2]" in desc
        assert "API error rate spike" in desc
        assert "user-api" in desc
        assert "Rollback to v2.3.0" in desc
        assert "h2." in desc  # Jira wiki markup

    async def test_to_slack_message(self) -> None:
        report = self._sample_report()
        msg = report.to_slack_message()
        assert "[P2 - HIGH]" in msg
        assert "user-api" in msg
        assert "NullPointerException" in msg
        assert "Rollback to v2.3.0" in msg

    async def test_parse_from_text_json(self) -> None:
        data = self._sample_report().to_dict()
        text = json.dumps(data)
        parsed = IncidentReport.parse_from_text(text)
        assert parsed.severity == "P2"
        assert parsed.title == "API error rate spike after v2.3.1"

    async def test_parse_from_text_code_fence(self) -> None:
        data = self._sample_report().to_dict()
        text = f"```json\n{json.dumps(data)}\n```"
        parsed = IncidentReport.parse_from_text(text)
        assert parsed.severity == "P2"

    async def test_parse_from_text_invalid(self) -> None:
        with pytest.raises(ValueError, match="Failed to parse"):
            IncidentReport.parse_from_text("not json at all")

    async def test_p1_critical_slack_message(self) -> None:
        report = IncidentReport(
            severity="P1",
            title="Service completely down",
            timeline="14:00 - all requests failing",
            root_cause_hypothesis="Database connection pool exhausted",
            affected_services=["user-api", "auth-service"],
            remediation_immediate=["Restart pods", "Scale DB connections"],
        )
        msg = report.to_slack_message()
        assert "[P1 - CRITICAL]" in msg
        assert "user-api" in msg
        assert "auth-service" in msg

    async def test_p4_low_report(self) -> None:
        report = IncidentReport(
            severity="P4",
            title="Warning threshold on cache miss rate",
            timeline="Gradual increase over 2 hours",
            root_cause_hypothesis="Cache TTL too short for new data pattern",
        )
        msg = report.to_slack_message()
        assert "[P4 - LOW]" in msg


# ---------------------------------------------------------------------------
# Initial Message Construction
# ---------------------------------------------------------------------------


class TestInitialMessage:
    async def test_includes_alert_details(self) -> None:
        agent = DevOpsAgent()
        msg = agent._build_initial_message(ALERT_INPUT)
        assert "API error rate >5%" in msg
        assert "user-api" in msg
        assert "org/user-api" in msg

    async def test_includes_workflow_steps(self) -> None:
        agent = DevOpsAgent()
        msg = agent._build_initial_message(ALERT_INPUT)
        assert "ASSESS" in msg
        assert "GATHER" in msg
        assert "CORRELATE" in msg
        assert "DIAGNOSE" in msg
        assert "ACT" in msg

    async def test_handles_missing_service(self) -> None:
        agent = DevOpsAgent()
        msg = agent._build_initial_message({"title": "Alert", "alert_type": "warning"})
        assert "Alert" in msg
        assert "warning" in msg


# ---------------------------------------------------------------------------
# Failure Scenarios
# ---------------------------------------------------------------------------


class TestFailureScenarios:
    async def test_claude_api_failure(self) -> None:
        agent = DevOpsAgent(datadog_client=_build_mock_datadog())

        async def explode(**kwargs: Any) -> MessageResponse:
            raise RuntimeError("Claude API unavailable")

        agent._claude._provider.create_message = explode

        with patch("ai_driven.agents.base.emit_agent_metrics"):
            result = await agent.run(ALERT_INPUT, task_id="task-fail")

        assert result.success is False
        assert "unavailable" in result.error.lower()

    async def test_datadog_tool_exception_doesnt_crash(self) -> None:
        dd = _build_mock_datadog()
        dd.search_logs = AsyncMock(side_effect=Exception("DataDog API timeout"))
        agent = DevOpsAgent(datadog_client=dd)

        responses = [
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-1", name="datadog.query_logs",
                             input={"query": "q", "from_ts": "t1", "to_ts": "t2"})
            ]),
            _make_response(text="DataDog unavailable, creating incident with limited info."),
        ]
        agent._claude._provider.create_message = _sequential_responder(responses)

        result = await agent.run(ALERT_INPUT, task_id="task-dd-fail")
        assert result.success is True  # Agent handles tool errors gracefully

    async def test_jira_failure_still_notifies(self) -> None:
        """If Jira fails, agent should still try to notify via Slack."""
        jira = _build_mock_jira()
        jira.create_issue = AsyncMock(side_effect=Exception("Jira down"))
        slack = _build_mock_slack()
        agent = DevOpsAgent(jira_client=jira, slack_notifier=slack)

        responses = [
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-1", name="jira.create_incident",
                             input={"project_key": "OPS", "title": "t", "description": "d"})
            ]),
            # After Jira fails, agent tries Slack
            _make_response(tool_calls=[
                ToolUseBlock(id="tu-2", name="slack.notify_oncall",
                             input={"message": "Jira failed, notifying directly"})
            ]),
            _make_response(text="Notified team. Jira was unavailable."),
        ]
        agent._claude._provider.create_message = _sequential_responder(responses)

        result = await agent.run(ALERT_INPUT, task_id="task-jira-fail")
        assert result.success is True
        slack.notify_phase_change.assert_awaited_once()


# ---------------------------------------------------------------------------
# System Prompt
# ---------------------------------------------------------------------------


class TestSystemPrompt:
    async def test_is_non_trivial(self) -> None:
        agent = DevOpsAgent()
        prompt = agent._get_system_prompt({})
        assert len(prompt) > 100

    async def test_references_incident_workflow(self) -> None:
        agent = DevOpsAgent()
        prompt = agent._get_system_prompt({})
        assert any(
            kw in prompt.lower()
            for kw in ["incident", "triage", "alert", "devops"]
        )
