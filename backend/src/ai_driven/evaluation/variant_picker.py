"""Dispatch-time prompt-variant picker.

Shape:

* ``load_variant_for(agent_type)`` → returns (variant_id, system_prompt) by
  weighted random pick over the registry. Returns (None, None) when no
  variants exist for the agent_type, so the caller falls back to the
  compiled-in prompt (zero-risk rollout).
* ``get_variant_prompt(variant_id)`` → fetches the stored prompt for an
  already-assigned variant (used by the agent to look up the prompt it
  was dispatched with). Returns None on cache miss.

Keeps all SQL inline and short. No per-process cache yet; the dispatch
path already sits inside an SQS receive cycle and the variant registry
is tiny (< 50 rows expected), so a hot SELECT is fine. Revisit if a
production workload shows dispatch-latency pressure.
"""
from __future__ import annotations

import logging
import random
import uuid
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.db.postgres.models import AgentPromptVariant

logger = logging.getLogger(__name__)


async def pick_variant(
    session: AsyncSession, agent_type: str,
) -> tuple[Optional[uuid.UUID], Optional[str]]:
    """Return (variant_id, system_prompt) or (None, None)."""
    result = await session.execute(
        select(AgentPromptVariant).where(
            AgentPromptVariant.agent_type == agent_type,
            AgentPromptVariant.weight > 0,
        )
    )
    rows = list(result.scalars().all())
    if not rows:
        return None, None
    weights = [int(r.weight or 0) for r in rows]
    total = sum(weights)
    if total <= 0:
        return None, None
    roll = random.randint(1, total)
    cum = 0
    for r, w in zip(rows, weights):
        cum += w
        if roll <= cum:
            return r.id, r.system_prompt
    # Shouldn't reach here; safety fallback.
    last = rows[-1]
    return last.id, last.system_prompt


async def get_variant_prompt(
    session: AsyncSession, variant_id: uuid.UUID,
) -> Optional[str]:
    """Fetch the stored prompt for an already-assigned variant."""
    result = await session.execute(
        select(AgentPromptVariant.system_prompt).where(
            AgentPromptVariant.id == variant_id,
        )
    )
    return result.scalar_one_or_none()
