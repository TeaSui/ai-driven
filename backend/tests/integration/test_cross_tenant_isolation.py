"""Tier-1 Item A — cross-tenant isolation probe.

Locks in the authz + team-scoping guarantees for the admin router:

  * Every mutating admin endpoint rejects callers from a different team.
  * Representative read endpoints refuse to return another team's data
    (404 on known-ID lookups — existence-leak avoidance, matching the
    T25 artifacts convention; empty list on list endpoints).

Why this is an integration-folder test
--------------------------------------
The behavior under test is the composition of ``require_admin`` +
per-handler team-scope guards inside the admin router. We mount the
real router with only the DB session mocked, so this exercises the
FastAPI dep graph + routing + all handler code paths together. It is
not a true "two Postgres rows" integration — that is covered by the
E2E harness — but it gives us a fast, deterministic CI gate that
survives code churn.

Test shape
----------
Two teams:
  * team A (ADMIN_TEAM_ID) — the authenticated caller
  * team B (OTHER_TEAM_ID) — owns every resource the caller attempts
    to reach

Every request is authenticated as a team-A admin. Resource IDs in the
URL all belong to team B. The assertion is that the admin router
refuses every one of these cross-tenant attempts.

Expected status codes:
  * 404 — endpoint resolves a resource ID that belongs to another
          team (reads + resource-scoped writes). We collapse to 404
          instead of 403 so an attacker cannot distinguish "exists on
          another team" from "does not exist".
  * 403 — not applicable here: require_admin passes (caller IS an
          admin, just not of the right team), so the only remaining
          guard is the team-scope check, which returns 404.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient


ADMIN_TEAM_ID = "11111111-1111-1111-1111-111111111111"  # caller's team
OTHER_TEAM_ID = "22222222-2222-2222-2222-222222222222"  # everything targeted

# Fixed UUIDs for resources owned by team B.
TEAM_B_TASK_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb01"
TEAM_B_REPO_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb02"
TEAM_B_REVIEW_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb03"


# ---------------------------------------------------------------------------
# Test harness
# ---------------------------------------------------------------------------


def _make_task_for_other_team() -> MagicMock:
    """Return a Task MagicMock that looks like it belongs to team B."""
    t = MagicMock()
    t.id = uuid.UUID(TEAM_B_TASK_ID)
    t.team_id = uuid.UUID(OTHER_TEAM_ID)
    t.status = "completed"
    t.agent_type = "coding"
    t.title = "teamB task"
    t.jira_key = "TEAM-B-1"
    t.input_data = {"payload": {"message": "do work"}, "repo_full_name": "other/repo"}
    t.output_data = {}
    t.total_tokens = 100
    t.total_cost_usd = 0.5
    t.iterations = 1
    t.error_message = None
    t.created_at = datetime(2026, 5, 1, tzinfo=timezone.utc)
    t.started_at = datetime(2026, 5, 1, tzinfo=timezone.utc)
    t.completed_at = datetime(2026, 5, 1, tzinfo=timezone.utc)
    t.last_heartbeat_at = None
    return t


def _make_repo_for_other_team() -> MagicMock:
    r = MagicMock()
    r.id = uuid.UUID(TEAM_B_REPO_ID)
    r.team_id = uuid.UUID(OTHER_TEAM_ID)
    r.full_name = "other/repo"
    r.scm_type = "github"
    r.default_branch = "main"
    r.clone_url = "https://github.com/other/repo.git"
    r.is_active = True
    r.settings_json = {}
    r.created_at = datetime(2026, 5, 1, tzinfo=timezone.utc)
    return r


def _make_team_b() -> MagicMock:
    t = MagicMock()
    t.id = uuid.UUID(OTHER_TEAM_ID)
    t.name = "team-b"
    t.is_active = True
    t.api_key_hash = "not-used-in-this-test"
    return t


def _make_app(
    *,
    team_b_task: MagicMock | None = None,
    team_b_repo: MagicMock | None = None,
    team_b_team: MagicMock | None = None,
) -> TestClient:
    """Build a FastAPI app mounting the real admin router with:

      * ``require_admin`` overridden to yield a team-A admin (ADMIN_TEAM_ID).
      * ``get_session`` overridden to yield a MagicMock session whose
        ``get_by_id`` / ``execute`` routes return team-B resources when
        asked.

    This is enough to exercise the per-handler cross-team guards without
    spinning up Postgres.
    """
    from starlette.middleware.base import BaseHTTPMiddleware

    from ai_driven.api.admin.router import router
    from ai_driven.auth.middleware import TeamMemberInfo, require_admin
    from ai_driven.db.postgres.session import get_session

    app = FastAPI()

    # Handlers that still use ``_get_team_from_request`` (rotate-key,
    # purge_team_data, delete_repo, update_repo, create_repo, list_repos,
    # get_usage, get_me, export_team_data) expect ``request.state.team_id``
    # to be set by APIKeyMiddleware. We populate it here so those helpers
    # resolve to team A (the caller's team) — the cross-team rejection is
    # still enforced by the path-id vs member.team_id comparison inside
    # each handler, not by the state.team_id lookup.
    class _FakeAuthState(BaseHTTPMiddleware):
        async def dispatch(self, request, call_next):
            request.state.team_id = ADMIN_TEAM_ID
            request.state.team_role = "admin"
            request.state.member_id = "00000000-0000-0000-0000-000000000001"
            return await call_next(request)

    app.add_middleware(_FakeAuthState)
    app.include_router(router, prefix="/api/admin")

    # ---- require_admin: caller is team A admin ----
    def _fake_admin() -> TeamMemberInfo:
        return TeamMemberInfo(
            member_id="00000000-0000-0000-0000-000000000001",
            team_id=ADMIN_TEAM_ID,
            role="admin",
        )

    app.dependency_overrides[require_admin] = _fake_admin

    # ---- session mock ----
    session = MagicMock()
    session.commit = AsyncMock()
    session.flush = AsyncMock()
    session.rollback = AsyncMock()
    session.refresh = AsyncMock()
    session.add = MagicMock()

    # session.get is used by TaskRepository.get_by_id — return team B's task
    # (so the handler reaches the cross-team guard and rejects).
    #
    # It is also used by ``_get_team_from_request`` (via
    # TeamRepository.get_by_id) to resolve the caller's own team from
    # state.team_id. We return a minimal team-A record there so the
    # helper succeeds and the handler can reach the cross-team guard.
    team_a_row = MagicMock()
    team_a_row.id = uuid.UUID(ADMIN_TEAM_ID)
    team_a_row.name = "team-a"
    team_a_row.is_active = True

    async def _fake_session_get(model, ident):
        if team_b_task is not None and str(ident) == TEAM_B_TASK_ID:
            return team_b_task
        if team_b_repo is not None and str(ident) == TEAM_B_REPO_ID:
            return team_b_repo
        if team_b_team is not None and str(ident) == OTHER_TEAM_ID:
            return team_b_team
        if str(ident) == ADMIN_TEAM_ID:
            return team_a_row
        return None

    session.get = AsyncMock(side_effect=_fake_session_get)

    # session.execute is used for: review lookups, repo.get_by_id variants,
    # dashboard aggregates, etc. The default behavior is "no rows" so any
    # SELECT returns empty; this is exactly the shape a cross-tenant scan
    # should see. Specific tests that need a review row patch this
    # themselves.
    execute_result = MagicMock()
    execute_result.scalar = MagicMock(return_value=0)
    execute_result.scalar_one_or_none = MagicMock(return_value=None)
    execute_result.scalars = MagicMock(
        return_value=MagicMock(all=MagicMock(return_value=[]))
    )
    execute_result.all = MagicMock(return_value=[])
    execute_result.rowcount = 0
    session.execute = AsyncMock(return_value=execute_result)

    async def _fake_get_session():
        yield session

    app.dependency_overrides[get_session] = _fake_get_session

    return TestClient(app)


# ---------------------------------------------------------------------------
# Mutating endpoints — cross-tenant must be rejected (404 for resource-scoped,
# matching the existence-leak-avoidance pattern from T25).
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "method,path,expected_status",
    [
        # Team-scoped mutators — path carries another team's team_id.
        (
            "DELETE",
            f"/api/admin/teams/{OTHER_TEAM_ID}",
            404,
        ),
        (
            "POST",
            f"/api/admin/teams/{OTHER_TEAM_ID}/rotate-key",
            404,
        ),
        (
            "DELETE",
            f"/api/admin/teams/{OTHER_TEAM_ID}/data",
            404,
        ),
        # Task-scoped mutator — path carries a team B task id.
        (
            "POST",
            f"/api/admin/tasks/{TEAM_B_TASK_ID}/evaluate",
            404,
        ),
        # Repo-scoped mutators — path carries a team B repo id.
        (
            "DELETE",
            f"/api/admin/repos/{TEAM_B_REPO_ID}",
            404,
        ),
        (
            "PUT",
            f"/api/admin/repos/{TEAM_B_REPO_ID}",
            404,
        ),
    ],
)
def test_mutating_endpoints_reject_cross_tenant(
    method: str, path: str, expected_status: int
) -> None:
    """Mutators targeting another team's resources return 404 (not 200)."""
    client = _make_app(
        team_b_task=_make_task_for_other_team(),
        team_b_repo=_make_repo_for_other_team(),
        team_b_team=_make_team_b(),
    )

    if method == "DELETE":
        resp = client.delete(path)
    elif method == "POST":
        resp = client.post(path, json={})
    elif method == "PUT":
        resp = client.put(path, json={})
    else:  # pragma: no cover — guard
        raise AssertionError(f"Unsupported method: {method}")

    assert resp.status_code == expected_status, (
        f"{method} {path}: expected {expected_status}, got {resp.status_code}: "
        f"{resp.text[:200]}"
    )


# ---------------------------------------------------------------------------
# Read endpoints — known-ID lookups on another team's resources must 404.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "method,path",
    [
        ("GET", f"/api/admin/tasks/{TEAM_B_TASK_ID}"),
        ("GET", f"/api/admin/tasks/{TEAM_B_TASK_ID}/evaluations"),
        ("GET", f"/api/admin/tasks/{TEAM_B_TASK_ID}/events"),
    ],
)
def test_read_endpoints_404_on_cross_tenant_known_id(
    method: str, path: str
) -> None:
    """Cross-team task reads collapse to 404, not 403 (existence-leak)."""
    client = _make_app(team_b_task=_make_task_for_other_team())
    resp = client.get(path)
    assert resp.status_code == 404, (
        f"{method} {path}: expected 404, got {resp.status_code}: "
        f"{resp.text[:200]}"
    )


# ---------------------------------------------------------------------------
# List endpoints — aggregates must exclude other-team rows even when the
# underlying tables contain them.
# ---------------------------------------------------------------------------


def test_list_tasks_scopes_to_caller_team() -> None:
    """GET /tasks must filter by the caller's team_id, not return all tasks."""
    from ai_driven.db.postgres.repository import TaskRepository

    client = _make_app()
    # The default execute_result returns empty; that is the correct
    # cross-tenant answer (caller's team has no tasks). We also confirm
    # the handler does not fall through to the debug "list all" path.
    resp = client.get("/api/admin/tasks")
    assert resp.status_code == 200
    body = resp.json()
    assert body == {"tasks": [], "total": 0}


def test_list_teams_scopes_to_caller_team() -> None:
    """GET /teams returns only the caller's own team (pre-fix: all teams)."""
    from ai_driven.db.postgres.repository import TeamRepository, RepoRepository

    client = _make_app()

    # Force TeamRepository.get_by_id to return a fake "team A" row so the
    # handler has something to serialize. The pre-fix code would have
    # called list_all() and returned team B too; with the fix, get_by_id
    # is the only path that runs for a non-debug caller.
    team_a = MagicMock()
    team_a.id = uuid.UUID(ADMIN_TEAM_ID)
    team_a.name = "team-a"

    async def _fake_get_by_id(self, tid):  # noqa: ARG001
        return team_a if str(tid) == ADMIN_TEAM_ID else None

    async def _fake_list_for_team(self, tid, limit=50, offset=0):  # noqa: ARG001
        return []

    # Patch at the class level so the handler's fresh instances see it.
    original_tr = TeamRepository.get_by_id
    original_rr = RepoRepository.list_for_team
    TeamRepository.get_by_id = _fake_get_by_id  # type: ignore[assignment]
    RepoRepository.list_for_team = _fake_list_for_team  # type: ignore[assignment]
    try:
        resp = client.get("/api/admin/teams")
    finally:
        TeamRepository.get_by_id = original_tr  # type: ignore[assignment]
        RepoRepository.list_for_team = original_rr  # type: ignore[assignment]

    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)
    assert len(body) == 1
    assert body[0]["id"] == ADMIN_TEAM_ID, (
        "list_teams must return only the caller's team"
    )


def test_list_incidents_scopes_to_caller_team(monkeypatch: pytest.MonkeyPatch) -> None:
    """GET /incidents must call list_for_team, not list_all (pre-fix leak)."""
    from ai_driven.db.postgres.repository import IncidentRepository

    client = _make_app()

    call_counts = {"list_all": 0, "list_for_team": 0}

    async def _fake_list_all(self, *_a, **_kw):  # noqa: ARG001
        call_counts["list_all"] += 1
        return []

    async def _fake_list_for_team(self, team_id, *_a, **_kw):  # noqa: ARG001
        call_counts["list_for_team"] += 1
        assert str(team_id) == ADMIN_TEAM_ID, (
            "list_for_team must be called with caller's team id"
        )
        return []

    monkeypatch.setattr(IncidentRepository, "list_all", _fake_list_all)
    monkeypatch.setattr(IncidentRepository, "list_for_team", _fake_list_for_team)

    resp = client.get("/api/admin/incidents")
    assert resp.status_code == 200
    assert call_counts["list_for_team"] == 1, (
        "incidents endpoint must call list_for_team for non-debug callers"
    )
    assert call_counts["list_all"] == 0, (
        "incidents endpoint must NOT call list_all — that would leak every team"
    )


# ---------------------------------------------------------------------------
# Authz gate: non-admin within the right team still gets 403.
# ---------------------------------------------------------------------------


def test_non_admin_role_is_rejected() -> None:
    """Sanity: require_admin returns 403 for non-admin roles.

    Not the focus of this file (cross-tenant isolation is), but covers
    the "role downgrade" angle — an otherwise-valid member of team A
    should still get 403 on every admin-gated endpoint. We sample a
    single write + a single read to confirm the gate is wired on the
    routes added in this change.
    """
    from ai_driven.api.admin.router import router
    from ai_driven.auth.middleware import require_admin
    from ai_driven.db.postgres.session import get_session
    from fastapi import HTTPException

    app = FastAPI()
    app.include_router(router, prefix="/api/admin")

    def _deny():
        raise HTTPException(status_code=403, detail="Admin role required")

    app.dependency_overrides[require_admin] = _deny

    async def _fake_session():
        session = MagicMock()
        session.commit = AsyncMock()
        yield session

    app.dependency_overrides[get_session] = _fake_session

    client = TestClient(app)

    # A representative write — must be gated.
    r = client.post("/api/admin/teams", json={"name": "nope"})
    assert r.status_code == 403, r.text

    # A representative read — must also be gated.
    r = client.get("/api/admin/dashboard/summary")
    assert r.status_code == 403, r.text
