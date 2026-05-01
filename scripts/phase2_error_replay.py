"""T-A1 Step 3: Error replay — projected error rate after fixes.

We cannot literally replay historical specialist runs against Claude
(AWS credit is still exhausted, which is the #1 error bucket). Instead:

  * Classify every verdict='error' row by root cause (same buckets as
    scripts/phase2_error_audit.py).
  * For each bucket, determine whether the T-A1 fixes address it:

      - max_tokens_truncation    -> addressed (max_output_tokens 4096 -> 16384)
      - bedrock_prefill_validation -> already addressed in 8ed5898
      - aws_credit_exhausted     -> NOT addressed (billing); handled by the
                                    fallback flag instead
      - json_repair_escalated    -> below 10% threshold; no fix
      - other                    -> unfixed

  * Compute projected per-role error rate under two scenarios:
      1. Raw (no fallback): still_errors / total_runs
      2. With fallback: (still_errors - aws_credit_exhausted) / total_runs
         — because aws_credit errors pass through the fallback to downstream
         specialists, they no longer halt the pipeline.

This is a projection, not a real replay; honest framing in the audit doc.
"""
from __future__ import annotations

import asyncio
import json
from collections import defaultdict
from datetime import datetime, timedelta, timezone

from sqlalchemy import func, select

from ai_driven.db.postgres.models import TaskArtifact
from ai_driven.db.postgres.session import async_session_factory
import os
import sys
sys.path.insert(0, os.path.dirname(__file__))
from phase2_error_audit import _bucket  # noqa: E402


FIXED_BUCKETS = {
    "max_tokens_truncation",
    "bedrock_prefill_validation",
}
# These pass through the fallback flag as verdict=pass downstream, so in
# "effective post-fallback" math they no longer block the pipeline.
FALLBACK_ABSORBED_BUCKETS = {
    "aws_credit_exhausted",
}


async def main() -> dict:
    cutoff = datetime.now(timezone.utc) - timedelta(days=30)
    async with async_session_factory() as s:
        # Total artifacts per role (all verdicts) for the denominator.
        total_rows = await s.execute(
            select(TaskArtifact.role, func.count(TaskArtifact.id))
            .where(TaskArtifact.created_at >= cutoff)
            .group_by(TaskArtifact.role)
        )
        total_per_role = {r: int(c) for r, c in total_rows.all()}

        # Error rows, with reasoning for bucketing.
        err_rows = await s.execute(
            select(TaskArtifact.role, TaskArtifact.reasoning)
            .where(
                TaskArtifact.verdict == "error",
                TaskArtifact.created_at >= cutoff,
            )
        )
        errors_per_role: dict[str, list[str]] = defaultdict(list)
        for role, reasoning in err_rows.all():
            errors_per_role[role].append(_bucket(reasoning or ""))

    projection = {}
    for role, total in total_per_role.items():
        err_buckets = errors_per_role.get(role, [])
        before_errors = len(err_buckets)
        # After max_tokens + bedrock prefill fixes: drop those buckets.
        after_fix_errors = sum(
            1 for b in err_buckets if b not in FIXED_BUCKETS
        )
        # After fallback: drop fallback-absorbed buckets too (the error row
        # is still written to the DB, but it no longer counts as a halt for
        # pipeline-health purposes).
        after_fallback_errors = sum(
            1 for b in err_buckets
            if b not in FIXED_BUCKETS and b not in FALLBACK_ABSORBED_BUCKETS
        )
        projection[role] = {
            "total": total,
            "before_errors": before_errors,
            "before_rate_pct": round(100 * before_errors / total, 1) if total else 0,
            "after_fix_errors": after_fix_errors,
            "after_fix_rate_pct": round(100 * after_fix_errors / total, 1) if total else 0,
            "after_fallback_halt_rate_pct": round(
                100 * after_fallback_errors / total, 1
            ) if total else 0,
            "buckets": {
                b: sum(1 for x in err_buckets if x == b)
                for b in sorted(set(err_buckets))
            },
        }

    # Aggregate (specialists only, skip tech_lead which is stub).
    roles = ["business_analyst", "stack_analyst", "security", "qa"]
    agg_total = sum(total_per_role.get(r, 0) for r in roles)
    agg_before = sum(projection.get(r, {}).get("before_errors", 0) for r in roles)
    agg_after_fix = sum(projection.get(r, {}).get("after_fix_errors", 0) for r in roles)
    agg_after_fallback = sum(
        projection.get(r, {}).get("total", 0)
        * projection.get(r, {}).get("after_fallback_halt_rate_pct", 0) / 100
        for r in roles
    )

    return {
        "window_days": 30,
        "cutoff": cutoff.isoformat(),
        "per_role": projection,
        "aggregate": {
            "specialists_considered": roles,
            "total_runs": agg_total,
            "before_errors": agg_before,
            "before_rate_pct": round(100 * agg_before / agg_total, 1) if agg_total else 0,
            "after_fix_errors": agg_after_fix,
            "after_fix_rate_pct": round(100 * agg_after_fix / agg_total, 1) if agg_total else 0,
            "after_fallback_halt_rate_pct": round(
                100 * agg_after_fallback / agg_total, 1
            ) if agg_total else 0,
        },
    }


if __name__ == "__main__":
    data = asyncio.run(main())
    print(json.dumps(data, indent=2, default=str))
