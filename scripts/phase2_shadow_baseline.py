"""T0: Shadow baseline analysis.

Queries task_artifacts + tasks to compute per-role verdict distribution,
block false-positive rate, and emits a JSON summary consumed by the
phase2-shadow-baseline report generator.
"""

from __future__ import annotations

import asyncio
import json
from collections import defaultdict
from decimal import Decimal

from sqlalchemy import func, select

from ai_driven.db.postgres.models import Task, TaskArtifact
from ai_driven.db.postgres.session import async_session_factory


ROLES = ["business_analyst", "stack_analyst", "security", "qa"]
VERDICTS = ["pass", "pass_with_notes", "block", "error", "not_implemented"]


def _to_float(v):
    if isinstance(v, Decimal):
        return float(v)
    return v


async def main() -> dict:
    async with async_session_factory() as s:
        # 1. per-role verdict counts + summed cost
        res = await s.execute(
            select(
                TaskArtifact.role,
                TaskArtifact.verdict,
                func.count(TaskArtifact.id),
                func.coalesce(func.sum(TaskArtifact.cost_usd), 0),
            ).group_by(TaskArtifact.role, TaskArtifact.verdict)
        )
        grid: dict[str, dict[str, dict[str, float]]] = defaultdict(
            lambda: defaultdict(lambda: {"count": 0, "cost": 0.0})
        )
        for role, verdict, count, cost in res.all():
            grid[role][verdict] = {"count": int(count), "cost": _to_float(cost)}

        # 2. For each role + verdict=block, pull the task status
        blocks = await s.execute(
            select(TaskArtifact.role, TaskArtifact.task_id, Task.status)
            .join(Task, Task.id == TaskArtifact.task_id)
            .where(TaskArtifact.verdict == "block")
        )
        block_rows = blocks.all()
        fp_analysis: dict[str, dict[str, int]] = defaultdict(
            lambda: {
                "total_blocks": 0,
                "fp_count": 0,           # task completed despite block
                "uncertain_count": 0,    # task failed/cancelled
                "in_progress_count": 0,  # task still running
            }
        )
        for role, _tid, status in block_rows:
            fp_analysis[role]["total_blocks"] += 1
            if status == "completed":
                fp_analysis[role]["fp_count"] += 1
            elif status in ("failed", "cancelled", "canceled"):
                fp_analysis[role]["uncertain_count"] += 1
            else:
                fp_analysis[role]["in_progress_count"] += 1

        # 3. total artifact count
        total = await s.scalar(select(func.count(TaskArtifact.id)))

        # 4. distinct roles in DB (in case others beyond expected ROLES)
        distinct_roles_res = await s.execute(
            select(TaskArtifact.role).distinct()
        )
        distinct_roles = sorted({r for (r,) in distinct_roles_res.all()})

        # 5. distinct statuses (for reference)
        status_res = await s.execute(
            select(Task.status, func.count(Task.id)).group_by(Task.status)
        )
        statuses = {row[0]: int(row[1]) for row in status_res.all()}

        return {
            "total_artifacts": int(total or 0),
            "distinct_roles": distinct_roles,
            "task_status_distribution": statuses,
            "verdict_grid": {
                role: dict(verdicts) for role, verdicts in grid.items()
            },
            "fp_analysis": {
                role: dict(v) for role, v in fp_analysis.items()
            },
        }


if __name__ == "__main__":
    data = asyncio.run(main())
    print(json.dumps(data, indent=2, default=str))
