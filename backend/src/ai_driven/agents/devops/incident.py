"""Structured incident report generation for the DevOps agent."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field

from ai_driven.domain.enums import IncidentSeverity

logger = logging.getLogger(__name__)

_VALID_SEVERITIES = {s.value for s in IncidentSeverity}


@dataclass
class IncidentReport:
    """Structured incident report produced by the DevOps agent.

    Provides serialisation helpers for Jira ticket descriptions and
    Slack notification messages.
    """

    severity: str  # P1, P2, P3, P4
    title: str
    timeline: str
    root_cause_hypothesis: str
    affected_services: list[str] = field(default_factory=list)
    remediation_immediate: list[str] = field(default_factory=list)
    remediation_longterm: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.severity = self.severity.upper()
        if self.severity not in _VALID_SEVERITIES:
            raise ValueError(
                f"severity must be one of {_VALID_SEVERITIES}, got '{self.severity}'"
            )
        if not self.title:
            raise ValueError("title must not be empty")

    # ------------------------------------------------------------------
    # Serialisation
    # ------------------------------------------------------------------

    def to_jira_description(self) -> str:
        """Format the incident as a Jira-compatible description (Markdown)."""
        sections = [
            f"h2. [{self.severity}] {self.title}",
            "",
            "h3. Timeline",
            self.timeline or "_Not yet determined_",
            "",
            "h3. Root Cause Hypothesis",
            self.root_cause_hypothesis or "_Under investigation_",
            "",
            "h3. Affected Services",
        ]

        if self.affected_services:
            for svc in self.affected_services:
                sections.append(f"* {svc}")
        else:
            sections.append("_None identified_")

        sections.append("")
        sections.append("h3. Immediate Remediation")
        if self.remediation_immediate:
            for step in self.remediation_immediate:
                sections.append(f"# {step}")
        else:
            sections.append("_No immediate actions defined_")

        sections.append("")
        sections.append("h3. Long-term Remediation")
        if self.remediation_longterm:
            for step in self.remediation_longterm:
                sections.append(f"# {step}")
        else:
            sections.append("_No long-term actions defined_")

        return "\n".join(sections)

    def to_slack_message(self) -> str:
        """Format the incident as a Slack notification message."""
        severity_emoji = {
            "P1": "CRITICAL",
            "P2": "HIGH",
            "P3": "MEDIUM",
            "P4": "LOW",
        }
        level = severity_emoji.get(self.severity, self.severity)

        parts = [
            f"*[{self.severity} - {level}] {self.title}*",
            "",
        ]

        if self.affected_services:
            parts.append(
                f"*Affected Services:* {', '.join(self.affected_services)}"
            )

        if self.root_cause_hypothesis:
            parts.append(f"*Root Cause Hypothesis:* {self.root_cause_hypothesis}")

        if self.remediation_immediate:
            parts.append("*Immediate Actions:*")
            for i, step in enumerate(self.remediation_immediate, 1):
                parts.append(f"  {i}. {step}")

        if self.timeline:
            parts.append(f"*Timeline:* {self.timeline}")

        return "\n".join(parts)

    def to_dict(self) -> dict:
        """Serialise to a plain dictionary."""
        return {
            "severity": self.severity,
            "title": self.title,
            "timeline": self.timeline,
            "root_cause_hypothesis": self.root_cause_hypothesis,
            "affected_services": self.affected_services,
            "remediation_immediate": self.remediation_immediate,
            "remediation_longterm": self.remediation_longterm,
        }

    # ------------------------------------------------------------------
    # Deserialisation
    # ------------------------------------------------------------------

    @classmethod
    def from_dict(cls, data: dict) -> IncidentReport:
        """Create an IncidentReport from a dictionary.

        Raises ``KeyError`` if required fields are missing and
        ``ValueError`` if field validation fails.
        """
        return cls(
            severity=str(data.get("severity", "")),
            title=str(data.get("title", "")),
            timeline=str(data.get("timeline", "")),
            root_cause_hypothesis=str(data.get("root_cause_hypothesis", "")),
            affected_services=list(data.get("affected_services", [])),
            remediation_immediate=list(data.get("remediation_immediate", [])),
            remediation_longterm=list(data.get("remediation_longterm", [])),
        )

    @classmethod
    def parse_from_text(cls, text: str) -> IncidentReport:
        """Parse an IncidentReport from a Claude JSON response.

        The *text* may contain a JSON object directly or a JSON block
        embedded in Markdown fences (````json ... ```).

        Raises ``ValueError`` if the text cannot be parsed.
        """
        cleaned = text.strip()

        # Strip Markdown code fences if present
        if cleaned.startswith("```"):
            # Remove opening fence (with optional language tag)
            first_newline = cleaned.index("\n")
            cleaned = cleaned[first_newline + 1 :]
            # Remove closing fence
            if cleaned.endswith("```"):
                cleaned = cleaned[: -3].rstrip()

        try:
            data = json.loads(cleaned)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Failed to parse incident report JSON: {exc}") from exc

        if not isinstance(data, dict):
            raise ValueError(
                f"Expected a JSON object, got {type(data).__name__}"
            )

        return cls.from_dict(data)
