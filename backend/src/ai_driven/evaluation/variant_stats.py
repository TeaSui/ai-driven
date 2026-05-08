"""Aggregator for agent_prompt_variant_stats.

Recomputes (variant_id, dimension) → (n_samples, avg, stddev) from
``task_evaluations ⋈ tasks``. Idempotent: each refresh wipes the table
for variants it touches and re-inserts the current rollup. Safe to run
on a schedule (nightly) or on-demand from the admin UI.
"""
from __future__ import annotations

import logging
from typing import Any

from sqlalchemy import delete, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.db.postgres.models import AgentPromptVariantStat
from ai_driven.db.postgres.session import async_session_factory

logger = logging.getLogger(__name__)


async def refresh_stats() -> dict[str, Any]:
    """Recompute the rollup and overwrite the stats table.

    Returns ``{"variants_touched": int, "rows_written": int}``.
    """
    async with async_session_factory() as session:
        # Compute the rollup in one SQL pass. LEFT JOIN so variants
        # with zero evals still appear in the output (with n=0) — the
        # admin UI then shows "no data yet" rather than hiding the row.
        rows = (await session.execute(
            text(
                """
                SELECT
                    t.prompt_variant_id AS variant_id,
                    te.dimension AS dimension,
                    count(*)::int AS n,
                    round(avg(te.score)::numeric, 2) AS avg_score,
                    round(coalesce(stddev(te.score), 0)::numeric, 2) AS stddev_score
                FROM task_evaluations te
                JOIN tasks t ON t.id = te.task_id
                WHERE t.prompt_variant_id IS NOT NULL
                GROUP BY t.prompt_variant_id, te.dimension
                """
            )
        )).mappings().all()

        # Wipe existing rollup to avoid stale rows when a (variant,
        # dimension) combination no longer has samples.
        await session.execute(delete(AgentPromptVariantStat))

        for r in rows:
            session.add(AgentPromptVariantStat(
                variant_id=r["variant_id"],
                dimension=r["dimension"],
                n_samples=int(r["n"]),
                avg_score=r["avg_score"],
                stddev_score=r["stddev_score"],
            ))
        await session.commit()
        logger.info(
            "variant_stats.refreshed rows=%d", len(rows),
        )
        return {"variants_touched": len({r["variant_id"] for r in rows}),
                "rows_written": len(rows)}
