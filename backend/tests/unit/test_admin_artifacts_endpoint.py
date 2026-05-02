"""T25 unit tests for GET /api/admin/tasks/{task_id}/artifacts.

Covers the full envelope the Specialists UI depends on:
- admin can GET artifacts in their team
- non-admin → 403 (via require_admin)
- cross-team access → 404 (existence leak avoidance)
- qa_failed task surfaces qaFailures + force_pass in overrideActions
- stuck orchestrating task surfaces reap in overrideActions
- fresh orchestrating → overrideActions empty

Harness style matches test_admin_override_endpoint.py — a throwaway
FastAPI app wired with a fake ``require_admin`` + mocked session.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient


ADMIN_TEAM_ID = "11111111-1111-1111-1111-111111111111"
OTHER_TEAM_ID = "22222222-2222-2222-2222-222222222222"


def _make_app(session_mock, *, admin=True):
    from ai_driven.api.admin.artifacts import router
    from ai_driven.auth.middleware import TeamMemberInfo, require_admin
    from ai_driven.db.postgres.session import get_session

    app = FastAPI()
    app.include_router(router, prefix="/api/admin")

    async def _fake_session():
        yield session_mock

    app.dependency_overrides[get_session] = _fake_session

    if admin:
        def _fake_admin():
            return TeamMemberInfo(
                member_id="00000000-0000-0000-0000-000000000001",
                team_id=ADMIN_TEAM_ID,
                role="admin",
            )
        app.dependency_overrides[require_admin] = _fake_admin
    else:
        def _deny():
            raise HTTPException(status_code=403, detail="Admin role required")
        app.dependency_overrides[require_admin] = _deny

    return TestClient(app)


def _make_task(
    *,
    task_id: uuid.UUID | None = None,
    team_id: str = ADMIN_TEAM_ID,
    status: str = "completed",
    output_data: dict | None = None,
    created_at: datetime | None = None,
    last_heartbeat_at: datetime | None = None,
) -> MagicMock:
    t = MagicMock()
    t.id = task_id or uuid.uuid4()
    t.team_id = uuid.UUID(team_id)
    t.status = status
    t.output_data = output_data or {}
    t.created_at = created_at or datetime.now(timezone.utc)
    t.last_heartbeat_at = last_heartbeat_at
    return t


def _make_artifact_row(
    *,
    role: str,
    verdict: str,
    cost_usd: Decimal = Decimal("0.001234"),
    reasoning: str = "all good",
    payload: dict | None = None,
) -> MagicMock:
    r = MagicMock()
    r.id = uuid.uuid4()
    r.role = role
    r.verdict = verdict
    r.reasoning = reasoning
    r.payload = payload or {"note": "ok"}
    r.model_used = "claude-sonnet-4-6"
    r.input_tokens = 1000
    r.output_tokens = 500
    r.cost_usd = cost_usd
    r.created_at = datetime(2026, 5, 1, 10, 0, 0, tzinfo=timezone.utc)
    return r


def _session_with_task(task, *, artifacts: list | None = None):
    """Patch TaskRepository.get_by_id + ArtifactRepository.list_rows_for_task.

    We mock the *classes* via monkeypatch-style dep injection: the
    endpoint constructs TaskRepository(session) / ArtifactRepository(session)
    itself, so we monkeypatch the classes on the endpoint module.
    """
    session = MagicMock()
    return session, task, artifacts or []


@pytest.fixture(autouse=True)
def _patch_repos(monkeypatch):
    """Each test configures _pending_task/_pending_artifacts on this
    fixture; the endpoint's TaskRepository/ArtifactRepository are
    replaced with stubs that return those values."""
    state = {"task": None, "artifacts": []}

    class FakeTaskRepo:
        def __init__(self, _session):
            pass

        async def get_by_id(self, _task_id):
            return state["task"]

    class FakeArtifactRepo:
        def __init__(self, _session):
            pass

        async def list_rows_for_task(self, _task_id):
            return state["artifacts"]

        async def list_for_task(self, _task_id):  # unused here
            return {}

    monkeypatch.setattr(
        "ai_driven.api.admin.artifacts.TaskRepository", FakeTaskRepo
    )
    monkeypatch.setattr(
        "ai_driven.api.admin.artifacts.ArtifactRepository", FakeArtifactRepo
    )
    return state


# ---------------------------------------------------------------------------
# Auth & access
# ---------------------------------------------------------------------------


def test_non_admin_gets_403(_patch_repos):
    _patch_repos["task"] = _make_task()
    client = _make_app(MagicMock(), admin=False)
    res = client.get(f"/api/admin/tasks/{_patch_repos['task'].id}/artifacts")
    assert res.status_code == 403


def test_cross_team_returns_404_not_403(_patch_repos):
    # Admin is on ADMIN_TEAM_ID; task belongs to OTHER_TEAM_ID.
    _patch_repos["task"] = _make_task(team_id=OTHER_TEAM_ID)
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{_patch_repos['task'].id}/artifacts")
    assert res.status_code == 404
    # Existence-leak avoidance — detail must NOT distinguish from "not found".
    assert "not found" in res.json()["detail"].lower()


def test_task_not_found_returns_404(_patch_repos):
    _patch_repos["task"] = None
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{uuid.uuid4()}/artifacts")
    assert res.status_code == 404


def test_bad_task_id_returns_400(_patch_repos):
    _patch_repos["task"] = None
    client = _make_app(MagicMock(), admin=True)
    res = client.get("/api/admin/tasks/not-a-uuid/artifacts")
    assert res.status_code == 400


# ---------------------------------------------------------------------------
# Response envelope
# ---------------------------------------------------------------------------


def test_empty_artifacts_returns_empty_list(_patch_repos):
    _patch_repos["task"] = _make_task(status="queued")
    _patch_repos["artifacts"] = []
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{_patch_repos['task'].id}/artifacts")
    assert res.status_code == 200
    body = res.json()
    assert body["data"]["artifacts"] == []
    # queued is not overrideable
    assert body["data"]["canOverride"] is False
    assert body["data"]["overrideActions"] == []


def test_artifact_cost_usd_serialized_as_string_with_precision(_patch_repos):
    """6-digit NUMERIC must survive the JSON hop without float rounding."""
    task = _make_task(
        status="completed",
        output_data={"classifier_tier": "full_workflow"},
    )
    _patch_repos["task"] = task
    _patch_repos["artifacts"] = [
        _make_artifact_row(
            role="business_analyst",
            verdict="pass",
            cost_usd=Decimal("0.000123"),
        ),
    ]
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{task.id}/artifacts")
    assert res.status_code == 200
    body = res.json()
    assert body["data"]["classifierTier"] == "full_workflow"
    [artifact] = body["data"]["artifacts"]
    assert isinstance(artifact["costUsd"], str)
    assert artifact["costUsd"] == "0.000123"
    assert artifact["role"] == "business_analyst"
    assert artifact["verdict"] == "pass"
    assert artifact["inputTokens"] == 1000
    assert artifact["outputTokens"] == 500


def test_qa_failed_task_surfaces_qa_failures_and_force_pass(_patch_repos):
    qa_failures = [
        {
            "kind": "ac_unsatisfied",
            "criterion": "User can reset password",
            "reason": "No test covers the empty-email edge case",
            "evidence": "tests/auth_test.go:42",
            "severity": "high",
        },
        {
            "kind": "security_rule_unfollowed",
            "rule": "Parameterized queries only",
            "reason": "Raw SQL concatenation in db.go:88",
            "evidence": "db.go:88",
            "severity": "critical",
        },
    ]
    task = _make_task(
        status="qa_failed",
        output_data={
            "qa_verdict": "block",
            "qa_failures": qa_failures,
        },
    )
    _patch_repos["task"] = task
    _patch_repos["artifacts"] = [
        _make_artifact_row(role="qa", verdict="block"),
    ]
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{task.id}/artifacts")
    assert res.status_code == 200
    body = res.json()
    assert body["data"]["qaVerdict"] == "block"
    assert body["data"]["qaFailures"] == qa_failures
    # qa_failed surfaces both rerun_qa + force_pass
    assert body["data"]["canOverride"] is True
    assert set(body["data"]["overrideActions"]) == {"rerun_qa", "force_pass"}


def test_stuck_orchestrating_offers_reap(_patch_repos):
    old = datetime.now(timezone.utc) - timedelta(minutes=45)
    task = _make_task(
        status="orchestrating",
        output_data={"started_at": old.isoformat()},
        created_at=old,
    )
    _patch_repos["task"] = task
    _patch_repos["artifacts"] = []
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{task.id}/artifacts")
    assert res.status_code == 200
    body = res.json()
    assert body["data"]["canOverride"] is True
    assert body["data"]["overrideActions"] == ["reap"]


def test_fresh_orchestrating_has_empty_override_actions(_patch_repos):
    fresh = datetime.now(timezone.utc) - timedelta(minutes=2)
    task = _make_task(
        status="orchestrating",
        output_data={"started_at": fresh.isoformat()},
        created_at=fresh,
    )
    _patch_repos["task"] = task
    _patch_repos["artifacts"] = []
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{task.id}/artifacts")
    assert res.status_code == 200
    body = res.json()
    assert body["data"]["canOverride"] is False
    assert body["data"]["overrideActions"] == []


def test_completed_task_has_empty_override_actions(_patch_repos):
    """Completed is terminal — UI must not render override buttons."""
    task = _make_task(
        status="completed",
        output_data={"qa_verdict": "pass"},
    )
    _patch_repos["task"] = task
    _patch_repos["artifacts"] = [
        _make_artifact_row(role="qa", verdict="pass"),
    ]
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{task.id}/artifacts")
    assert res.status_code == 200
    body = res.json()
    assert body["data"]["canOverride"] is False
    assert body["data"]["overrideActions"] == []
    assert body["data"]["qaVerdict"] == "pass"


def test_artifacts_endpoint_mounted_on_admin_router():
    """The T25 endpoint must be reachable through the real admin router
    (not just the isolated sub-router) so a routing change in
    admin/__init__.py would fail this test loudly."""
    from ai_driven.api.admin.router import router as admin_router

    paths = {getattr(r, "path", "") for r in admin_router.routes}
    assert any(
        "/tasks/{task_id}/artifacts" in p for p in paths
    ), f"Expected artifacts route on admin router; got {paths!r}"


def test_blocked_offers_rerun_coding(_patch_repos):
    task = _make_task(status="blocked")
    _patch_repos["task"] = task
    _patch_repos["artifacts"] = [
        _make_artifact_row(role="security", verdict="block"),
    ]
    client = _make_app(MagicMock(), admin=True)
    res = client.get(f"/api/admin/tasks/{task.id}/artifacts")
    assert res.status_code == 200
    body = res.json()
    assert body["data"]["canOverride"] is True
    assert body["data"]["overrideActions"] == ["rerun_coding"]
