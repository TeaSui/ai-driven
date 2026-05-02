"""Unit tests for the code-based graders. Run on every CI build (no API cost)."""

from __future__ import annotations

import json

from tests.evals.reviewer.code_graders import (
    check_citations,
    check_critical_floor,
    check_finding_count,
    check_must_flag_files,
    check_must_mention_any,
    check_must_not_flag_files,
    check_valid_json,
    check_verdict,
    grade_case,
    parse_reviewer_output,
    pass_rate,
)


def _wrap(payload: dict) -> str:
    """Reviewer agents emit JSON wrapped in a fenced block. Mirror that here."""
    return "```json\n" + json.dumps(payload) + "\n```"


def test_parse_extracts_json_from_fenced_output() -> None:
    text = _wrap({"verdict": "approve", "summary": "ok", "findings": []})
    parsed = parse_reviewer_output(text)
    assert parsed is not None
    assert parsed["verdict"] == "approve"


def test_parse_returns_none_on_garbage() -> None:
    assert parse_reviewer_output("not json") is None
    assert parse_reviewer_output("") is None


def test_valid_json_check() -> None:
    assert check_valid_json(_wrap({"verdict": "approve"})).passed
    assert not check_valid_json("oops").passed


def test_verdict_normalisation() -> None:
    parsed = {"verdict": "approve"}
    assert check_verdict(parsed, "approved").passed
    parsed = {"verdict": "request_changes"}
    assert check_verdict(parsed, "changes_requested").passed
    parsed = {"verdict": "comment"}
    assert check_verdict(parsed, "commented").passed
    parsed = {"verdict": "approve"}
    assert not check_verdict(parsed, "changes_requested").passed


def test_must_flag_files_passes_when_all_listed() -> None:
    parsed = {
        "findings": [
            {"file_path": "a.py", "body": "..."},
            {"path": "b.py", "body": "..."},
        ]
    }
    assert check_must_flag_files(parsed, ["a.py", "b.py"]).passed
    assert not check_must_flag_files(parsed, ["a.py", "c.py"]).passed
    # empty expectation always passes
    assert check_must_flag_files(parsed, []).passed


def test_must_not_flag_files_catches_false_positives() -> None:
    parsed = {"findings": [{"file_path": "clean.py", "body": "nit"}]}
    assert not check_must_not_flag_files(parsed, ["clean.py"]).passed
    assert check_must_not_flag_files(parsed, ["other.py"]).passed


def test_must_mention_searches_findings_and_summary() -> None:
    parsed = {
        "summary": "Critical SQL injection risk",
        "findings": [],
    }
    assert check_must_mention_any(parsed, ["sql injection"]).passed
    assert not check_must_mention_any(parsed, ["xss"]).passed
    parsed2 = {
        "summary": "ok",
        "findings": [{"body": "this is an N+1 query in the loop"}],
    }
    assert check_must_mention_any(parsed2, ["n+1"]).passed


def test_critical_floor() -> None:
    parsed = {
        "findings": [
            {"severity": "low"},
            {"severity": "high"},
            {"severity": "critical"},
        ]
    }
    assert check_critical_floor(parsed, 1).passed
    assert check_critical_floor(parsed, 2).passed
    assert not check_critical_floor(parsed, 3).passed


def test_finding_count_min_max() -> None:
    parsed = {"findings": [{"body": "a"}, {"body": "b"}]}
    assert check_finding_count(parsed, min_total=1).passed
    assert check_finding_count(parsed, max_total=3).passed
    assert not check_finding_count(parsed, min_total=3).passed
    assert not check_finding_count(parsed, max_total=1).passed


def test_grade_case_end_to_end_clean_pr() -> None:
    output = _wrap({
        "verdict": "approve",
        "summary": "Cosmetic log message change. Looks good.",
        "findings": [],
    })
    expectations = {
        "verdict": "approved",
        "must_flag_files": [],
        "must_not_flag_files": ["payments_api/clients/upstream.py"],
        "must_mention_any": [],
        "min_critical_findings": 0,
        "max_total_findings": 1,
    }
    checks = grade_case(output, expectations)
    assert pass_rate(checks) == 1.0


def test_grade_case_end_to_end_sql_injection() -> None:
    output = _wrap({
        "verdict": "request_changes",
        "summary": "SQL injection via string concatenation.",
        "findings": [
            {
                "file_path": "payments_api/handlers/users.py",
                "line": 17,
                "severity": "critical",
                "category": "security",
                "body": "Use a parameterized query — string concatenation here is a classic SQL injection.",
            }
        ],
    })
    expectations = {
        "verdict": "changes_requested",
        "must_flag_files": ["payments_api/handlers/users.py"],
        "must_mention_any": ["sql injection", "parameterized"],
        "must_not_flag_files": [],
        "min_critical_findings": 1,
    }
    checks = grade_case(output, expectations)
    assert pass_rate(checks) == 1.0, [c for c in checks if not c.passed]


def test_citation_check_passes_when_quote_found() -> None:
    case = {
        "diff": {
            "files": [
                {
                    "path": "a.py",
                    "patch": "@@ -1,1 +1,3 @@\n+    query = \"SELECT id FROM users WHERE email = '\" + email + \"'\"\n",
                }
            ]
        },
        "extra_files": {},
    }
    parsed = {
        "findings": [
            {
                "severity": "critical",
                "title": "SQL injection",
                "cited_text": "query = \"SELECT id FROM users WHERE email = '\" + email + \"'\"",
            }
        ]
    }
    assert check_citations(parsed, case).passed


def test_citation_check_fails_on_hallucinated_quote() -> None:
    case = {
        "diff": {"files": [{"path": "a.py", "patch": "+ pass\n"}]},
        "extra_files": {},
    }
    parsed = {
        "findings": [
            {"severity": "critical", "title": "x", "cited_text": "this code is not in the diff"}
        ]
    }
    result = check_citations(parsed, case)
    assert not result.passed
    assert "quote not found" in result.detail


def test_citation_check_skips_low_severity_by_default() -> None:
    case = {"diff": {"files": []}, "extra_files": {}}
    parsed = {"findings": [{"severity": "low", "cited_text": "anything"}]}
    # low-severity finding doesn't require citation verification
    assert check_citations(parsed, case).passed


def test_citation_check_flags_empty_cited_text() -> None:
    case = {"diff": {"files": [{"path": "a.py", "patch": "+ pass\n"}]}, "extra_files": {}}
    parsed = {"findings": [{"severity": "critical", "title": "x", "cited_text": ""}]}
    assert not check_citations(parsed, case).passed


def test_grade_case_catches_wrong_verdict() -> None:
    """Reviewer approves a SQL-injection diff -- harness MUST fail it."""
    output = _wrap({
        "verdict": "approve",
        "summary": "Looks fine.",
        "findings": [],
    })
    expectations = {
        "verdict": "changes_requested",
        "must_flag_files": ["payments_api/handlers/users.py"],
        "must_mention_any": ["sql injection"],
        "min_critical_findings": 1,
    }
    checks = grade_case(output, expectations)
    failed = [c.name for c in checks if not c.passed]
    assert "verdict_match" in failed
    assert "must_flag_files" in failed
    assert "must_mention_any" in failed
    assert "critical_floor" in failed
