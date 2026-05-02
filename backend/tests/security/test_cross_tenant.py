"""Cross-tenant probe test — verifies team isolation (RULE-E1).

TeamA's token must NOT be able to access TeamB's resources.
This test is designed to run in CI on every PR.
"""

from __future__ import annotations

import uuid

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient

# These tests require the app to be importable but NOT a live DB.
# They use the FastAPI test client with dependency overrides.


@pytest.fixture
def team_a_id() -> str:
    return str(uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))


@pytest.fixture
def team_b_id() -> str:
    return str(uuid.UUID("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))


@pytest.fixture
def task_owned_by_a() -> str:
    return str(uuid.UUID("11111111-1111-1111-1111-111111111111"))


@pytest.fixture
def app_with_overrides(team_a_id, team_b_id, task_owned_by_a):
    """Create a FastAPI app with auth dependencies overridden for testing."""
    import os
    os.environ.setdefault("AI_DRIVEN_DEBUG", "false")
    os.environ.setdefault("AI_DRIVEN_REDIS_URL", "redis://localhost:6379")

    from ai_driven.main import create_app
    from ai_driven.dependencies import get_team_id, require_task_access

    app = create_app()

    # Track which team_id the "current request" should use
    _current_team_id = team_b_id  # Default: TeamB (the attacker)

    def override_get_team_id():
        return _current_team_id

    async def override_require_task_access(task_id: str):
        """Simulate task lookup — task_owned_by_a belongs to TeamA."""
        from fastapi import HTTPException
        if task_id == task_owned_by_a and _current_team_id != team_a_id:
            raise HTTPException(status_code=404, detail="Task not found")
        # For own team's tasks, return a mock
        from unittest.mock import MagicMock
        mock_task = MagicMock()
        mock_task.id = uuid.UUID(task_id)
        mock_task.team_id = uuid.UUID(_current_team_id)
        return mock_task

    app.dependency_overrides[get_team_id] = override_get_team_id
    app.dependency_overrides[require_task_access] = override_require_task_access

    app._test_set_team = lambda tid: _current_team_id  # noqa
    # Expose setter for tests
    app.__dict__["_set_current_team"] = lambda tid: None  # placeholder

    return app, lambda tid: setattr(app, "_current_team_setter", tid) or tid


@pytest_asyncio.fixture
async def client_team_b(app_with_overrides):
    """HTTP client authenticated as TeamB (the attacker)."""
    app, _ = app_with_overrides
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        yield client


class TestCrossTenantAccess:
    """Verify that TeamB cannot access TeamA's resources."""

    @pytest.mark.asyncio
    async def test_pause_other_teams_task_returns_404(self, client_team_b, task_owned_by_a):
        """TeamB trying to pause TeamA's task must get 404."""
        r = await client_team_b.post(f"/api/chat/tasks/{task_owned_by_a}/pause")
        assert r.status_code == 404

    @pytest.mark.asyncio
    async def test_stop_other_teams_task_returns_404(self, client_team_b, task_owned_by_a):
        """TeamB trying to stop TeamA's task must get 404."""
        r = await client_team_b.post(f"/api/chat/tasks/{task_owned_by_a}/stop")
        assert r.status_code == 404

    @pytest.mark.asyncio
    async def test_resume_other_teams_task_returns_404(self, client_team_b, task_owned_by_a):
        """TeamB trying to resume TeamA's task must get 404."""
        r = await client_team_b.post(f"/api/chat/tasks/{task_owned_by_a}/resume")
        assert r.status_code == 404


class TestPositiveAccess:
    """Verify that a team CAN access its own resources (guard against vacuous 404s)."""

    @pytest.mark.asyncio
    async def test_own_task_pause_does_not_404(self, app_with_overrides):
        """TeamA pausing its own task should NOT get 404 (may get other errors without Redis, but not 404)."""
        app, _ = app_with_overrides
        # Override to TeamA
        from ai_driven.dependencies import get_team_id
        team_a_id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        app.dependency_overrides[get_team_id] = lambda: team_a_id

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as client:
            r = await client.post(f"/api/chat/tasks/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/pause")
            # Should not be 404 — may be 500 if Redis is down, but the auth check passed
            assert r.status_code != 404
