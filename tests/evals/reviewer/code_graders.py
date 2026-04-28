"""Code-based graders for the reviewer eval — lesson 14 of the curriculum.

Deterministic, no-API checks. Each grader returns a `GraderCheck` with a
boolean pass/fail and a short reason. They run in CI before any model-based
grader so cheap regressions surface first.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any


@dataclass
class GraderCheck:
    name: str
    passed: bool
    detail: str = ""


# --- output shape ---------------------------------------------------------

_JSON_BLOCK_RE = re.compile(r"\{.*\}", re.DOTALL)


def parse_reviewer_output(output_text: str) -> dict[str, Any] | None:
    """Extract the reviewer's structured JSON. Returns None if it cannot parse.

    Mirrors the parsing the production agent does in
    `ReviewAgent._persist_review_row`, so a regression here predicts a
    regression in the live persistence path.
    """
    if not output_text:
        return None
    match = _JSON_BLOCK_RE.search(output_text)
    if not match:
        return None
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return None


def _findings_of(parsed: dict[str, Any]) -> list[dict[str, Any]]:
    for key in ("findings", "comments", "issues"):
        v = parsed.get(key)
        if isinstance(v, list):
            return [f for f in v if isinstance(f, dict)]
    return []


def _norm_verdict(raw: str) -> str:
    raw = (raw or "").lower()
    if raw in ("approve", "approved"):
        return "approved"
    if raw in ("request_changes", "changes_requested", "changes"):
        return "changes_requested"
    return "commented"


# --- individual checks ----------------------------------------------------

def check_valid_json(output_text: str) -> GraderCheck:
    parsed = parse_reviewer_output(output_text)
    return GraderCheck(
        name="valid_json",
        passed=parsed is not None,
        detail="" if parsed is not None else "could not extract a JSON object",
    )


def check_verdict(parsed: dict[str, Any] | None, expected: str) -> GraderCheck:
    actual = _norm_verdict(parsed.get("verdict", "") if parsed else "")
    return GraderCheck(
        name="verdict_match",
        passed=actual == expected,
        detail=f"expected={expected!r} actual={actual!r}",
    )


def check_must_flag_files(
    parsed: dict[str, Any] | None, expected_files: list[str]
) -> GraderCheck:
    if not expected_files:
        return GraderCheck("must_flag_files", True, "no files required")
    findings = _findings_of(parsed or {})
    flagged_paths = {
        (f.get("file_path") or f.get("path") or f.get("file") or "")
        for f in findings
    }
    missing = [p for p in expected_files if p not in flagged_paths]
    return GraderCheck(
        name="must_flag_files",
        passed=not missing,
        detail=f"missing={missing}" if missing else "all expected files flagged",
    )


def check_must_not_flag_files(
    parsed: dict[str, Any] | None, forbidden_files: list[str]
) -> GraderCheck:
    if not forbidden_files:
        return GraderCheck("must_not_flag_files", True, "no forbidden files")
    findings = _findings_of(parsed or {})
    flagged_paths = {
        (f.get("file_path") or f.get("path") or f.get("file") or "")
        for f in findings
    }
    bad = [p for p in forbidden_files if p in flagged_paths]
    return GraderCheck(
        name="must_not_flag_files",
        passed=not bad,
        detail=f"unexpectedly flagged={bad}" if bad else "no false positives",
    )


def check_must_mention_any(
    parsed: dict[str, Any] | None, keywords: list[str]
) -> GraderCheck:
    if not keywords:
        return GraderCheck("must_mention_any", True, "no keywords required")
    findings = _findings_of(parsed or {})
    haystack = " ".join(
        " ".join(
            str(f.get(k) or "")
            for k in ("title", "body", "message", "summary", "category")
        )
        for f in findings
    ).lower()
    haystack += " " + str((parsed or {}).get("summary", "")).lower()
    hits = [kw for kw in keywords if kw.lower() in haystack]
    return GraderCheck(
        name="must_mention_any",
        passed=bool(hits),
        detail=f"matched={hits}" if hits else f"none of {keywords} found",
    )


def check_critical_floor(
    parsed: dict[str, Any] | None, min_critical: int
) -> GraderCheck:
    findings = _findings_of(parsed or {})
    critical_levels = {"critical", "high", "blocker", "severe"}
    count = sum(
        1 for f in findings
        if str(f.get("severity") or "").lower() in critical_levels
    )
    return GraderCheck(
        name="critical_floor",
        passed=count >= min_critical,
        detail=f"required>={min_critical} actual={count}",
    )


def check_citations(
    parsed: dict[str, Any] | None,
    case: dict[str, Any],
    *,
    require_for_severities: tuple[str, ...] = ("critical", "high"),
) -> GraderCheck:
    """Lesson 41 application: every high-severity finding must quote real code.

    Scans the case's diff patches and any extra_files for the literal
    `cited_text` from each finding (after stripping diff `+` markers and
    leading whitespace). A finding without a verifiable quote is treated as
    hallucinated evidence — the strongest signal a reviewer model can
    fabricate without warning.
    """
    findings = _findings_of(parsed or {})
    if not findings:
        return GraderCheck("citations", True, "no findings to cite")

    # Build the haystack: diff patches (stripped of leading +/- markers) +
    # extra files. Lower-cased and whitespace-collapsed so we don't fail on
    # the model normalising indent.
    def _normalise(s: str) -> str:
        # Strip leading +/-/space markers from each line, collapse whitespace.
        out_lines = []
        for line in s.splitlines():
            if line[:1] in {"+", "-", " "}:
                line = line[1:]
            out_lines.append(" ".join(line.split()))
        return " ".join(out_lines).lower()

    haystack_parts: list[str] = []
    for f in (case.get("diff") or {}).get("files") or []:
        haystack_parts.append(_normalise(f.get("patch") or ""))
    for content in (case.get("extra_files") or {}).values():
        haystack_parts.append(_normalise(content))
    haystack = " ".join(haystack_parts)

    missing: list[str] = []
    for finding in findings:
        sev = str(finding.get("severity") or "").lower()
        if require_for_severities and sev not in require_for_severities:
            continue
        cited = str(finding.get("cited_text") or finding.get("citedText") or "").strip()
        if not cited:
            missing.append(f"{finding.get('title') or '(no title)'}: empty cited_text")
            continue
        normalised = _normalise(cited)
        if normalised and normalised not in haystack:
            missing.append(
                f"{finding.get('title') or '(no title)'}: quote not found in source — "
                f"{normalised[:80]!r}"
            )

    return GraderCheck(
        name="citations",
        passed=not missing,
        detail="; ".join(missing) if missing else "all required findings cite real code",
    )


def check_finding_count(
    parsed: dict[str, Any] | None,
    *,
    min_total: int | None = None,
    max_total: int | None = None,
) -> GraderCheck:
    findings = _findings_of(parsed or {})
    n = len(findings)
    failures: list[str] = []
    if min_total is not None and n < min_total:
        failures.append(f"need>={min_total}")
    if max_total is not None and n > max_total:
        failures.append(f"need<={max_total}")
    return GraderCheck(
        name="finding_count",
        passed=not failures,
        detail=f"n={n}" + (f" failed={failures}" if failures else ""),
    )


# --- orchestrator --------------------------------------------------------

def grade_case(
    output_text: str,
    expectations: dict[str, Any],
    case: dict[str, Any] | None = None,
) -> list[GraderCheck]:
    """Run every applicable check for one case. Order matters for readability.

    `case` is optional only for the unit tests of individual checks; the live
    runner always passes it so `check_citations` can resolve quotes.
    """
    checks: list[GraderCheck] = []

    json_check = check_valid_json(output_text)
    checks.append(json_check)
    parsed = parse_reviewer_output(output_text) if json_check.passed else None

    checks.append(check_verdict(parsed, expectations.get("verdict", "commented")))
    checks.append(check_must_flag_files(parsed, expectations.get("must_flag_files", [])))
    checks.append(check_must_not_flag_files(parsed, expectations.get("must_not_flag_files", [])))
    checks.append(check_must_mention_any(parsed, expectations.get("must_mention_any", [])))
    checks.append(check_critical_floor(parsed, int(expectations.get("min_critical_findings", 0))))
    checks.append(
        check_finding_count(
            parsed,
            min_total=expectations.get("min_total_findings"),
            max_total=expectations.get("max_total_findings"),
        )
    )
    if case is not None:
        checks.append(check_citations(parsed, case))
    return checks


def pass_rate(checks: list[GraderCheck]) -> float:
    if not checks:
        return 0.0
    return round(sum(1 for c in checks if c.passed) / len(checks), 3)
