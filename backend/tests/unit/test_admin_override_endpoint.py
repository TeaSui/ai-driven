"""T24 unit tests for POST /api/admin/tasks/{task_id}/override-block.

Exercises the full behavior matrix from the brief:
- blocked + rerun_coding        → status=queued, markers cleared, audit row
- qa_failed + rerun_qa          → status=awaiting_qa, markers cleared, audit
- qa_failed + force_pass        → status=completed, audit
- orchestrating + reap (stuck)  → status=failed, audit
- orchestrating + reap (fresh)  → 400
- non-admin                     → 403
- missing reason                → 422
- concurrent override (CAS)     → 409
- unknown task                  → 404

Tests use the mocked-session pattern established elsewhere in the unit
suite (test_admin_repos, test_task_repository_output_data). A fresh
FastAPI app is wired per-test with the override router and a fake
require_admin dep so we don't need real auth middleware or a real DB.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient


SYSTEM_MEMBER_UUID = uuid.UUID("00000000-0000-0000-0000-000000000001")


# ---------------------------------------------------------------------------
# Test harness
# ---------------------------------------------------------------------------


def _make_app(session_mock, *, admin=True):
    """Build a throwaway FastAPI app with override router + fake deps."""
    from ai_driven.api.admin.override import router
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
                member_id=str(SYSTEM_MEMBER_UUID),
                team_id="11111111-1111-1111-1111-111111111111",
                role="admin",
            )
        app.dependency_overrides[require_admin] = _fake_admin
    else:
        # Simulate 403 by making the dep raise.
        from fastapi import HTTPException

        def _fake_deny():
            raise HTTPException(status_code=403, detail="Admin role required")
        app.dependency_overrides[require_admin] = _fake_deny

    return TestClient(app)


def _make_task(
    *,
    status: str,
    output_data: dict | None = None,
    last_heartbeat_at: datetime | None = None,
    created_at: datetime | None = None,
) -> MagicMock:
    t = MagicMock()
    t.id = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    t.status = status
    t.output_data = output_data or {}
    t.last_heartbeat_at = last_heartbeat_at
    t.created_at = created_at or datetime.now(timezone.utc)
    t.input_data = {"payload": {"message": "do work"}}
    return t


def _session_for(task, *, cas_hit: bool = True):
    """Build a mocked AsyncSession that returns ``task`` from
    ``TaskRepository.get_by_id`` and a controllable UPDATE rowcount."""
    session = MagicMock()
    session.add = MagicMock()
    session.commit = AsyncMock()
    session.rollback = AsyncMock()
    session.flush = AsyncMock()
    session.get = AsyncMock(return_value=task)
    execute_result = MagicMock()
    execute_result.rowcount = 1 if cas_hit else 0
    session.execute = AsyncMock(return_value=execute_result)
    return session


# ---------------------------------------------------------------------------
# Positive cases
# ---------------------------------------------------------------------------


def test_blocked_rerun_coding_clears_markers_and_enqueues():
    task = _make_task(
        status="blocked",
        output_data={
            "orchestrator_completed_at": "2026-05-01T10:00:00+00:00",
            "orchestrator_halted_on": "classifier",
            "classifier_tier": "multi_domain",
            "blocked_by": "security",
            "blocking_reasons": ["missing input"],
            "blocker_artifact_id": "abc",
            "unrelated_key": "kept",
        },
    )
    session = _session_for(task)

    with patch("ai_driven.api.admin.override.TaskProducer") as ProducerCls:
        producer = MagicMock()
        producer.enqueue_task = AsyncMock(return_value="msg-1")
        ProducerCls.return_value = producer
        client = _make_app(session)
        resp = client.post(
            f"/api/admin/tasks/{task.id}/override-block",
            json={"action": "rerun_coding", "reason": "false positive block"},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["data"]["fromStatus"] == "blocked"
    assert body["data"]["toStatus"] == "queued"
    assert body["data"]["action"] == "rerun_coding"

    # The UPDATE call should have output_data with the clear-keys removed
    # but 'unrelated_key' preserved.
    update_call = session.execute.await_args_list[0]
    stmt = update_call.args[0]
    compiled = stmt.compile()
    params = dict(compiled.params)
    output = next(v for v in params.values() if isinstance(v, dict))
    assert "orchestrator_completed_at" not in output
    assert "classifier_tier" not in output
    assert "blocked_by" not in output
    assert "blocking_reasons" not in output
    assert "blocker_artifact_id" not in output
    assert "orchestrator_halted_on" not in output
    assert output["unrelated_key"] == "kept"

    # Audit row was added (session.add called with a TaskOverrideAudit).
    from ai_driven.db.postgres.models import TaskOverrideAudit
    added = [c.args[0] for c in session.add.call_args_list]
    assert any(isinstance(a, TaskOverrideAudit) for a in added)

    # Enqueue called with agent_type='coding'
    assert producer.enqueue_task.await_count == 1
    kwargs = producer.enqueue_task.await_args.kwargs
    assert kwargs["agent_type"] == "coding"


def test_qa_failed_rerun_qa_clears_markers_and_enqueues_qa():
    task = _make_task(
        status="qa_failed",
        output_data={
            "qa_completed_at": "2026-05-01T10:00:00+00:00",
            "qa_verdict": "block",
            "qa_artifact_id": "qa-1",
            "qa_enqueued_at": "2026-05-01T09:50:00+00:00",
            "qa_failures": ["no tests"],
            "pr_number": 42,  # not a QA marker — should survive
        },
    )
    session = _session_for(task)

    with patch("ai_driven.api.admin.override.TaskProducer") as ProducerCls:
        producer = MagicMock()
        producer.enqueue_task = AsyncMock(return_value="msg-1")
        ProducerCls.return_value = producer
        client = _make_app(session)
        resp = client.post(
            f"/api/admin/tasks/{task.id}/override-block",
            json={"action": "rerun_qa", "reason": "retry after missing deps"},
        )

    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["toStatus"] == "awaiting_qa"

    update_call = session.execute.await_args_list[0]
    stmt = update_call.args[0]
    params = dict(stmt.compile().params)
    output = next(v for v in params.values() if isinstance(v, dict))
    assert "qa_completed_at" not in output
    assert "qa_verdict" not in output
    assert "qa_artifact_id" not in output
    assert "qa_enqueued_at" not in output
    assert "qa_failures" not in output
    assert output["pr_number"] == 42

    kwargs = producer.enqueue_task.await_args.kwargs
    assert kwargs["agent_type"] == "qa"


def test_qa_failed_force_pass_marks_completed():
    task = _make_task(status="qa_failed", output_data={"qa_verdict": "block"})
    session = _session_for(task)

    client = _make_app(session)
    resp = client.post(
        f"/api/admin/tasks/{task.id}/override-block",
        json={"action": "force_pass", "reason": "accepted risk"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["toStatus"] == "completed"

    # output_data should have admin_force_pass=True flag
    stmt = session.execute.await_args_list[0].args[0]
    params = dict(stmt.compile().params)
    output = next(v for v in params.values() if isinstance(v, dict))
    assert output["admin_force_pass"] is True


def test_orchestrating_reap_stuck_marks_failed():
    # Stuck: started 31 min ago.
    started = (datetime.now(timezone.utc) - timedelta(minutes=31)).isoformat()
    task = _make_task(
        status="orchestrating",
        output_data={"started_at": started},
    )
    session = _session_for(task)

    client = _make_app(session)
    resp = client.post(
        f"/api/admin/tasks/{task.id}/override-block",
        json={"action": "reap", "reason": "orchestrator hung"},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["toStatus"] == "failed"

    stmt = session.execute.await_args_list[0].args[0]
    params = dict(stmt.compile().params)
    output = next(v for v in params.values() if isinstance(v, dict))
    assert output["failure_reason"] == "orchestrator_stuck_reaped"


# ---------------------------------------------------------------------------
# Negative cases
# ---------------------------------------------------------------------------


def test_orchestrating_reap_fresh_returns_400():
    # Fresh: started 5 min ago.
    started = (datetime.now(timezone.utc) - timedelta(minutes=5)).isoformat()
    task = _make_task(
        status="orchestrating",
        output_data={"started_at": started},
        created_at=datetime.now(timezone.utc) - timedelta(minutes=5),
    )
    session = _session_for(task)

    client = _make_app(session)
    resp = client.post(
        f"/api/admin/tasks/{task.id}/override-block",
        json={"action": "reap", "reason": "looks stuck"},
    )
    assert resp.status_code == 400
    assert "not stuck long enough" in resp.json()["detail"].lower()


def test_completed_override_rejected_400():
    task = _make_task(status="completed")
    session = _session_for(task)

    client = _make_app(session)
    resp = client.post(
        f"/api/admin/tasks/{task.id}/override-block",
        json={"action": "force_pass", "reason": "already done"},
    )
    assert resp.status_code == 400


def test_non_admin_returns_403():
    task = _make_task(status="blocked")
    session = _session_for(task)
    client = _make_app(session, admin=False)
    resp = client.post(
        f"/api/admin/tasks/{task.id}/override-block",
        json={"action": "rerun_coding", "reason": "x"},
    )
    assert resp.status_code == 403


def test_missing_reason_returns_422():
    task = _make_task(status="blocked")
    session = _session_for(task)
    client = _make_app(session)
    resp = client.post(
        f"/api/admin/tasks/{task.id}/override-block",
        json={"action": "rerun_coding", "reason": ""},
    )
    assert resp.status_code == 422


def test_task_not_found_returns_404():
    session = MagicMock()
    session.get = AsyncMock(return_value=None)
    session.add = MagicMock()
    session.commit = AsyncMock()
    session.execute = AsyncMock()

    client = _make_app(session)
    resp = client.post(
        "/api/admin/tasks/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/override-block",
        json={"action": "rerun_coding", "reason": "x"},
    )
    assert resp.status_code == 404


def test_concurrent_override_returns_409():
    """CAS miss: the UPDATE's WHERE status=:expected did not match any row."""
    task = _make_task(status="blocked")
    session = _session_for(task, cas_hit=False)

    with patch("ai_driven.api.admin.override.TaskProducer") as ProducerCls:
        ProducerCls.return_value = MagicMock(enqueue_task=AsyncMock())
        client = _make_app(session)
        resp = client.post(
            f"/api/admin/tasks/{task.id}/override-block",
            json={"action": "rerun_coding", "reason": "retry"},
        )
    assert resp.status_code == 409
    # Audit row must NOT be written on CAS miss.
    from ai_driven.db.postgres.models import TaskOverrideAudit
    added = [c.args[0] for c in session.add.call_args_list]
    assert not any(isinstance(a, TaskOverrideAudit) for a in added)


def test_awaiting_qa_reap_stuck_marks_failed():
    enq = (datetime.now(timezone.utc) - timedelta(minutes=45)).isoformat()
    task = _make_task(
        status="awaiting_qa",
        output_data={"qa_enqueued_at": enq},
    )
    session = _session_for(task)
    client = _make_app(session)
    resp = client.post(
        f"/api/admin/tasks/{task.id}/override-block",
        json={"action": "reap", "reason": "qa silent"},
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["toStatus"] == "failed"
    stmt = session.execute.await_args_list[0].args[0]
    params = dict(stmt.compile().params)
    output = next(v for v in params.values() if isinstance(v, dict))
    assert output["failure_reason"] == "qa_stuck_reaped"
