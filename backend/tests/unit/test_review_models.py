"""Unit tests for ReviewResult and ReviewComment models."""

from __future__ import annotations

import json

import pytest

from ai_driven.agents.reviewer.models import ReviewComment, ReviewResult


class TestReviewComment:
    """Test ReviewComment model."""

    def test_formatted_body_includes_severity_emoji_and_category(self) -> None:
        comment = ReviewComment(
            file_path="src/main.py",
            line=10,
            severity="critical",
            category="security",
            body="SQL injection vulnerability detected.",
        )
        formatted = comment.formatted_body
        # Should include a severity indicator (emoji or label) and category
        assert "critical" in formatted.lower() or "\U0001f534" in formatted  # red circle
        assert "security" in formatted.lower()
        assert "SQL injection" in formatted


class TestReviewResultFromDict:
    """Test ReviewResult.from_dict parsing."""

    def test_parses_dict_correctly(self) -> None:
        data = {
            "verdict": "request_changes",
            "summary": "Found 2 issues.",
            "comments": [
                {
                    "file_path": "src/app.py",
                    "line": 5,
                    "severity": "warning",
                    "category": "bugs",
                    "body": "Potential null dereference.",
                },
                {
                    "file_path": "src/db.py",
                    "line": 20,
                    "severity": "critical",
                    "category": "security",
                    "body": "Hardcoded password.",
                },
            ],
        }
        result = ReviewResult.from_dict(data)
        assert result.verdict == "request_changes"
        assert result.summary == "Found 2 issues."
        assert len(result.comments) == 2
        assert result.comments[0].file_path == "src/app.py"
        assert result.comments[1].severity == "critical"


class TestReviewResultParseFromText:
    """Test ReviewResult.parse_from_text with different formats."""

    def test_parse_from_text_json_block(self) -> None:
        payload = {
            "verdict": "approve",
            "summary": "Clean PR.",
            "comments": [],
        }
        text = f"Here is my review:\n```json\n{json.dumps(payload)}\n```"
        result = ReviewResult.parse_from_text(text)
        assert result.verdict == "approve"
        assert result.summary == "Clean PR."
        assert len(result.comments) == 0

    def test_parse_from_text_plain_json(self) -> None:
        payload = {
            "verdict": "comment",
            "summary": "Minor suggestions.",
            "comments": [
                {
                    "file_path": "a.py",
                    "line": 1,
                    "severity": "suggestion",
                    "category": "quality",
                    "body": "Consider renaming.",
                },
            ],
        }
        text = json.dumps(payload)
        result = ReviewResult.parse_from_text(text)
        assert result.verdict == "comment"
        assert len(result.comments) == 1

    def test_parse_from_text_invalid_returns_fallback(self) -> None:
        text = "This is not JSON at all, just a regular review message."
        result = ReviewResult.parse_from_text(text)
        # Should return a fallback ReviewResult without raising
        assert isinstance(result, ReviewResult)
        assert result.verdict is not None


class TestFilterBySeverity:
    """Test filtering comments by severity threshold."""

    def _make_result(self) -> ReviewResult:
        return ReviewResult(
            verdict="request_changes",
            summary="Issues found.",
            comments=[
                ReviewComment(
                    file_path="a.py", line=1, severity="suggestion",
                    category="quality", body="Rename var.",
                ),
                ReviewComment(
                    file_path="b.py", line=2, severity="warning",
                    category="bugs", body="Possible null.",
                ),
                ReviewComment(
                    file_path="c.py", line=3, severity="critical",
                    category="security", body="SQL injection.",
                ),
            ],
        )

    def test_filter_returns_at_or_above_threshold(self) -> None:
        result = self._make_result()
        filtered = result.filter_by_severity("warning")
        severities = [c.severity for c in filtered.comments]
        assert "suggestion" not in severities
        assert "warning" in severities
        assert "critical" in severities

    def test_filter_critical_only(self) -> None:
        result = self._make_result()
        filtered = result.filter_by_severity("critical")
        assert len(filtered.comments) == 1
        assert filtered.comments[0].severity == "critical"


class TestFilterByPaths:
    """Test filtering out comments on ignored files."""

    def test_removes_comments_on_ignored_paths(self) -> None:
        result = ReviewResult(
            verdict="comment",
            summary="Review.",
            comments=[
                ReviewComment(
                    file_path="vendor/lib.py", line=1, severity="warning",
                    category="quality", body="Issue in vendor.",
                ),
                ReviewComment(
                    file_path="src/main.py", line=5, severity="warning",
                    category="bugs", body="Real issue.",
                ),
                ReviewComment(
                    file_path="package-lock.json", line=1, severity="suggestion",
                    category="quality", body="Lock file change.",
                ),
            ],
        )
        filtered = result.filter_by_paths(ignore_paths=["vendor/*", "*.lock*"])
        paths = [c.file_path for c in filtered.comments]
        assert "src/main.py" in paths
        assert "vendor/lib.py" not in paths


class TestReviewCommentNewFields:
    """Test new structured fields on ReviewComment."""

    def test_default_confidence_and_title(self) -> None:
        comment = ReviewComment(
            file_path="src/main.py",
            line=10,
            severity="warning",
            category="bug",
            body="Possible null dereference.",
        )
        assert comment.confidence == 0.8
        assert comment.title == ""
        assert comment.suggested_code is None

    def test_explicit_confidence_title_and_suggested_code(self) -> None:
        comment = ReviewComment(
            file_path="src/main.py",
            line=10,
            severity="critical",
            category="security",
            body="SQL injection via string concat.",
            confidence=0.95,
            title="SQL injection risk",
            suggested_code="cursor.execute('SELECT * FROM t WHERE id=%s', (id,))",
        )
        assert comment.confidence == 0.95
        assert comment.title == "SQL injection risk"
        assert "cursor.execute" in comment.suggested_code

    def test_from_dict_parses_new_fields(self) -> None:
        data = {
            "verdict": "request_changes",
            "summary": "Issues found.",
            "comments": [
                {
                    "file_path": "src/app.py",
                    "line": 5,
                    "severity": "warning",
                    "category": "bug",
                    "body": "Possible null.",
                    "confidence": 0.9,
                    "title": "Null dereference risk",
                    "suggested_code": "if x is not None:",
                },
                {
                    "file_path": "src/db.py",
                    "line": 20,
                    "severity": "critical",
                    "category": "security",
                    "body": "Hardcoded password.",
                },
            ],
        }
        result = ReviewResult.from_dict(data)
        assert result.comments[0].confidence == 0.9
        assert result.comments[0].title == "Null dereference risk"
        assert result.comments[0].suggested_code == "if x is not None:"
        # Second comment should have defaults
        assert result.comments[1].confidence == 0.8
        assert result.comments[1].title == ""
        assert result.comments[1].suggested_code is None


class TestFormatSummary:
    """Test the summary formatting."""

    def test_format_summary_includes_expected_sections(self) -> None:
        result = ReviewResult(
            verdict="request_changes",
            summary="Found security issues.",
            comments=[
                ReviewComment(
                    file_path="a.py", line=1, severity="critical",
                    category="security", body="Hardcoded secret.",
                ),
                ReviewComment(
                    file_path="b.py", line=2, severity="warning",
                    category="bugs", body="Missing null check.",
                ),
            ],
        )
        summary = result.format_summary()
        # Should include the summary text
        assert "security issues" in summary.lower()
        # Should include severity counts
        assert "critical" in summary.lower()
        assert "warning" in summary.lower()
        # Should include the footer
        assert "AI-Driven Code Review Bot" in summary
