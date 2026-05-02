"""T-A1 Step 1: Error audit.

Reads every task_artifacts row with verdict='error' from the last 30 days,
groups them by reasoning-pattern, and emits a JSON summary the audit doc
is generated from.

Read-only. Safe to re-run.
"""
from __future__ import annotations

import asyncio
import json
import re
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal

from sqlalchemy import select

from ai_driven.db.postgres.models import TaskArtifact
from ai_driven.db.postgres.session import async_session_factory


PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    # Order matters — more specific first.
    ("max_tokens_truncation", re.compile(r"stop_reason=max_tokens|output truncated", re.I)),
    ("bedrock_prefill_validation", re.compile(r"assistant message prefill|ValidationException.*prefill", re.I)),
    ("bedrock_validation_other", re.compile(r"ValidationException", re.I)),
    ("throttling", re.compile(r"ThrottlingException|TooManyRequests|ServiceUnavailable", re.I)),
    ("aws_credit_exhausted", re.compile(r"credit balance is too low|403.*credit", re.I)),
    ("json_repair_escalated", re.compile(r"JSON invalid after 2 repair rounds", re.I)),
    ("json_parse_post_repair", re.compile(r"JSON parse failed post-repair", re.I)),
    ("payload_schema_mismatch", re.compile(r"payload schema mismatch", re.I)),
    ("claude_call_failed_other", re.compile(r"claude call failed", re.I)),
]


def _bucket(reasoning: str) -> str:
    for label, pat in PATTERNS:
        if pat.search(reasoning or ""):
            return label
    return "other"


async def main() -> dict:
    cutoff = datetime.now(timezone.utc) - timedelta(days=30)
    async with async_session_factory() as s:
        res = await s.execute(
            select(
                TaskArtifact.id,
                TaskArtifact.role,
                TaskArtifact.reasoning,
                TaskArtifact.cost_usd,
                TaskArtifact.input_tokens,
                TaskArtifact.output_tokens,
                TaskArtifact.model_used,
                TaskArtifact.task_id,
                TaskArtifact.created_at,
            ).where(
                TaskArtifact.verdict == "error",
                TaskArtifact.created_at >= cutoff,
            ).order_by(TaskArtifact.created_at.desc())
        )
        rows = res.all()

    buckets: dict[str, list[dict]] = defaultdict(list)
    buckets_by_role: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    total_per_role: dict[str, int] = defaultdict(int)
    cost_nonzero: int = 0
    cost_zero: int = 0

    for row in rows:
        label = _bucket(row.reasoning or "")
        rec = {
            "id": str(row.id),
            "task_id": str(row.task_id),
            "role": row.role,
            "reasoning_excerpt": (row.reasoning or "")[:400],
            "cost_usd": str(row.cost_usd) if row.cost_usd is not None else None,
            "input_tokens": row.input_tokens,
            "output_tokens": row.output_tokens,
            "model_used": row.model_used,
            "created_at": row.created_at.isoformat() if row.created_at else None,
        }
        buckets[label].append(rec)
        buckets_by_role[row.role][label] += 1
        total_per_role[row.role] += 1
        if row.cost_usd and Decimal(str(row.cost_usd)) > 0:
            cost_nonzero += 1
        else:
            cost_zero += 1

    summary = {}
    total = len(rows)
    for label, recs in sorted(buckets.items(), key=lambda kv: -len(kv[1])):
        summary[label] = {
            "count": len(recs),
            "pct": round(100 * len(recs) / total, 1) if total else 0,
            "examples": [r["reasoning_excerpt"] for r in recs[:3]],
            "roles": {r: sum(1 for x in recs if x["role"] == r) for r in sorted({x["role"] for x in recs})},
        }

    return {
        "window_days": 30,
        "cutoff": cutoff.isoformat(),
        "total_error_artifacts": total,
        "by_pattern": summary,
        "by_role": {
            role: dict(patterns) for role, patterns in buckets_by_role.items()
        },
        "total_per_role": dict(total_per_role),
        "cost_attribution_check": {
            "error_rows_with_nonzero_cost": cost_nonzero,
            "error_rows_with_zero_or_null_cost": cost_zero,
        },
        "raw_sample_per_bucket": {
            label: recs[:5] for label, recs in buckets.items()
        },
    }


if __name__ == "__main__":
    data = asyncio.run(main())
    print(json.dumps(data, indent=2, default=str))
