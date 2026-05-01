"""T27 — Phase 2 dogfood report generator.

Reads the JSONL written by ``phase2_dogfood_harness`` and produces a
markdown report at ``--output``. Sections are fixed per the T27 brief:

  1. Summary table       — counts + cost percentiles
  2. Tier breakdown      — fast_path / full_workflow / multi_domain
  3. Specialist roles    — per-role counts + mean cost + error rate
  4. Block reasons       — top 10 blocking-reason strings
  5. Admin overrides     — counts by action + from-status
  6. Expected-outcome mismatches — intentional-block tickets that
                                   reached 'completed' (regressions)
  7. Recommendations     — 4 programmatic checks (from the brief)

The report is deterministic given the same input so it can be diffed
across runs to catch drift.

CLI
---
    python -m scripts.phase2_dogfood_report \
        --input docs/phase2-dogfood-results-raw.jsonl \
        --output docs/phase2-dogfood-results.md \
        [--corpus scripts/phase2_dogfood_corpus.yaml]

The ``--corpus`` path is optional — the JSONL carries ``expected_outcome``
inline, but passing the corpus lets the report double-check alignment
(helpful when an older JSONL is re-run against a newer corpus).
"""

from __future__ import annotations

import argparse
import json
import logging
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

logger = logging.getLogger("phase2_dogfood_report")


# Roles we report per T-A1 (business_analyst, stack_analyst, security, qa).
# Order matters — drives column ordering in the specialist table.
REPORT_ROLES: tuple[str, ...] = (
    "business_analyst", "stack_analyst", "security", "qa",
)

TERMINAL_STATUSES: frozenset[str] = frozenset(
    {"completed", "failed", "blocked", "qa_failed"}
)


# ---------------------------------------------------------------------------
# Input
# ---------------------------------------------------------------------------


def load_results(path: Path) -> list[dict[str, Any]]:
    """Read the JSONL emitted by the harness. One object per line."""
    rows: list[dict[str, Any]] = []
    with path.open() as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as ex:
                logger.warning("Skipping malformed line %d: %s", i + 1, ex)
    return rows


def load_corpus_expected(path: Path | None) -> dict[str, str | None]:
    """Return {ticket_id: expected_outcome} from a YAML corpus file."""
    if path is None:
        return {}
    import yaml
    with path.open() as f:
        entries = yaml.safe_load(f) or []
    out: dict[str, str | None] = {}
    for e in entries:
        if isinstance(e, dict) and "id" in e:
            out[str(e["id"])] = e.get("expected_outcome")
    return out


# ---------------------------------------------------------------------------
# Aggregation
# ---------------------------------------------------------------------------


def _percentile(values: list[float], pct: float) -> float:
    """Linear-interpolation percentile. Returns 0.0 for empty input."""
    if not values:
        return 0.0
    s = sorted(values)
    # clamp
    if pct <= 0:
        return s[0]
    if pct >= 100:
        return s[-1]
    k = (len(s) - 1) * (pct / 100.0)
    f = int(k)
    c = min(f + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def compute_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Overall counts + cost percentiles."""
    total = len(rows)
    statuses = Counter(r.get("status") for r in rows)
    costs = [float(r.get("cost_usd") or 0) for r in rows if r.get("cost_usd") is not None]

    def pct(name: str) -> float:
        return (statuses.get(name, 0) / total * 100) if total > 0 else 0.0

    return {
        "total": total,
        "completed": statuses.get("completed", 0),
        "completed_pct": pct("completed"),
        "blocked": statuses.get("blocked", 0),
        "blocked_pct": pct("blocked"),
        "qa_failed": statuses.get("qa_failed", 0),
        "qa_failed_pct": pct("qa_failed"),
        "failed": statuses.get("failed", 0),
        "failed_pct": pct("failed"),
        "stuck": statuses.get("stuck", 0),
        "stuck_pct": pct("stuck"),
        "skipped_budget": statuses.get("skipped_budget_cap", 0),
        "error": sum(1 for r in rows if r.get("error")),
        "total_cost": sum(costs),
        "median_cost": statistics.median(costs) if costs else 0.0,
        "p95_cost": _percentile(costs, 95),
    }


def compute_tier_breakdown(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Per-tier counts + success rate."""
    by_tier: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for r in rows:
        by_tier[r.get("tier_expected") or "unknown"].append(r)

    out: list[dict[str, Any]] = []
    for tier in ("fast_path", "full_workflow", "multi_domain", "unknown"):
        bucket = by_tier.get(tier, [])
        if not bucket:
            continue
        completed = sum(1 for r in bucket if r.get("status") == "completed")
        blocked = sum(
            1 for r in bucket if r.get("status") in ("blocked", "qa_failed")
        )
        failed = sum(1 for r in bucket if r.get("status") in ("failed", "stuck"))
        total = len(bucket)
        success_rate = (completed / total * 100) if total > 0 else 0.0
        out.append({
            "tier": tier,
            "total": total,
            "completed": completed,
            "blocked": blocked,
            "failed": failed,
            "success_rate": success_rate,
        })
    return out


def compute_specialist_roles(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Per-role aggregates: verdict counts, mean cost, error rate."""
    # role -> {verdict -> count}, role -> [costs]
    verdict_counts: dict[str, Counter] = defaultdict(Counter)
    role_costs: dict[str, list[float]] = defaultdict(list)

    for r in rows:
        per_v = r.get("per_role_verdict") or {}
        per_c = r.get("per_role_cost") or {}
        for role, verdict in per_v.items():
            if verdict:
                verdict_counts[role][verdict] += 1
        for role, cost in per_c.items():
            try:
                role_costs[role].append(float(cost))
            except (TypeError, ValueError):
                pass

    out: list[dict[str, Any]] = []
    for role in REPORT_ROLES:
        vc = verdict_counts.get(role, Counter())
        costs = role_costs.get(role, [])
        total = sum(vc.values())
        errors = vc.get("error", 0)
        error_rate = (errors / total * 100) if total > 0 else 0.0
        out.append({
            "role": role,
            "total": total,
            "pass": vc.get("pass", 0),
            "pass_with_notes": vc.get("pass_with_notes", 0),
            "block": vc.get("block", 0),
            "error": errors,
            "mean_cost": sum(costs) / len(costs) if costs else 0.0,
            "error_rate": error_rate,
        })
    return out


def compute_block_reasons(rows: list[dict[str, Any]]) -> list[tuple[str, int]]:
    """Top-10 blocking_reasons strings across blocked + qa_failed tasks."""
    reasons: Counter = Counter()
    for r in rows:
        if r.get("status") not in ("blocked", "qa_failed"):
            continue
        for reason in r.get("blocking_reasons") or []:
            reasons[str(reason)] += 1
    return reasons.most_common(10)


def compute_overrides(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Counts by action + from-status across all tickets."""
    total = 0
    by_action: Counter = Counter()
    by_from_status: Counter = Counter()
    by_pair: Counter = Counter()
    for r in rows:
        for ov in r.get("overrides") or []:
            total += 1
            action = ov.get("action") or "unknown"
            from_s = ov.get("fromStatus") or ov.get("from_status") or "unknown"
            by_action[action] += 1
            by_from_status[from_s] += 1
            by_pair[(from_s, action)] += 1
    return {
        "total": total,
        "by_action": dict(by_action),
        "by_from_status": dict(by_from_status),
        "by_pair": dict(by_pair),
    }


def compute_mismatches(
    rows: list[dict[str, Any]],
    corpus_expected: dict[str, str | None],
) -> list[dict[str, Any]]:
    """Intentional-block tickets whose actual outcome diverged.

    Priority: ``corpus_expected`` (if present) overrides the
    per-row ``expected_outcome`` field — the corpus is the source of
    truth when both are given.
    """
    out: list[dict[str, Any]] = []
    for r in rows:
        expected = corpus_expected.get(r["id"]) if corpus_expected else None
        if expected is None:
            expected = r.get("expected_outcome")
        if expected is None:
            continue
        actual = r.get("status")
        # The harness already computed outcome_match but we re-derive
        # here so the report is reproducible from the JSONL alone.
        if expected == "blocked":
            mismatch = actual not in ("blocked", "qa_failed")
        elif expected == "completed":
            mismatch = actual != "completed"
        else:
            mismatch = False
        if mismatch:
            out.append({
                "id": r["id"],
                "tier": r.get("tier_expected"),
                "expected": expected,
                "actual": actual,
                "task_id": r.get("task_id"),
            })
    return out


def compute_recommendations(
    summary: dict[str, Any],
    specialists: list[dict[str, Any]],
    overrides: dict[str, Any],
    mismatches: list[dict[str, Any]],
    tier_breakdown: list[dict[str, Any]],
) -> list[str]:
    """Programmatic checks from brief §3 / Recommendations."""
    recs: list[str] = []

    # 1. Any specialist role with >15% error rate
    high_err = [s for s in specialists if s["error_rate"] > 15.0]
    if high_err:
        names = ", ".join(f"{s['role']} ({s['error_rate']:.1f}%)" for s in high_err)
        recs.append(
            f"Specialist error rate >15% on: {names}. Recommend KEEP lenient gating "
            f"until error rate is brought down (T-A1 threshold)."
        )

    # 2. force_pass overrides > 20% of qa_failed
    qa_failed_ct = summary["qa_failed"]
    force_pass_ct = overrides["by_action"].get("force_pass", 0)
    if qa_failed_ct > 0:
        fp_rate = force_pass_ct / qa_failed_ct * 100
        if fp_rate > 20.0:
            recs.append(
                f"force_pass overrides {force_pass_ct}/{qa_failed_ct} "
                f"({fp_rate:.1f}%) of qa_failed — QA prompt is false-positive-"
                f"heavy. Recommend retune of QA verdict prompt."
            )

    # 3. fast_path tickets that ran the orchestrator (specialists present)
    # Heuristic: fast_path ticket with any specialist artifact indicates
    # a classifier miss — orchestrator ran when it shouldn't have.
    # (This requires harness to set classifier_tier; we tolerate absence.)
    # Implemented as: tier_expected == fast_path AND completed status AND
    # any per_role_cost entry > 0.
    # We don't rerun that here — instead we flag based on tier_breakdown:
    # any fast_path failures (other than stuck) are worth investigating.
    fast_tier = next((t for t in tier_breakdown if t["tier"] == "fast_path"), None)
    if fast_tier and fast_tier["total"] > 0:
        if fast_tier["blocked"] + fast_tier["failed"] > 0:
            recs.append(
                f"fast_path tier had {fast_tier['blocked']} blocked + "
                f"{fast_tier['failed']} failed/stuck "
                f"out of {fast_tier['total']}. Investigate classifier — "
                f"fast-path tickets should almost never block."
            )

    # 4. Any intentional-block that reached 'completed' → regression
    hard_regressions = [m for m in mismatches if m["expected"] == "blocked"
                        and m["actual"] == "completed"]
    if hard_regressions:
        ids = ", ".join(m["id"] for m in hard_regressions)
        recs.append(
            f"REGRESSION: intentional-block tickets completed: {ids}. "
            f"Audit Security prompt immediately — these should have been "
            f"blocked."
        )

    if not recs:
        recs.append("All programmatic checks passed. No tuning recommended.")
    return recs


# ---------------------------------------------------------------------------
# Rendering
# ---------------------------------------------------------------------------


def render_markdown(
    *,
    summary: dict[str, Any],
    tier_breakdown: list[dict[str, Any]],
    specialists: list[dict[str, Any]],
    block_reasons: list[tuple[str, int]],
    overrides: dict[str, Any],
    mismatches: list[dict[str, Any]],
    recommendations: list[str],
) -> str:
    out: list[str] = []
    out.append("# Phase 2 Dogfood Results")
    out.append("")
    out.append("Generated by `scripts/phase2_dogfood_report.py` from the harness JSONL.")
    out.append("")

    # --- Summary ---
    out.append("## Summary")
    out.append("")
    out.append("| Metric | Value |")
    out.append("|---|---|")
    out.append(f"| Total submitted | {summary['total']} |")
    out.append(
        f"| Completed | {summary['completed']} "
        f"({summary['completed_pct']:.1f}%) |"
    )
    out.append(
        f"| Blocked | {summary['blocked']} "
        f"({summary['blocked_pct']:.1f}%) |"
    )
    out.append(
        f"| QA failed | {summary['qa_failed']} "
        f"({summary['qa_failed_pct']:.1f}%) |"
    )
    out.append(
        f"| Reaped (failed) | {summary['failed']} "
        f"({summary['failed_pct']:.1f}%) |"
    )
    out.append(
        f"| Stuck (timeout) | {summary['stuck']} "
        f"({summary['stuck_pct']:.1f}%) |"
    )
    out.append(
        f"| Skipped (budget cap) | {summary['skipped_budget']} |"
    )
    out.append(f"| Total cost (USD) | {summary['total_cost']:.4f} |")
    out.append(f"| Median cost (USD) | {summary['median_cost']:.4f} |")
    out.append(f"| P95 cost (USD) | {summary['p95_cost']:.4f} |")
    out.append("")

    # --- Tier breakdown ---
    out.append("## Tier Breakdown")
    out.append("")
    out.append("| Tier | Total | Completed | Blocked | Failed | Success rate |")
    out.append("|---|---|---|---|---|---|")
    for t in tier_breakdown:
        out.append(
            f"| {t['tier']} | {t['total']} | {t['completed']} | "
            f"{t['blocked']} | {t['failed']} | {t['success_rate']:.1f}% |"
        )
    out.append("")

    # --- Specialist roles ---
    out.append("## Specialist Role Data")
    out.append("")
    out.append("| Role | Total | pass | pass_with_notes | block | error | "
               "Mean cost (USD) | Error rate |")
    out.append("|---|---|---|---|---|---|---|---|")
    for s in specialists:
        out.append(
            f"| {s['role']} | {s['total']} | {s['pass']} | "
            f"{s['pass_with_notes']} | {s['block']} | {s['error']} | "
            f"{s['mean_cost']:.6f} | {s['error_rate']:.1f}% |"
        )
    out.append("")

    # --- Block reasons ---
    out.append("## Block Reasons (top 10)")
    out.append("")
    if block_reasons:
        out.append("| Reason | Count |")
        out.append("|---|---|")
        for reason, count in block_reasons:
            # Escape pipes in reason text so they don't break the table.
            r_esc = reason.replace("|", "\\|")
            out.append(f"| {r_esc} | {count} |")
    else:
        out.append("No blocked/qa_failed tickets had blocking_reasons populated.")
    out.append("")

    # --- Overrides ---
    out.append("## Admin Overrides")
    out.append("")
    out.append(f"Total override rows: **{overrides['total']}**")
    out.append("")
    if overrides["by_action"]:
        out.append("### By action")
        out.append("")
        out.append("| Action | Count |")
        out.append("|---|---|")
        for action, count in sorted(
            overrides["by_action"].items(), key=lambda kv: -kv[1]
        ):
            out.append(f"| {action} | {count} |")
        out.append("")
    if overrides["by_from_status"]:
        out.append("### By from-status")
        out.append("")
        out.append("| From status | Count |")
        out.append("|---|---|")
        for status, count in sorted(
            overrides["by_from_status"].items(), key=lambda kv: -kv[1]
        ):
            out.append(f"| {status} | {count} |")
        out.append("")

    # --- Mismatches ---
    out.append("## Expected-outcome Mismatches")
    out.append("")
    if mismatches:
        out.append("| Ticket | Tier | Expected | Actual | Task ID |")
        out.append("|---|---|---|---|---|")
        for m in mismatches:
            out.append(
                f"| {m['id']} | {m['tier']} | {m['expected']} | "
                f"{m['actual']} | {m.get('task_id') or 'n/a'} |"
            )
        out.append("")
        out.append(
            "> Intentional-block tickets that reached `completed` are "
            "regressions — audit the Security prompt."
        )
    else:
        out.append(
            "All tickets with `expected_outcome` set matched their actual outcome."
        )
    out.append("")

    # --- Recommendations ---
    out.append("## Recommendations")
    out.append("")
    for r in recommendations:
        out.append(f"- {r}")
    out.append("")

    return "\n".join(out)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_argparser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Phase 2 dogfood report — JSONL to markdown"
    )
    p.add_argument("--input", type=Path, required=True,
                   help="Path to harness JSONL output")
    p.add_argument("--output", type=Path, required=True,
                   help="Path to write the markdown report")
    p.add_argument("--corpus", type=Path, default=None,
                   help="Optional corpus YAML for expected_outcome lookup")
    p.add_argument("--log-level", type=str, default="INFO")
    return p


def generate_report(
    rows: list[dict[str, Any]],
    corpus_expected: dict[str, str | None],
) -> str:
    """Compute aggregates and render the markdown. Exposed for tests."""
    summary = compute_summary(rows)
    tier_breakdown = compute_tier_breakdown(rows)
    specialists = compute_specialist_roles(rows)
    block_reasons = compute_block_reasons(rows)
    overrides = compute_overrides(rows)
    mismatches = compute_mismatches(rows, corpus_expected)
    recommendations = compute_recommendations(
        summary, specialists, overrides, mismatches, tier_breakdown,
    )
    return render_markdown(
        summary=summary,
        tier_breakdown=tier_breakdown,
        specialists=specialists,
        block_reasons=block_reasons,
        overrides=overrides,
        mismatches=mismatches,
        recommendations=recommendations,
    )


def main() -> int:
    args = build_argparser().parse_args()
    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    rows = load_results(args.input)
    corpus_expected = load_corpus_expected(args.corpus)

    md = generate_report(rows, corpus_expected)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(md)
    logger.info("Wrote report to %s (%d rows)", args.output, len(rows))
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
