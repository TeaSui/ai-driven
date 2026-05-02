"""Tests for IncidentReport (Task 6.3)."""

from __future__ import annotations

import json

import pytest

from ai_driven.agents.devops.incident import IncidentReport


class TestIncidentReport:
    def test_valid_creation(self) -> None:
        report = IncidentReport(
            severity="P1",
            title="API Gateway 5xx spike",
            timeline="Started at 14:00 UTC, error rate jumped from 0.1% to 15%",
            root_cause_hypothesis="Bad deployment at 13:55 UTC introduced null pointer",
            affected_services=["api-gateway", "auth-service"],
            remediation_immediate=["Rollback to v2.3.1"],
            remediation_longterm=["Add null check in request handler"],
        )
        assert report.severity == "P1"
        assert len(report.affected_services) == 2

    def test_severity_case_insensitive(self) -> None:
        report = IncidentReport(
            severity="p2",
            title="High latency",
            timeline="",
            root_cause_hypothesis="",
        )
        assert report.severity == "P2"

    def test_invalid_severity_raises(self) -> None:
        with pytest.raises(ValueError, match="severity must be one of"):
            IncidentReport(
                severity="P0",
                title="Test",
                timeline="",
                root_cause_hypothesis="",
            )

    def test_empty_title_raises(self) -> None:
        with pytest.raises(ValueError, match="title must not be empty"):
            IncidentReport(
                severity="P1",
                title="",
                timeline="",
                root_cause_hypothesis="",
            )

    def test_to_jira_description(self) -> None:
        report = IncidentReport(
            severity="P1",
            title="DB connection pool exhausted",
            timeline="Connections ran out at 10:00",
            root_cause_hypothesis="Connection leak in batch processor",
            affected_services=["batch-worker"],
            remediation_immediate=["Restart batch-worker"],
            remediation_longterm=["Fix connection leak"],
        )
        desc = report.to_jira_description()
        assert "P1" in desc
        assert "DB connection pool" in desc
        assert "batch-worker" in desc

    def test_to_slack_message(self) -> None:
        report = IncidentReport(
            severity="P2",
            title="Cache miss rate elevated",
            timeline="Started 2h ago",
            root_cause_hypothesis="Redis node eviction",
            affected_services=["cache-layer"],
        )
        msg = report.to_slack_message()
        assert "P2" in msg
        assert "Cache miss" in msg

    def test_from_dict(self) -> None:
        data = {
            "severity": "P3",
            "title": "Minor alert",
            "timeline": "Yesterday",
            "root_cause_hypothesis": "Noisy neighbor",
            "affected_services": ["svc-a"],
            "remediation_immediate": ["Monitor"],
            "remediation_longterm": [],
        }
        report = IncidentReport.from_dict(data)
        assert report.severity == "P3"
        assert report.title == "Minor alert"

    def test_parse_from_text_json_block(self) -> None:
        data = {
            "severity": "P1",
            "title": "Outage",
            "timeline": "Now",
            "root_cause_hypothesis": "Unknown",
        }
        text = f"```json\n{json.dumps(data)}\n```"
        report = IncidentReport.parse_from_text(text)
        assert report.severity == "P1"
        assert report.title == "Outage"
