"""Tests for CostTracker (Task 6.6)."""

from __future__ import annotations

import pytest

from ai_driven.billing.tracker import (
    BudgetStatus,
    DailyUsage,
    calculate_cost,
)


class TestCalculateCost:
    def test_sonnet_pricing(self) -> None:
        cost = calculate_cost("claude-sonnet-4-6", input_tokens=1000, output_tokens=500)
        # 1000/1000 * 0.003 + 500/1000 * 0.015 = 0.003 + 0.0075 = 0.0105
        assert abs(cost - 0.0105) < 0.0001

    def test_opus_pricing(self) -> None:
        cost = calculate_cost("claude-opus-4-7", input_tokens=1000, output_tokens=500)
        # 1000/1000 * 0.015 + 500/1000 * 0.075 = 0.015 + 0.0375 = 0.0525
        assert abs(cost - 0.0525) < 0.0001

    def test_haiku_pricing(self) -> None:
        cost = calculate_cost("claude-haiku-4-5", input_tokens=10000, output_tokens=1000)
        # 10000/1000 * 0.0008 + 1000/1000 * 0.004 = 0.008 + 0.004 = 0.012
        assert abs(cost - 0.012) < 0.0001

    def test_unknown_model_uses_default(self) -> None:
        cost = calculate_cost("unknown-model", input_tokens=1000, output_tokens=1000)
        # Should use default pricing (sonnet equivalent)
        assert cost > 0

    def test_zero_tokens_zero_cost(self) -> None:
        cost = calculate_cost("claude-sonnet-4-6", input_tokens=0, output_tokens=0)
        assert cost == 0.0


class TestDailyUsage:
    def test_creation(self) -> None:
        usage = DailyUsage(
            team_id="team-1",
            date="2026-04-20",
            input_tokens=50000,
            output_tokens=10000,
            total_cost_usd=0.30,
        )
        assert usage.team_id == "team-1"
        assert usage.total_cost_usd == 0.30


class TestBudgetStatus:
    def test_within_budget(self) -> None:
        status = BudgetStatus(
            within_budget=True,
            usage_percent=50.0,
            daily_budget_usd=50.0,
            spent_usd=25.0,
            warning=False,
        )
        assert status.within_budget is True
        assert status.warning is False

    def test_warning_threshold(self) -> None:
        status = BudgetStatus(
            within_budget=True,
            usage_percent=85.0,
            daily_budget_usd=50.0,
            spent_usd=42.5,
            warning=True,
        )
        assert status.within_budget is True
        assert status.warning is True

    def test_over_budget(self) -> None:
        status = BudgetStatus(
            within_budget=False,
            usage_percent=110.0,
            daily_budget_usd=50.0,
            spent_usd=55.0,
            warning=True,
        )
        assert status.within_budget is False
