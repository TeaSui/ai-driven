"""T27 unit tests for GET /api/admin/override-audit.

Pattern mirrors test_admin_artifacts_endpoint.py:
  * admin on the task's team gets rows
  * non-admin → 403 (via require_admin)
  * cross-team access → 404 (existence leak avoidance)
  * task with no overrides → empty data array
  * endpoint is reachable through the real admin router mount
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from unittest.mock import MagicMock

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient


ADMIN_TEAM_ID = "11111111-1111-1111-1111-111111111111"
OTHER_TEAM_ID = "22222222-2222-2222-2222-222222222222"


def _make_app(session_mock, *, admin=True):
    from ai_driven.api.admin.override_audit import router
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
    status: str = "qa_failed",
) -> MagicMock:
    t = MagicMock()
    t.id = task_id or uuid.uuid4()
    t.team_id = uuid.UUID(team_id)
    t.status = status
    return t


def _make_audit_row(
    *,
    task_id: uuid.UUID,
    action: str = "force_pass",
    from_status: str = "qa_failed",
    to_status: str = "completed",
    reason: str = "manual review",
    created_at: datetime | None = None,
) -> MagicMock:
    r = MagicMock()
    r.id = uuid.uuid4()
    r.task_id = task_id
    r.overridden_by_member_id = uuid.UUID(
        "00000000-0000-0000-0000-000000000001"
    )
    r.from_status = from_status
    r.to_status = to_status
    r.action = action
    r.reason = reason
    r.created_at = created_at or datetime(
        2026, 5, 1, 10, 0, 0, tzinfo=timezone.utc
    )
    return r


@pytest.fixture(autouse=True)
def _patch(monkeypatch):
    """Patch TaskRepository + session.execute on the endpoint module.

    The endpoint constructs TaskRepository(session) and calls
    session.execute(...) directly; we stub both so the tests don't
    need a real DB.
    """
    state = {"task": None, "audit_rows": []}

    class FakeTaskRepo:
        def __init__(self, _session):
            pass

        async def get_by_id(self, _task_id):
            return state["task"]

    monkeypatch.setattr(
        "ai_driven.api.admin.override_audit.TaskRepository", FakeTaskRepo
    )

    # Stub the bare `session.execute` via a per-call fake that returns
    # an object whose .scalars().all() yields the seeded audit rows.
    class FakeResult:
        def __init__(self, rows):
            self._rows = rows

        def scalars(self):
            inner = MagicMock()
            inner.all = lambda: self._rows
            return inner

    def _patched_execute_factory():
        async def _execute(_stmt):
            return FakeResult(state["audit_rows"])
        return _execute

    # We patch per-test by setting an async execute on the MagicMock
    # session. See tests below.
    state["_make_result"] = lambda rows: FakeResult(rows)
    return state


def _session_with_audit(audit_rows: list) -> MagicMock:
    """Return a MagicMock session whose execute() resolves to the
    seeded audit rows on each call."""
    session = MagicMock()

    class FakeResult:
        def scalars(self):
            inner = MagicMock()
            inner.all = lambda: audit_rows
            return inner

    async def _execute(_stmt):
        return FakeResult()

    session.execute = _execute
    return session


# ---------------------------------------------------------------------------
# Auth & access
# ---------------------------------------------------------------------------


def test_non_admin_gets_403(_patch):
    _patch["task"] = _make_task()
    client = _make_app(_session_with_audit([]), admin=False)
    res = client.get(
        f"/api/admin/override-audit?task_id={_patch['task'].id}"
    )
    assert res.status_code == 403


def test_cross_team_returns_404_not_403(_patch):
    _patch["task"] = _make_task(team_id=OTHER_TEAM_ID)
    client = _make_app(_session_with_audit([]), admin=True)
    res = client.get(
        f"/api/admin/override-audit?task_id={_patch['task'].id}"
    )
    assert res.status_code == 404
    # Existence-leak avoidance — same wording as the T25 pattern.
    assert "not found" in res.json()["detail"].lower()


def test_task_not_found_returns_404(_patch):
    _patch["task"] = None
    client = _make_app(_session_with_audit([]), admin=True)
    res = client.get(f"/api/admin/override-audit?task_id={uuid.uuid4()}")
    assert res.status_code == 404


def test_bad_task_id_returns_400(_patch):
    _patch["task"] = None
    client = _make_app(_session_with_audit([]), admin=True)
    res = client.get("/api/admin/override-audit?task_id=not-a-uuid")
    assert res.status_code == 400


def test_missing_task_id_returns_422(_patch):
    """task_id is a required query param — FastAPI returns 422."""
    _patch["task"] = _make_task()
    client = _make_app(_session_with_audit([]), admin=True)
    res = client.get("/api/admin/override-audit")
    assert res.status_code == 422


# ---------------------------------------------------------------------------
# Response envelope
# ---------------------------------------------------------------------------


def test_task_with_no_overrides_returns_empty_list(_patch):
    task = _make_task(status="completed")
    _patch["task"] = task
    client = _make_app(_session_with_audit([]), admin=True)
    res = client.get(f"/api/admin/override-audit?task_id={task.id}")
    assert res.status_code == 200
    body = res.json()
    assert body["data"] == []
    assert "timestamp" in body["meta"]


def test_admin_gets_audit_rows(_patch):
    task = _make_task(status="completed")
    _patch["task"] = task
    rows = [
        _make_audit_row(
            task_id=task.id, action="force_pass",
            from_status="qa_failed", to_status="completed",
            reason="manual review — QA false-positive",
        ),
        _make_audit_row(
            task_id=task.id, action="rerun_qa",
            from_status="qa_failed", to_status="awaiting_qa",
            reason="retry after prompt fix",
        ),
    ]
    _patch["audit_rows"] = rows
    client = _make_app(_session_with_audit(rows), admin=True)
    res = client.get(f"/api/admin/override-audit?task_id={task.id}")
    assert res.status_code == 200
    body = res.json()
    assert len(body["data"]) == 2

    first = body["data"][0]
    assert first["taskId"] == str(task.id)
    assert first["action"] == "force_pass"
    assert first["fromStatus"] == "qa_failed"
    assert first["toStatus"] == "completed"
    assert first["reason"] == "manual review — QA false-positive"
    assert first["overriddenByMemberId"] == (
        "00000000-0000-0000-0000-000000000001"
    )
    assert first["createdAt"].startswith("2026-05-01")


def test_override_audit_endpoint_mounted_on_admin_router():
    """Must be reachable through the real admin router, not just the
    isolated sub-router — a missing mount would ship a broken endpoint."""
    from ai_driven.api.admin.router import router as admin_router

    paths = {getattr(r, "path", "") for r in admin_router.routes}
    assert any(
        p.endswith("/override-audit") for p in paths
    ), f"Expected override-audit on admin router; got {paths!r}"
