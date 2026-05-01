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


# ---------------------------------------------------------------------------
# T23 gate-integration tests: exercise the REAL _persist_qa_artifact_and_marker
# (not the fake_persist stub) so decide_qa_gate -> status transition is
# covered end-to-end at the DB boundary.
# ---------------------------------------------------------------------------


class _FakeTask:
    """In-memory task row mirroring the shape TaskRepository returns."""

    def __init__(self, status: str = "awaiting_qa",
                 output_data: dict | None = None) -> None:
        self.status = status
        self.output_data = output_data or {}


class _FakeTaskRepo:
    def __init__(self, task: _FakeTask) -> None:
        self._task = task

    async def get_by_id(self, _task_id):
        return self._task

    async def update_output_data(self, _task_id, patch):
        self._task.output_data = {**(self._task.output_data or {}), **patch}

    async def update_status(self, _task_id, status, **_):
        self._task.status = status


class _FakeArtifactRepo:
    """Tracks upserts + serves ``list_for_task`` for the B1 replay test."""

    def __init__(self, _session, preloaded: dict | None = None) -> None:
        self.upserts: list = []
        self._preloaded = preloaded or {}

    async def upsert(self, task_id, artifact):
        self.upserts.append((task_id, artifact))

    async def list_for_task(self, _task_id):
        return dict(self._preloaded)


class _FakeSession:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None

    async def commit(self):
        return None

    async def execute(self, *_a, **_k):
        # select(TaskArtifact.id) -> scalar_one_or_none() returning None
        # means the qa_artifact_id stamp falls through to None. That's
        # fine for a wrapper-scope test; the integration-scope tests in
        # tests/integration/ validate the fuller write path.
        res = MagicMock()
        res.scalar_one_or_none = MagicMock(return_value=None)
        return res


def _run_kwargs(task_id: uuid.UUID, mode: str) -> dict:
    return {"task_id": str(task_id), "orchestrator_mode": mode}


def _payload(task_id: uuid.UUID) -> dict:
    return {
        "task_id": str(task_id),
        "ticket": {"summary": "s", "description": "d",
                   "acceptance_criteria": "", "labels": []},
        "repo": {"full_name": "org/repo", "scm_type": "github",
                 "default_branch": "main"},
    }


def _v2_failing_payload() -> dict:
    return {
        "schema_version": "v2",
        "checks_performed": ["ac"],
        "acceptance_criteria_verification": [
            {"criterion": "AC1", "satisfied": False,
             "reason": "regex gap", "evidence": "src/auth.py:42"},
        ],
        "security_rules_verification": [],
        "test_coverage_gap": [],
        "regression_risk": "medium",
    }


@pytest.mark.asyncio
async def test_live_block_transitions_to_qa_failed_in_db():
    """live mode + block verdict → status flips awaiting_qa → qa_failed.

    Exercises the REAL _persist_qa_artifact_and_marker so decide_qa_gate
    runs inside the session. Asserts qa_failures is written to
    output_data and the counter is emitted post-commit.
    """
    task_id = uuid.uuid4()
    fake_task = _FakeTask(
        status="awaiting_qa",
        output_data={"pr_number": 42, "classifier_tier": "full_workflow"},
    )
    fake_repo = _FakeTaskRepo(fake_task)

    block_art = Artifact(
        role="qa", verdict="block",
        reasoning="AC1 regex gap.",
        payload=_v2_failing_payload(),
        model_used="claude", input_tokens=100, output_tokens=50,
    )

    with patch.object(consumer_mod, "async_session_factory",
                       lambda: _FakeSession()), \
         patch.object(consumer_mod, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository",
               side_effect=_FakeArtifactRepo), \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(return_value=block_art)), \
         patch("ai_driven.observability.metrics.emit_counter") as emit:
        wrapper = _QaPostCodingWrapper(scm_client=MagicMock())
        result = await wrapper.run(
            _payload(task_id), **_run_kwargs(task_id, "live"),
        )

    assert result.success is True
    assert fake_task.status == "qa_failed", (
        f"expected qa_failed, got {fake_task.status!r}"
    )
    assert fake_task.output_data["qa_verdict"] == "block"
    # qa_failures persisted for UI (T25) and T24 reaper.
    assert len(fake_task.output_data.get("qa_failures", [])) == 1
    assert fake_task.output_data["qa_failures"][0]["kind"] == "ac_unsatisfied"
    # Block counter emitted.
    emit.assert_any_call("qa.gate.blocked")


@pytest.mark.asyncio
async def test_live_pass_transitions_to_completed_in_db():
    """live mode + pass verdict → status flips awaiting_qa → completed."""
    task_id = uuid.uuid4()
    fake_task = _FakeTask(
        status="awaiting_qa",
        output_data={"pr_number": 42, "classifier_tier": "full_workflow"},
    )
    fake_repo = _FakeTaskRepo(fake_task)

    pass_art = Artifact(
        role="qa", verdict="pass",
        reasoning="all good",
        payload=_v2_ok_payload(),
        model_used="claude", input_tokens=100, output_tokens=40,
    )

    with patch.object(consumer_mod, "async_session_factory",
                       lambda: _FakeSession()), \
         patch.object(consumer_mod, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository",
               side_effect=_FakeArtifactRepo), \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(return_value=pass_art)), \
         patch("ai_driven.observability.metrics.emit_counter") as emit:
        wrapper = _QaPostCodingWrapper(scm_client=MagicMock())
        result = await wrapper.run(
            _payload(task_id), **_run_kwargs(task_id, "live"),
        )

    assert result.success is True
    assert fake_task.status == "completed"
    assert fake_task.output_data["qa_verdict"] == "pass"
    # No failures on a pass verdict.
    assert "qa_failures" not in fake_task.output_data
    emit.assert_any_call("qa.gate.pass")


@pytest.mark.asyncio
async def test_shadow_block_leaves_status_untouched_but_writes_failures():
    """shadow mode + block → status stays at completed (T22 path)
    but qa_failures is still populated for observability (T25 UI)."""
    task_id = uuid.uuid4()
    # In shadow mode T22 leaves status=completed immediately after
    # coding; awaiting_qa is never set.
    fake_task = _FakeTask(
        status="completed",
        output_data={"pr_number": 42, "classifier_tier": "full_workflow"},
    )
    fake_repo = _FakeTaskRepo(fake_task)

    block_art = Artifact(
        role="qa", verdict="block",
        reasoning="gap",
        payload=_v2_failing_payload(),
        model_used="claude", input_tokens=100, output_tokens=50,
    )

    with patch.object(consumer_mod, "async_session_factory",
                       lambda: _FakeSession()), \
         patch.object(consumer_mod, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository",
               side_effect=_FakeArtifactRepo), \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(return_value=block_art)), \
         patch("ai_driven.observability.metrics.emit_counter") as emit:
        wrapper = _QaPostCodingWrapper(scm_client=MagicMock())
        await wrapper.run(
            _payload(task_id), **_run_kwargs(task_id, "shadow"),
        )

    # Status untouched.
    assert fake_task.status == "completed"
    # qa_failures populated for observability.
    assert len(fake_task.output_data.get("qa_failures", [])) == 1
    # Shadow-block counter emitted, NOT live-block.
    emit.assert_any_call("qa.gate.shadow.block")
    for call in emit.call_args_list:
        assert call.args[0] != "qa.gate.blocked", (
            "qa.gate.blocked counter leaked in shadow mode — a shadow "
            "verdict must only emit qa.gate.shadow.* counters."
        )


@pytest.mark.asyncio
async def test_b1_replay_recovers_stranded_awaiting_qa():
    """B1 red-team fix: prior delivery crashed AFTER artifact+marker
    commit but BEFORE status transition. Redelivery must re-apply the
    gate from the stored verdict + stored artifact without re-running
    the Claude call."""
    task_id = uuid.uuid4()
    # Task is stranded: qa_completed_at present, qa_verdict stored,
    # but status still awaiting_qa.
    fake_task = _FakeTask(
        status="awaiting_qa",
        output_data={
            "pr_number": 42,
            "qa_completed_at": "2026-05-01T00:00:00Z",
            "qa_verdict": "block",
        },
    )
    fake_repo = _FakeTaskRepo(fake_task)

    # Prior artifact — the one the crashed delivery already persisted.
    prior_qa_art = Artifact(
        role="qa", verdict="block",
        reasoning="stranded",
        payload=_v2_failing_payload(),
        model_used="claude", input_tokens=100, output_tokens=50,
    )

    specialist_ran = False

    async def specialist_stub(*_a, **_k):
        nonlocal specialist_ran
        specialist_ran = True

    # patch ArtifactRepository.list_for_task to return the prior QA art.
    with patch.object(consumer_mod, "async_session_factory",
                       lambda: _FakeSession()), \
         patch.object(consumer_mod, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository.list_for_task",
               new=AsyncMock(return_value={"qa": prior_qa_art})), \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=specialist_stub), \
         patch("ai_driven.observability.metrics.emit_counter") as emit:
        wrapper = _QaPostCodingWrapper()
        result = await wrapper.run(
            {"task_id": str(task_id)},
            **_run_kwargs(task_id, "live"),
        )

    assert result.success is True
    assert specialist_ran is False, (
        "B1 replay must NOT re-run the specialist — pure DB work."
    )
    # Gate reapplied from stored verdict=block → status becomes qa_failed.
    assert fake_task.status == "qa_failed"
    emit.assert_any_call("qa.gate.replay_recovered")


@pytest.mark.asyncio
async def test_m3_compare_and_set_blocked_row_emits_mismatch_counter():
    """M3 red-team fix: if current status is NOT awaiting_qa (e.g., an
    operator already manually blocked the row), the qa_failed
    transition must NOT fire; instead qa.gate.status_mismatch is
    emitted and the row is left for T24 reaper / admin override."""
    task_id = uuid.uuid4()
    # Pre-condition: operator already set status to "blocked" while QA
    # was running.
    fake_task = _FakeTask(
        status="blocked",
        output_data={"pr_number": 42, "classifier_tier": "full_workflow"},
    )
    fake_repo = _FakeTaskRepo(fake_task)

    block_art = Artifact(
        role="qa", verdict="block",
        reasoning="gap",
        payload=_v2_failing_payload(),
        model_used="claude", input_tokens=100, output_tokens=50,
    )

    with patch.object(consumer_mod, "async_session_factory",
                       lambda: _FakeSession()), \
         patch.object(consumer_mod, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository",
               side_effect=_FakeArtifactRepo), \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(return_value=block_art)), \
         patch("ai_driven.observability.metrics.emit_counter") as emit:
        wrapper = _QaPostCodingWrapper(scm_client=MagicMock())
        await wrapper.run(
            _payload(task_id), **_run_kwargs(task_id, "live"),
        )

    # Status UNCHANGED — operator's "blocked" wins over the gate.
    assert fake_task.status == "blocked"
    # Mismatch counter fired with the current status tag so ops can
    # triage / T24 reaper can classify.
    mismatch_calls = [
        c for c in emit.call_args_list
        if c.args and c.args[0] == "qa.gate.status_mismatch"
    ]
    assert len(mismatch_calls) == 1, (
        f"expected 1 qa.gate.status_mismatch call, got {len(mismatch_calls)}"
    )
    tags = mismatch_calls[0].kwargs.get("tags", [])
    assert any("current:blocked" in t for t in tags), (
        f"mismatch counter missing current-status tag: {tags}"
    )
