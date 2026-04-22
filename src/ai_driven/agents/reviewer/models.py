"""Models for structured review results."""

from __future__ import annotations

import fnmatch
import json
import logging
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)

SEVERITY_ORDER = {"critical": 0, "warning": 1, "suggestion": 2}
VALID_VERDICTS = {"approve", "request_changes", "comment"}
VALID_CATEGORIES = {"security", "bug", "performance", "quality", "style"}


@dataclass
class ReviewComment:
    severity: str  # critical, warning, suggestion
    file_path: str
    line: int | None
    body: str
    category: str  # security, bug, performance, quality, style
    confidence: float = 0.8  # 0.0 to 1.0
    title: str = ""  # Short summary (under 60 chars)
    suggested_code: str | None = None  # Optional code fix

    @property
    def severity_emoji(self) -> str:
        return {"critical": "\U0001f534", "warning": "\U0001f7e1", "suggestion": "\U0001f535"}.get(
            self.severity, "\u26aa"
        )

    @property
    def formatted_body(self) -> str:
        return (
            f"{self.severity_emoji} **[{self.severity.upper()}]** ({self.category})"
            f"\n\n{self.body}"
        )


@dataclass
class ReviewResult:
    verdict: str  # approve, request_changes, comment
    summary: str
    comments: list[ReviewComment] = field(default_factory=list)
    positive_highlights: list[str] = field(default_factory=list)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ReviewResult:
        comments = [
            ReviewComment(
                severity=c.get("severity", "suggestion"),
                file_path=c.get("file_path", ""),
                line=c.get("line"),
                body=c.get("body", ""),
                category=c.get("category", "quality"),
                confidence=float(c.get("confidence", 0.8)),
                title=c.get("title", ""),
                suggested_code=c.get("suggested_code"),
            )
            for c in data.get("comments", [])
        ]
        return cls(
            verdict=data.get("verdict", "comment"),
            summary=data.get("summary", ""),
            comments=comments,
            positive_highlights=data.get("positive_highlights", []),
        )

    @classmethod
    def parse_from_text(cls, text: str) -> ReviewResult:
        """Parse review JSON from Claude's response."""
        cleaned = text.strip()
        if "```json" in cleaned:
            start = cleaned.index("```json") + 7
            end = cleaned.index("```", start)
            cleaned = cleaned[start:end].strip()
        elif "```" in cleaned:
            start = cleaned.index("```") + 3
            end = cleaned.index("```", start)
            cleaned = cleaned[start:end].strip()

        try:
            data = json.loads(cleaned)
            return cls.from_dict(data)
        except (json.JSONDecodeError, ValueError) as e:
            logger.warning("Failed to parse review JSON: %s", e)
            return cls(verdict="comment", summary=text[:500])

    def filter_by_severity(self, threshold: str) -> ReviewResult:
        """Return a new ReviewResult with comments at or above the severity threshold."""
        threshold_order = SEVERITY_ORDER.get(threshold, 2)
        filtered = [
            c
            for c in self.comments
            if SEVERITY_ORDER.get(c.severity, 2) <= threshold_order
        ]
        return ReviewResult(
            verdict=self.verdict,
            summary=self.summary,
            comments=filtered,
            positive_highlights=self.positive_highlights,
        )

    def filter_by_paths(self, ignore_paths: list[str]) -> ReviewResult:
        """Return a new ReviewResult without comments on files matching ignore patterns."""

        def _matches(file_path: str, patterns: list[str]) -> bool:
            for pat in patterns:
                if fnmatch.fnmatch(file_path, pat):
                    return True
                # Support directory prefix patterns like "vendor/"
                if pat.endswith("/") and file_path.startswith(pat):
                    return True
            return False

        filtered = [
            c
            for c in self.comments
            if not _matches(c.file_path, ignore_paths)
        ]
        return ReviewResult(
            verdict=self.verdict,
            summary=self.summary,
            comments=filtered,
            positive_highlights=self.positive_highlights,
        )

    def format_summary(self) -> str:
        """Format review summary for posting as a PR comment."""
        verdict_label = self.verdict.replace("_", " ").title()
        parts = [f"## AI Code Review\n\n**Verdict:** {verdict_label}\n\n{self.summary}\n"]

        # Count by severity
        by_severity: dict[str, int] = {}
        for c in self.comments:
            by_severity[c.severity] = by_severity.get(c.severity, 0) + 1

        if by_severity:
            parts.append("### Issues Found")
            for sev in ("critical", "warning", "suggestion"):
                count = by_severity.get(sev, 0)
                if count:
                    emoji = {
                        "critical": "\U0001f534",
                        "warning": "\U0001f7e1",
                        "suggestion": "\U0001f535",
                    }[sev]
                    parts.append(f"- {emoji} {sev.title()}: {count}")

        if self.positive_highlights:
            parts.append("\n### Highlights")
            for h in self.positive_highlights:
                parts.append(f"- \u2705 {h}")

        parts.append("\n---\n*Generated by AI-Driven Code Review Bot*")
        return "\n".join(parts)
