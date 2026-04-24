"""Cost tracking and budget enforcement per team per day."""

from __future__ import annotations

import datetime
import logging
from dataclasses import dataclass
from typing import Any

logger = logging.getLogger(__name__)

# Claude model pricing (USD per 1K tokens) — as of 2025
# These are approximate; update as pricing changes.
_MODEL_PRICING: dict[str, dict[str, float]] = {
    # Anthropic Direct
    "claude-opus-4-7": {"input": 0.015, "output": 0.075},
    "claude-opus-4-6": {"input": 0.015, "output": 0.075},
    "claude-sonnet-4-6": {"input": 0.003, "output": 0.015},
    "claude-haiku-4-5": {"input": 0.0008, "output": 0.004},
    # Bedrock model IDs
    "anthropic.claude-opus-4-7-v1": {"input": 0.015, "output": 0.075},
    "anthropic.claude-opus-4-6-v1": {"input": 0.015, "output": 0.075},
    "anthropic.claude-sonnet-4-6-v1": {"input": 0.003, "output": 0.015},
    "anthropic.claude-haiku-4-5-v1": {"input": 0.0008, "output": 0.004},
}

# Default fallback pricing for unknown models
_DEFAULT_PRICING = {"input": 0.003, "output": 0.015}

# Default daily budget per team (USD)
DEFAULT_DAILY_BUDGET_USD = 50.0

# Budget warning threshold
_BUDGET_WARNING_THRESHOLD = 0.80

# Redis key prefixes
_COST_PREFIX = "cost:"
_USAGE_PREFIX = "usage:"


@dataclass
class DailyUsage:
    """Usage summary for a specific day."""

    team_id: str
    date: str
    input_tokens: int
    output_tokens: int
    total_cost_usd: float


@dataclass
class BudgetStatus:
    """Budget check result."""

    within_budget: bool
    usage_percent: float
    daily_budget_usd: float
    spent_usd: float
    warning: bool  # True if > 80%


def calculate_cost(
    model: str, input_tokens: int, output_tokens: int
) -> float:
    """Calculate the cost in USD for a given model and token counts.

    Args:
        model: The Claude model identifier.
        input_tokens: Number of input tokens consumed.
        output_tokens: Number of output tokens consumed.

    Returns:
        Cost in USD (rounded to 6 decimal places).
    """
    pricing = _MODEL_PRICING.get(model, _DEFAULT_PRICING)
    cost = (input_tokens / 1000.0) * pricing["input"] + (
        output_tokens / 1000.0
    ) * pricing["output"]
    return round(cost, 6)


class CostTracker:
    """Track Claude API cost per team per day using Redis.

    Gracefully degrades when Redis is unavailable: usage recording is
    skipped, budget checks return within-budget, and daily usage returns zeros.
    """

    def __init__(self, redis_url: str = "", daily_budget_usd: float = DEFAULT_DAILY_BUDGET_USD) -> None:
        self._redis_url = redis_url
        self._redis: Any = None
        self._redis_init_attempted = False
        self.daily_budget_usd = daily_budget_usd

    async def _get_redis(self) -> Any:
        """Lazy-initialize Redis client."""
        if self._redis_init_attempted:
            return self._redis
        self._redis_init_attempted = True
        try:
            import redis.asyncio as aioredis

            url = self._redis_url
            if not url:
                from ai_driven.config import get_settings
                url = get_settings().redis_url
            self._redis = aioredis.from_url(
                url,
                decode_responses=True,
                socket_connect_timeout=2,
            )
            await self._redis.ping()
            return self._redis
        except Exception:
            logger.warning("Redis unavailable for cost tracking — tracking disabled")
            self._redis = None
            return None

    async def record_usage(
        self, team_id: str, model: str, input_tokens: int, output_tokens: int
    ) -> None:
        """Record token usage and calculate cost.

        Stores daily totals in a Redis hash keyed by team and date.

        Args:
            team_id: The team identifier.
            model: The Claude model used (e.g., "claude-sonnet-4-6").
            input_tokens: Number of input tokens consumed.
            output_tokens: Number of output tokens consumed.
        """
        if input_tokens < 0 or output_tokens < 0:
            raise ValueError("Token counts must be non-negative")

        cost = calculate_cost(model, input_tokens, output_tokens)

        redis = await self._get_redis()
        if redis is None:
            return

        try:
            today = datetime.date.today().isoformat()
            key = f"{_USAGE_PREFIX}{team_id}:{today}"

            # Store cost as integer micro-dollars to avoid float precision issues
            cost_microdollars = int(cost * 1_000_000)

            pipe = redis.pipeline()
            pipe.hincrby(key, "input_tokens", input_tokens)
            pipe.hincrby(key, "output_tokens", output_tokens)
            pipe.hincrby(key, "cost_microdollars", cost_microdollars)
            pipe.hincrby(key, "request_count", 1)
            # Expire after 48 hours for cleanup
            pipe.expire(key, 172800)
            await pipe.execute()

            logger.info(
                "usage_recorded",
                extra={
                    "model": model,
                    "input_tokens": input_tokens,
                    "output_tokens": output_tokens,
                    "cost_usd": cost,
                },
            )
        except Exception:
            logger.warning("Failed to record usage in Redis", exc_info=True)

    async def get_daily_usage(
        self, team_id: str, date: str | None = None
    ) -> DailyUsage:
        """Get usage for a specific day.

        Args:
            team_id: The team identifier.
            date: The date in ISO format (YYYY-MM-DD). Defaults to today.

        Returns:
            DailyUsage with token counts and cost.
        """
        if date is None:
            date = datetime.date.today().isoformat()

        redis = await self._get_redis()
        if redis is None:
            return DailyUsage(
                team_id=team_id,
                date=date,
                input_tokens=0,
                output_tokens=0,
                total_cost_usd=0.0,
            )

        try:
            key = f"{_USAGE_PREFIX}{team_id}:{date}"
            data = await redis.hgetall(key)

            input_tokens = int(data.get("input_tokens", 0))
            output_tokens = int(data.get("output_tokens", 0))
            cost_microdollars = int(data.get("cost_microdollars", 0))

            return DailyUsage(
                team_id=team_id,
                date=date,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                total_cost_usd=round(cost_microdollars / 1_000_000, 6),
            )
        except Exception:
            logger.warning("Failed to read daily usage from Redis", exc_info=True)
            return DailyUsage(
                team_id=team_id,
                date=date,
                input_tokens=0,
                output_tokens=0,
                total_cost_usd=0.0,
            )

    async def check_budget(self, team_id: str) -> BudgetStatus:
        """Check if team is within budget. Warn at 80%, block at 100%.

        Args:
            team_id: The team identifier.

        Returns:
            BudgetStatus with budget information and warning/blocking flags.
        """
        usage = await self.get_daily_usage(team_id)
        spent = usage.total_cost_usd
        budget = self.daily_budget_usd

        if budget <= 0:
            # No budget set — allow everything
            return BudgetStatus(
                within_budget=True,
                usage_percent=0.0,
                daily_budget_usd=budget,
                spent_usd=spent,
                warning=False,
            )

        usage_percent = round((spent / budget) * 100, 2)
        within_budget = spent < budget
        warning = usage_percent >= (_BUDGET_WARNING_THRESHOLD * 100)

        if warning and within_budget:
            logger.warning(
                "budget_warning",
                extra={
                    "usage_percent": usage_percent,
                    "spent_usd": spent,
                    "budget_usd": budget,
                },
            )
        elif not within_budget:
            logger.warning(
                "budget_exceeded",
                extra={
                    "usage_percent": usage_percent,
                    "spent_usd": spent,
                    "budget_usd": budget,
                },
            )

        return BudgetStatus(
            within_budget=within_budget,
            usage_percent=usage_percent,
            daily_budget_usd=budget,
            spent_usd=spent,
            warning=warning,
        )

    async def close(self) -> None:
        """Close the Redis connection."""
        if self._redis is not None:
            await self._redis.close()
            self._redis = None
            self._redis_init_attempted = False
