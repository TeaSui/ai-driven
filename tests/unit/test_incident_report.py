"""Unit tests for the IncidentReport dataclass."""

from __future__ import annotations

import json

import pytest

from ai_driven.agents.devops.incident import IncidentReport


class TestIncidentReportCreation:
    """Test IncidentReport construction and validation."""

    def test_valid_creation(self) -> None:
        report = IncidentReport(
            severity="P1",
            title="Service outage",
            timeline="10:00 — alert fired, 10:05 — confirmed down",
            root_cause_hypothesis="Bad deployment",
            affected_services=["payment-api", "checkout-ui"],
            remediation_immediate=["Rollback deployment v2.3.1"],
            remediation_longterm=["Add canary deployment"],
        )
        assert report.severity == "P1"
        assert report.title == "Service outage"

    def test_severity_normalized_to_uppercase(self) -> None:
        report = IncidentReport(
            severity="p2", title="Alert", timeline="", root_cause_hypothesis=""
        )
        assert report.severity == "P2"

    def test_invalid_severity_raises(self) -> None:
        with pytest.raises(ValueError, match="severity must be one of"):
            IncidentReport(severity="P5", title="Test", timeline="", root_cause_hypothesis="")

    def test_empty_title_raises(self) -> None:
        with pytest.raises(ValueError, match="title must not be empty"):
            IncidentReport(severity="P1", title="", timeline="", root_cause_hypothesis="")

    def test_defaults_for_lists(self) -> None:
        report = IncidentReport(
            severity="P3", title="Minor alert", timeline="", root_cause_hypothesis=""
        )
        assert report.affected_services == []
        assert report.remediation_immediate == []
        assert report.remediation_longterm == []


class TestToJiraDescription:
    """Test Jira description formatting."""

    def test_includes_severity_and_title(self) -> None:
        report = IncidentReport(
            severity="P1", title="Outage", timeline="Now", root_cause_hypothesis="Deploy"
        )
        desc = report.to_jira_description()
        assert "[P1] Outage" in desc

    def test_includes_all_sections(self) -> None:
        report = IncidentReport(
            severity="P2",
            title="High latency",
            timeline="10:00 spike",
            root_cause_hypothesis="Memory leak",
            affected_services=["api", "worker"],
            remediation_immediate=["Restart pods"],
            remediation_longterm=["Fix memory leak"],
        )
        desc = report.to_jira_description()
        assert "Timeline" in desc
        assert "10:00 spike" in desc
        assert "Root Cause" in desc
        assert "Memory leak" in desc
        assert "* api" in desc
        assert "* worker" in desc
        assert "# Restart pods" in desc
        assert "# Fix memory leak" in desc

    def test_handles_empty_optional_fields(self) -> None:
        report = IncidentReport(
            severity="P4", title="Warning", timeline="", root_cause_hypothesis=""
        )
        desc = report.to_jira_description()
        assert "_Not yet determined_" in desc
        assert "_Under investigation_" in desc
        assert "_None identified_" in desc


class TestToSlackMessage:
    """Test Slack message formatting."""

    def test_includes_severity_label(self) -> None:
        report = IncidentReport(
            severity="P1", title="Down", timeline="", root_cause_hypothesis=""
        )
        msg = report.to_slack_message()
        assert "[P1 - CRITICAL]" in msg
        assert "Down" in msg

    def test_includes_services_and_hypothesis(self) -> None:
        report = IncidentReport(
            severity="P2",
            title="Slow",
            timeline="",
            root_cause_hypothesis="DB connection pool exhausted",
            affected_services=["api"],
            remediation_immediate=["Increase pool size"],
        )
        msg = report.to_slack_message()
        assert "api" in msg
        assert "DB connection pool exhausted" in msg
        assert "Increase pool size" in msg

    def test_p4_shows_low(self) -> None:
        report = IncidentReport(
            severity="P4", title="Info", timeline="", root_cause_hypothesis=""
        )
        msg = report.to_slack_message()
        assert "LOW" in msg


class TestToDict:
    """Test dictionary serialisation."""

    def test_round_trip(self) -> None:
        report = IncidentReport(
            severity="P2",
            title="Test",
            timeline="timeline",
            root_cause_hypothesis="hypothesis",
            affected_services=["svc"],
            remediation_immediate=["step1"],
            remediation_longterm=["step2"],
        )
        d = report.to_dict()
        restored = IncidentReport.from_dict(d)
        assert restored.severity == report.severity
        assert restored.title == report.title
        assert restored.affected_services == report.affected_services


class TestFromDict:
    """Test from_dict deserialisation."""

    def test_creates_from_valid_dict(self) -> None:
        data = {
            "severity": "P1",
            "title": "Incident",
            "timeline": "tl",
            "root_cause_hypothesis": "rch",
            "affected_services": ["a"],
            "remediation_immediate": ["r1"],
            "remediation_longterm": ["r2"],
        }
        report = IncidentReport.from_dict(data)
        assert report.severity == "P1"
        assert report.title == "Incident"

    def test_handles_missing_optional_fields(self) -> None:
        data = {"severity": "P3", "title": "Alert"}
        report = IncidentReport.from_dict(data)
        assert report.affected_services == []

    def test_invalid_severity_raises(self) -> None:
        with pytest.raises(ValueError):
            IncidentReport.from_dict({"severity": "X", "title": "T"})


class TestParseFromText:
    """Test parse_from_text for Claude JSON responses."""

    def test_parses_plain_json(self) -> None:
        text = json.dumps({
            "severity": "P2",
            "title": "Alert",
            "timeline": "",
            "root_cause_hypothesis": "",
        })
        report = IncidentReport.parse_from_text(text)
        assert report.severity == "P2"
        assert report.title == "Alert"

    def test_parses_json_in_code_fence(self) -> None:
        text = '```json\n{"severity": "P1", "title": "Down", "timeline": "", "root_cause_hypothesis": ""}\n```'
        report = IncidentReport.parse_from_text(text)
        assert report.severity == "P1"
        assert report.title == "Down"

    def test_parses_json_in_plain_code_fence(self) -> None:
        text = '```\n{"severity": "P3", "title": "Warn", "timeline": "", "root_cause_hypothesis": ""}\n```'
        report = IncidentReport.parse_from_text(text)
        assert report.severity == "P3"

    def test_invalid_json_raises(self) -> None:
        with pytest.raises(ValueError, match="Failed to parse"):
            IncidentReport.parse_from_text("not json at all")

    def test_non_object_json_raises(self) -> None:
        with pytest.raises(ValueError, match="Expected a JSON object"):
            IncidentReport.parse_from_text("[1, 2, 3]")

    def test_handles_whitespace(self) -> None:
        text = f'  \n{json.dumps({"severity": "P4", "title": "Info", "timeline": "", "root_cause_hypothesis": ""})}\n  '
        report = IncidentReport.parse_from_text(text)
        assert report.severity == "P4"
