"""Unit tests for review comment normalization in the admin router."""

from __future__ import annotations

import uuid

import pytest

from ai_driven.api.admin.router import _normalize_comments


class TestNormalizeCommentsEmpty:
    """Test normalization with empty or None input."""

    def test_none_returns_empty_list(self) -> None:
        assert _normalize_comments(None) == []

    def test_empty_list_returns_empty_list(self) -> None:
        assert _normalize_comments([]) == []


class TestNormalizeCommentsNewFormat:
    """Test normalization with new structured format comments."""

    def test_new_format_passthrough(self) -> None:
        raw = [
            {
                "file_path": "src/main.py",
                "line": 10,
                "severity": "critical",
                "category": "security",
                "confidence": 0.95,
                "title": "SQL injection risk",
                "body": "Use parameterized queries.",
                "suggested_code": "cursor.execute(%s, (val,))",
            }
        ]
        result = _normalize_comments(raw)
        assert len(result) == 1
        c = result[0]
        assert c["filePath"] == "src/main.py"
        assert c["lineNumber"] == 10
        assert c["severity"] == "critical"
        assert c["category"] == "security"
        assert c["confidence"] == 0.95
        assert c["title"] == "SQL injection risk"
        assert c["body"] == "Use parameterized queries."
        assert c["suggestedCode"] == "cursor.execute(%s, (val,))"
        assert c["resolved"] is False
        # Should have a valid UUID id
        uuid.UUID(c["id"])


class TestNormalizeCommentsOldFormat:
    """Test normalization with old-format comments."""

    def test_old_format_with_file_key(self) -> None:
        raw = [
            {
                "file": "src/app.py",
                "line": 5,
                "severity": "warning",
                "comment": "Missing null check.",
            }
        ]
        result = _normalize_comments(raw)
        assert len(result) == 1
        c = result[0]
        assert c["filePath"] == "src/app.py"
        assert c["lineNumber"] == 5
        assert c["severity"] == "warning"
        assert c["body"] == "Missing null check."
        # Defaults for missing fields
        assert c["category"] == "correctness"
        assert c["confidence"] == 0.8
        assert c["title"] == ""

    def test_old_format_with_path_key(self) -> None:
        raw = [{"path": "src/db.py", "message": "Hardcoded secret."}]
        result = _normalize_comments(raw)
        c = result[0]
        assert c["filePath"] == "src/db.py"
        assert c["title"] == "Hardcoded secret."
        assert c["body"] == "Hardcoded secret."

    def test_old_format_string_confidence_medium(self) -> None:
        raw = [{"file_path": "a.py", "confidence": "medium", "body": "Issue."}]
        result = _normalize_comments(raw)
        assert result[0]["confidence"] == 0.7

    def test_old_format_string_confidence_high(self) -> None:
        raw = [{"file_path": "a.py", "confidence": "high", "body": "Issue."}]
        result = _normalize_comments(raw)
        assert result[0]["confidence"] == 0.9

    def test_old_format_string_confidence_low(self) -> None:
        raw = [{"file_path": "a.py", "confidence": "low", "body": "Issue."}]
        result = _normalize_comments(raw)
        assert result[0]["confidence"] == 0.4

    def test_old_format_string_confidence_unknown(self) -> None:
        raw = [{"file_path": "a.py", "confidence": "unknown_value", "body": "Issue."}]
        result = _normalize_comments(raw)
        assert result[0]["confidence"] == 0.8


class TestNormalizeCommentsEdgeCases:
    """Test edge cases in normalization."""

    def test_confidence_clamped_to_0_1(self) -> None:
        raw = [{"file_path": "a.py", "confidence": 1.5, "body": "Over."}]
        result = _normalize_comments(raw)
        assert result[0]["confidence"] == 1.0

        raw2 = [{"file_path": "a.py", "confidence": -0.5, "body": "Under."}]
        result2 = _normalize_comments(raw2)
        assert result2[0]["confidence"] == 0.0

    def test_missing_file_path_defaults_to_unknown(self) -> None:
        raw = [{"body": "Orphan comment."}]
        result = _normalize_comments(raw)
        assert result[0]["filePath"] == "unknown"

    def test_camel_case_input_keys(self) -> None:
        raw = [
            {
                "filePath": "src/main.py",
                "lineNumber": 42,
                "suggestedCode": "fix()",
                "body": "Issue.",
            }
        ]
        result = _normalize_comments(raw)
        c = result[0]
        assert c["filePath"] == "src/main.py"
        assert c["lineNumber"] == 42
        assert c["suggestedCode"] == "fix()"

    def test_preserves_existing_id(self) -> None:
        existing_id = "abc-123"
        raw = [{"file_path": "a.py", "id": existing_id, "body": "Issue."}]
        result = _normalize_comments(raw)
        assert result[0]["id"] == existing_id

    def test_multiple_comments_all_normalized(self) -> None:
        raw = [
            {"file_path": "a.py", "body": "First."},
            {"file": "b.py", "comment": "Second."},
            {"path": "c.py", "message": "Third."},
        ]
        result = _normalize_comments(raw)
        assert len(result) == 3
        assert result[0]["filePath"] == "a.py"
        assert result[1]["filePath"] == "b.py"
        assert result[2]["filePath"] == "c.py"
