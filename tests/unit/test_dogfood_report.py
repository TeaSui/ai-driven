"""T27 unit tests for scripts/phase2_dogfood_report.py.

Golden-file style: we feed a small hand-built JSONL through the
aggregate pipeline and assert each markdown section contains the
expected cells. Avoids full-document equality (which is brittle to
whitespace/formatting).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from scripts.phase2_dogfood_report import (
    _percentile,
    compute_block_reasons,
    compute_mismatches,
    compute_overrides,
    compute_recommendations,
    compute_specialist_roles,
    compute_summary,
    compute_tier_breakdown,
    generate_report,
    load_results,
)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def rows():
    """A representative mix: 2 fast-path completed, 1 full_workflow
    qa_failed, 1 intentional-block that regressed, 1 stuck, 1 multi
    blocked, 1 full_workflow with admin overrides."""
    return [
        {
            "id": "fast-1", "tier_expected": "fast_path",
            "status": "completed", "cost_usd": 0.10,
            "per_role_verdict": {"business_analyst": "pass", "security": "pass"},
            "per_role_cost": {"business_analyst": 0.01, "security": 0.02},
            "blocking_reasons": [], "overrides": [],
            "expected_outcome": None,
        },
        {
            "id": "fast-2", "tier_expected": "fast_path",
            "status": "completed", "cost_usd": 0.12,
            "per_role_verdict": {"security": "pass", "qa": "pass"},
            "per_role_cost": {"security": 0.02, "qa": 0.03},
            "blocking_reasons": [], "overrides": [],
            "expected_outcome": None,
        },
        {
            "id": "full-1", "tier_expected": "full_workflow",
            "status": "qa_failed", "cost_usd": 0.60,
            "per_role_verdict": {"qa": "block", "security": "pass"},
            "per_role_cost": {"qa": 0.06, "security": 0.03},
            "blocking_reasons": ["missing tests", "missing docstring"],
            "overrides": [],
            "expected_outcome": None,
        },
        {
            # Regression: intentional-block that completed
            "id": "block-1", "tier_expected": "full_workflow",
            "status": "completed", "cost_usd": 0.55,
            "per_role_verdict": {"security": "pass"},
            "per_role_cost": {"security": 0.04},
            "blocking_reasons": [], "overrides": [],
            "expected_outcome": "blocked",
        },
        {
            "id": "stuck-1", "tier_expected": "multi_domain",
            "status": "stuck", "cost_usd": 0.30,
            "per_role_verdict": {}, "per_role_cost": {},
            "blocking_reasons": [], "overrides": [],
            "expected_outcome": None,
        },
        {
            "id": "multi-1", "tier_expected": "multi_domain",
            "status": "blocked", "cost_usd": 0.40,
            "per_role_verdict": {"security": "block"},
            "per_role_cost": {"security": 0.05},
            "blocking_reasons": ["missing tests"],
            "overrides": [],
            "expected_outcome": None,
        },
        {
            "id": "full-2-override", "tier_expected": "full_workflow",
            "status": "completed", "cost_usd": 0.70,
            "per_role_verdict": {"qa": "block", "security": "pass"},
            "per_role_cost": {"qa": 0.05, "security": 0.03},
            "blocking_reasons": [],
            "overrides": [
                {"action": "force_pass", "fromStatus": "qa_failed",
                 "toStatus": "completed", "reason": "human review"},
            ],
            "expected_outcome": None,
        },
    ]


# ---------------------------------------------------------------------------
# Primitives
# ---------------------------------------------------------------------------


def test_percentile_empty_returns_zero():
    assert _percentile([], 95) == 0.0


def test_percentile_single_value():
    assert _percentile([5.0], 95) == 5.0


def test_percentile_monotonic():
    v = [1.0, 2.0, 3.0, 4.0, 5.0]
    p50 = _percentile(v, 50)
    p95 = _percentile(v, 95)
    assert p50 == pytest.approx(3.0)
    assert p95 > p50


# ---------------------------------------------------------------------------
# Aggregates
# ---------------------------------------------------------------------------


def test_compute_summary_counts_and_percentages(rows):
    s = compute_summary(rows)
    assert s["total"] == 7
    assert s["completed"] == 4
    assert s["blocked"] == 1
    assert s["qa_failed"] == 1
    assert s["stuck"] == 1
    assert s["total_cost"] == pytest.approx(0.10 + 0.12 + 0.60 + 0.55 + 0.30 + 0.40 + 0.70)
    assert s["completed_pct"] == pytest.approx(4 / 7 * 100)


def test_compute_tier_breakdown(rows):
    tb = compute_tier_breakdown(rows)
    by_tier = {t["tier"]: t for t in tb}
    assert by_tier["fast_path"]["total"] == 2
    assert by_tier["fast_path"]["completed"] == 2
    assert by_tier["fast_path"]["success_rate"] == pytest.approx(100.0)
    assert by_tier["full_workflow"]["total"] == 3
    # full_workflow: 2 completed (including the regression), 1 qa_failed
    assert by_tier["full_workflow"]["completed"] == 2
    assert by_tier["full_workflow"]["blocked"] == 1  # qa_failed counted here
    # multi_domain: 1 blocked, 1 stuck
    assert by_tier["multi_domain"]["blocked"] == 1
    assert by_tier["multi_domain"]["failed"] == 1  # stuck


def test_compute_specialist_roles(rows):
    specs = compute_specialist_roles(rows)
    by_role = {s["role"]: s for s in specs}
    # security: 6 pass verdicts, 1 block = 7 total? Let's count:
    # fast-1 pass, fast-2 pass, full-1 pass, block-1 pass,
    # multi-1 block, full-2-override pass = 6 total
    sec = by_role["security"]
    assert sec["total"] == 6
    assert sec["pass"] == 5
    assert sec["block"] == 1
    # qa: fast-2 pass, full-1 block, full-2-override block = 3
    qa = by_role["qa"]
    assert qa["total"] == 3
    assert qa["block"] == 2


def test_compute_block_reasons_top_10(rows):
    reasons = compute_block_reasons(rows)
    # "missing tests" appears in full-1 (qa_failed) + multi-1 (blocked)
    reasons_dict = dict(reasons)
    assert reasons_dict.get("missing tests") == 2
    assert reasons_dict.get("missing docstring") == 1


def test_compute_overrides(rows):
    ov = compute_overrides(rows)
    assert ov["total"] == 1
    assert ov["by_action"]["force_pass"] == 1
    assert ov["by_from_status"]["qa_failed"] == 1


def test_compute_mismatches_flags_intentional_block_regression(rows):
    mm = compute_mismatches(rows, corpus_expected={})
    assert len(mm) == 1
    assert mm[0]["id"] == "block-1"
    assert mm[0]["expected"] == "blocked"
    assert mm[0]["actual"] == "completed"


def test_mismatches_corpus_overrides_row(rows):
    """If the corpus carries a different expected_outcome than the
    row, corpus wins."""
    mm = compute_mismatches(
        rows, corpus_expected={"fast-1": "completed"}
    )
    # fast-1 expected completed; actual completed — no mismatch.
    # block-1 still expected blocked (no corpus override) — still mismatch.
    ids = {m["id"] for m in mm}
    assert "fast-1" not in ids
    assert "block-1" in ids


# ---------------------------------------------------------------------------
# Recommendations
# ---------------------------------------------------------------------------


def test_recommendations_flag_hard_regression(rows):
    summary = compute_summary(rows)
    specialists = compute_specialist_roles(rows)
    overrides = compute_overrides(rows)
    mismatches = compute_mismatches(rows, {})
    tier_breakdown = compute_tier_breakdown(rows)
    recs = compute_recommendations(
        summary, specialists, overrides, mismatches, tier_breakdown,
    )
    # A block-1 regression must fire the REGRESSION rec
    assert any("REGRESSION" in r and "block-1" in r for r in recs)


def test_recommendations_clean_slate_passes():
    """Empty input → the 'All programmatic checks passed' default."""
    summary = compute_summary([])
    specialists = compute_specialist_roles([])
    overrides = compute_overrides([])
    tier_breakdown = compute_tier_breakdown([])
    recs = compute_recommendations(
        summary, specialists, overrides, [], tier_breakdown,
    )
    assert recs == ["All programmatic checks passed. No tuning recommended."]


def test_recommendations_flag_high_error_rate():
    """Synthetic: one role with 3 error verdicts out of 10 → 30% > 15%."""
    rows_hi_err = []
    for i in range(7):
        rows_hi_err.append({
            "id": f"t-{i}", "tier_expected": "fast_path",
            "status": "completed", "cost_usd": 0.01,
            "per_role_verdict": {"qa": "pass"},
            "per_role_cost": {"qa": 0.001},
            "blocking_reasons": [], "overrides": [],
            "expected_outcome": None,
        })
    for i in range(3):
        rows_hi_err.append({
            "id": f"err-{i}", "tier_expected": "fast_path",
            "status": "failed", "cost_usd": 0.01,
            "per_role_verdict": {"qa": "error"},
            "per_role_cost": {"qa": 0.001},
            "blocking_reasons": [], "overrides": [],
            "expected_outcome": None,
        })
    summary = compute_summary(rows_hi_err)
    specialists = compute_specialist_roles(rows_hi_err)
    overrides = compute_overrides(rows_hi_err)
    tier_breakdown = compute_tier_breakdown(rows_hi_err)
    recs = compute_recommendations(
        summary, specialists, overrides, [], tier_breakdown,
    )
    assert any("error rate" in r.lower() and "qa" in r for r in recs)


def test_recommendations_flag_force_pass_heavy():
    """force_pass > 20% of qa_failed fires the QA-prompt rec."""
    # 1 qa_failed ticket + 1 force_pass override (ratio 100%).
    rows = [
        {
            "id": "qaf-1", "tier_expected": "full_workflow",
            "status": "qa_failed", "cost_usd": 0.1,
            "per_role_verdict": {}, "per_role_cost": {},
            "blocking_reasons": [], "overrides": [],
        },
        {
            "id": "t-1", "tier_expected": "full_workflow",
            "status": "completed", "cost_usd": 0.1,
            "per_role_verdict": {}, "per_role_cost": {},
            "blocking_reasons": [],
            "overrides": [
                {"action": "force_pass", "fromStatus": "qa_failed"},
            ],
        },
    ]
    summary = compute_summary(rows)
    specialists = compute_specialist_roles(rows)
    overrides = compute_overrides(rows)
    tier_breakdown = compute_tier_breakdown(rows)
    recs = compute_recommendations(
        summary, specialists, overrides, [], tier_breakdown,
    )
    assert any("force_pass" in r and "QA prompt" in r for r in recs)


# ---------------------------------------------------------------------------
# Full markdown — section smoke
# ---------------------------------------------------------------------------


def test_generate_report_has_all_required_sections(rows):
    md = generate_report(rows, {})
    # Brief §3 calls for these sections by name.
    required = [
        "# Phase 2 Dogfood Results",
        "## Summary",
        "## Tier Breakdown",
        "## Specialist Role Data",
        "## Block Reasons",
        "## Admin Overrides",
        "## Expected-outcome Mismatches",
        "## Recommendations",
    ]
    for header in required:
        assert header in md, f"missing section: {header}"


def test_generate_report_surfaces_regression_ticket_id(rows):
    md = generate_report(rows, {})
    # The mismatches table must name the regressing ticket.
    assert "block-1" in md
    # And the recommendations must flag it.
    assert "REGRESSION" in md


def test_generate_report_escapes_pipes_in_reasons():
    rows = [
        {
            "id": "t", "tier_expected": "fast_path",
            "status": "blocked", "cost_usd": 0.01,
            "per_role_verdict": {}, "per_role_cost": {},
            "blocking_reasons": ["contains | pipe in reason"],
            "overrides": [], "expected_outcome": None,
        },
    ]
    md = generate_report(rows, {})
    # Escaped pipe should appear as '\|' in the table row.
    assert "contains \\| pipe" in md


# ---------------------------------------------------------------------------
# load_results — JSONL round-trip
# ---------------------------------------------------------------------------


def test_load_results_skips_blank_and_malformed(tmp_path: Path):
    p = tmp_path / "r.jsonl"
    p.write_text(
        '{"id":"a","status":"completed"}\n'
        "\n"
        "not json at all\n"
        '{"id":"b","status":"failed"}\n'
    )
    rows = load_results(p)
    assert [r["id"] for r in rows] == ["a", "b"]
