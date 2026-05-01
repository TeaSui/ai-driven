"""Unit tests for T22's _QaPostCodingWrapper.

Covers (per T22 brief section 10):
  * happy path: pr_number present → QA runs, artifact persisted, markers
    written via shared session, status NOT flipped (T23's job).
  * missing pr_number → qa.post_coding.missing_pr_number counter emitted,
    error artifact persisted so T24 reaper can clear it.
  * idempotent replay: output_data.qa_completed_at already set →
    short-circuit, no specialist call.
  * schema drift: specialist returns payload without schema_version="v2"
    → wrapper forces verdict=error and emits qa.post_coding.schema_drift.
  * get_pr_diff 404: the QA specialist path is unit-scoped — we mock
    `run_specialist` to return verdict=error and verify lenient
    pass-through.
"""
from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.queue import consumer as consumer_mod
from ai_driven.queue.consumer import _QaPostCodingWrapper


def _mk_task_row(
    output_data: dict | None = None,
    status: str = "awaiting_qa",
) -> MagicMock:
    row = MagicMock()
    row.output_data = output_data or {}
    row.status = status
    return row


def _v2_ok_payload() -> dict:
    return {
        "schema_version": "v2",
        "checks_performed": [],
        "acceptance_criteria_verification": [
            {"criterion": "AC1", "satisfied": True,
             "evidence": "src/foo.py:10", "reason": "ok"},
        ],
        "security_rules_verification": [
            {"rule": "argon2id", "followed": True,
             "evidence": "src/auth.py:5", "severity": "high"},
        ],
        "test_coverage_gap": [],
        "regression_risk": "low",
    }


@pytest.mark.asyncio
async def test_happy_path_persists_artifact_and_markers():
    """pr_number present → run QA, shared-session persist markers."""
    task_id = uuid.uuid4()
    payload = {
        "task_id": str(task_id),
        "ticket": {"summary": "s", "description": "d",
                   "acceptance_criteria": "", "labels": []},
        "repo": {"full_name": "org/repo", "scm_type": "github",
                 "default_branch": "main"},
    }
    existing_output = {
        "pr_number": 42,
        "pr_url": "https://github.com/org/repo/pull/42",
        "classifier_tier": "full_workflow",
    }

    persisted: dict = {}

    async def fake_persist(task_uuid, artifact, stamped_mode="shadow"):
        persisted["task_uuid"] = task_uuid
        persisted["artifact"] = artifact

    qa_art = Artifact(
        role="qa", verdict="pass",
        reasoning="All ACs satisfied.",
        payload=_v2_ok_payload(),
        model_used="claude-sonnet",
        input_tokens=100, output_tokens=50,
    )

    with patch.object(consumer_mod, "_persist_qa_artifact_and_marker",
                       side_effect=fake_persist) as p, \
         patch("ai_driven.observability.metrics.emit_counter") as emit, \
         patch.object(consumer_mod, "async_session_factory") as sess_ctx, \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(return_value=qa_art)) as _run, \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository.list_for_task",
               new=AsyncMock(return_value={})):
        # Stub async_session_factory context manager to return a fake
        # session whose TaskRepository.get_by_id returns our row.
        fake_session = AsyncMock()
        sess_ctx.return_value.__aenter__.return_value = fake_session
        sess_ctx.return_value.__aexit__.return_value = False
        with patch("ai_driven.queue.consumer.TaskRepository") as task_repo_cls:
            task_repo_cls.return_value.get_by_id = AsyncMock(
                return_value=_mk_task_row(existing_output)
            )
            wrapper = _QaPostCodingWrapper(scm_client=MagicMock())
            result = await wrapper.run(payload)

    assert result.success is True
    assert persisted["task_uuid"] == task_id
    assert persisted["artifact"].verdict == "pass"
    assert persisted["artifact"].payload["schema_version"] == "v2"
    # Verdict counter was emitted.
    emit.assert_any_call("qa.post_coding.verdict.pass")


@pytest.mark.asyncio
async def test_missing_pr_number_emits_sev1_and_persists_error_artifact():
    task_id = uuid.uuid4()
    payload = {"task_id": str(task_id)}
    existing_output = {"classifier_tier": "full_workflow"}  # no pr_number

    persisted: dict = {}

    async def fake_persist(task_uuid, artifact, stamped_mode="shadow"):
        persisted["artifact"] = artifact

    with patch.object(consumer_mod, "_persist_qa_artifact_and_marker",
                       side_effect=fake_persist), \
         patch("ai_driven.observability.metrics.emit_counter") as emit, \
         patch.object(consumer_mod, "async_session_factory") as sess_ctx:
        fake_session = AsyncMock()
        sess_ctx.return_value.__aenter__.return_value = fake_session
        sess_ctx.return_value.__aexit__.return_value = False
        with patch("ai_driven.queue.consumer.TaskRepository") as task_repo_cls:
            task_repo_cls.return_value.get_by_id = AsyncMock(
                return_value=_mk_task_row(existing_output)
            )
            wrapper = _QaPostCodingWrapper()
            result = await wrapper.run(payload)

    assert result.success is True
    emit.assert_any_call("qa.post_coding.missing_pr_number")
    assert persisted["artifact"].verdict == "error"
    assert persisted["artifact"].payload["schema_version"] == "v2"


@pytest.mark.asyncio
async def test_idempotent_replay_short_circuits():
    task_id = uuid.uuid4()
    payload = {"task_id": str(task_id)}
    # qa_completed_at already set → short-circuit.
    existing_output = {
        "pr_number": 42,
        "qa_completed_at": "2026-05-01T00:00:00Z",
        "qa_verdict": "pass",
    }

    specialist_calls = []

    with patch.object(consumer_mod, "_persist_qa_artifact_and_marker",
                       new=AsyncMock()) as persist, \
         patch.object(consumer_mod, "async_session_factory") as sess_ctx, \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(side_effect=lambda ctx: specialist_calls.append(ctx))):
        fake_session = AsyncMock()
        sess_ctx.return_value.__aenter__.return_value = fake_session
        sess_ctx.return_value.__aexit__.return_value = False
        with patch("ai_driven.queue.consumer.TaskRepository") as task_repo_cls:
            task_repo_cls.return_value.get_by_id = AsyncMock(
                return_value=_mk_task_row(existing_output)
            )
            wrapper = _QaPostCodingWrapper()
            result = await wrapper.run(payload)

    assert result.success is True
    assert specialist_calls == [], (
        "Specialist must not run on an idempotent replay."
    )
    persist.assert_not_called()


@pytest.mark.asyncio
async def test_schema_drift_forces_error_and_emits_counter():
    """Specialist returned v1-shape payload → wrapper forces verdict=error."""
    task_id = uuid.uuid4()
    payload = {"task_id": str(task_id)}
    existing_output = {"pr_number": 42, "classifier_tier": "full_workflow"}

    # Specialist returns a v1 payload (wrong schema_version for post-coding).
    v1_art = Artifact(
        role="qa", verdict="pass",
        reasoning="looks good",
        payload={"schema_version": "v1", "checks_performed": [],
                 "test_coverage_gap": [], "regression_risk": "low"},
        model_used="m", input_tokens=10, output_tokens=5,
    )

    captured: dict = {}

    async def fake_persist(task_uuid, artifact, stamped_mode="shadow"):
        captured["artifact"] = artifact

    with patch.object(consumer_mod, "_persist_qa_artifact_and_marker",
                       side_effect=fake_persist), \
         patch("ai_driven.observability.metrics.emit_counter") as emit, \
         patch.object(consumer_mod, "async_session_factory") as sess_ctx, \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(return_value=v1_art)), \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository.list_for_task",
               new=AsyncMock(return_value={})):
        fake_session = AsyncMock()
        sess_ctx.return_value.__aenter__.return_value = fake_session
        sess_ctx.return_value.__aexit__.return_value = False
        with patch("ai_driven.queue.consumer.TaskRepository") as task_repo_cls:
            task_repo_cls.return_value.get_by_id = AsyncMock(
                return_value=_mk_task_row(existing_output)
            )
            wrapper = _QaPostCodingWrapper()
            await wrapper.run(payload)

    assert captured["artifact"].verdict == "error"
    assert "schema_version drift" in captured["artifact"].reasoning
    emit.assert_any_call("qa.post_coding.schema_drift")
    # Verdict counter still fires, but now with the forced 'error' tag.
    emit.assert_any_call("qa.post_coding.verdict.error")


@pytest.mark.asyncio
async def test_specialist_raises_returns_error_artifact_not_crash():
    """Lenient pass-through: if run_specialist raises, wrapper persists
    error artifact and completes successfully (SQS will delete the
    message)."""
    task_id = uuid.uuid4()
    payload = {"task_id": str(task_id)}
    existing_output = {"pr_number": 42, "classifier_tier": "full_workflow"}

    captured: dict = {}

    async def fake_persist(task_uuid, artifact, stamped_mode="shadow"):
        captured["artifact"] = artifact

    with patch.object(consumer_mod, "_persist_qa_artifact_and_marker",
                       side_effect=fake_persist), \
         patch("ai_driven.observability.metrics.emit_counter"), \
         patch.object(consumer_mod, "async_session_factory") as sess_ctx, \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(side_effect=RuntimeError("bedrock down"))), \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository.list_for_task",
               new=AsyncMock(return_value={})):
        fake_session = AsyncMock()
        sess_ctx.return_value.__aenter__.return_value = fake_session
        sess_ctx.return_value.__aexit__.return_value = False
        with patch("ai_driven.queue.consumer.TaskRepository") as task_repo_cls:
            task_repo_cls.return_value.get_by_id = AsyncMock(
                return_value=_mk_task_row(existing_output)
            )
            wrapper = _QaPostCodingWrapper()
            result = await wrapper.run(payload)

    assert result.success is True
    assert captured["artifact"].verdict == "error"
    assert "bedrock down" in captured["artifact"].reasoning


@pytest.mark.asyncio
async def test_missing_task_id_emits_counter_and_noops():
    wrapper = _QaPostCodingWrapper()
    with patch("ai_driven.observability.metrics.emit_counter") as emit:
        result = await wrapper.run({})  # no task_id
    assert result.success is True
    emit.assert_called_with("qa.post_coding.missing_task_id")
