"""Regression tests for the POST /api/chat/send budget hard-stop (RULE-D5).

Bug: the check imported a module that never existed
(``ai_driven.observability.cost_tracker``) and then read dict-style keys
(``budget.get("exceeded")``) off ``CostTracker.check_budget()``'s actual
return type, a ``BudgetStatus`` dataclass with no ``.get`` method. Both
failures were swallowed by a broad ``except Exception: fail open``, so an
over-budget team was silently let through with no visible error — the
budget hard-stop never actually fired.
"""

from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_driven.api.chat.router import router
from ai_driven.auth.rate_limiter import RateLimitResult
from ai_driven.billing.tracker import BudgetStatus
from ai_driven.dependencies import get_team_id

TEAM_ID = str(uuid.uuid4())


def _budget(within_budget: bool, usage_percent: float = 0.0) -> BudgetStatus:
    return BudgetStatus(
        within_budget=within_budget,
        usage_percent=usage_percent,
        daily_budget_usd=50.0,
        spent_usd=50.0 if not within_budget else 0.0,
        warning=False,
    )


@pytest.fixture
def client():
    app = FastAPI()
    app.include_router(router, prefix="/api/chat")
    app.dependency_overrides[get_team_id] = lambda: TEAM_ID
    return TestClient(app)


class TestBudgetHardStop:
    def test_over_budget_blocks_with_429(self, client):
        with patch(
            "ai_driven.billing.tracker.CostTracker.check_budget",
            new=AsyncMock(return_value=_budget(within_budget=False, usage_percent=100.0)),
        ):
            resp = client.post("/api/chat/send", json={"message": "do work"})

        assert resp.status_code == 429
        assert resp.json()["error"]["code"] == "BUDGET_EXCEEDED"

    def test_under_budget_is_not_blocked(self, client):
        with (
            patch(
                "ai_driven.billing.tracker.CostTracker.check_budget",
                new=AsyncMock(return_value=_budget(within_budget=True)),
            ),
            patch(
                "ai_driven.auth.rate_limiter.RateLimiter.check_rate_limit",
                new=AsyncMock(return_value=RateLimitResult(allowed=True, remaining=9, limit=10)),
            ),
            patch(
                "ai_driven.auth.rate_limiter.RateLimiter.check_concurrent",
                new=AsyncMock(return_value=RateLimitResult(allowed=True, remaining=4, limit=5)),
            ),
            patch(
                "ai_driven.auth.rate_limiter.RateLimiter.increment_concurrent",
                new=AsyncMock(),
            ),
            patch(
                "ai_driven.db.postgres.session.async_session_factory",
                side_effect=RuntimeError("no db in unit test"),
            ),
            patch(
                "ai_driven.queue.producer.TaskProducer.enqueue_task",
                new=AsyncMock(),
            ),
        ):
            # agentType="devops" avoids the unrelated REPO_REQUIRED 400 that
            # "coding"/"review" would trigger without a repo_full_name — this
            # test is only about the budget check, not repo validation.
            resp = client.post(
                "/api/chat/send",
                json={"message": "do work", "agentType": "devops"},
            )

        assert resp.status_code == 200
        assert resp.json()["status"] == "queued"
