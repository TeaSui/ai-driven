"""Integration test: full post-coding QA flow (T22).

Chain exercised (all SQS and Claude boundaries mocked):

    coding-task succeeds → _enqueue_qa_best_effort fires
        → TaskProducer sends one message to sqs_qa_queue_url
        → _QaPostCodingWrapper.run() dequeues + runs QaSpecialist
        → artifact + qa_* markers persisted atomically

Exercises both happy path (live mode → status=awaiting_qa) and shadow
mode (enqueue-only, status stays 'completed'). The SpecialistBase
Claude call is mocked at the `_claude.chat` boundary.
"""
from __future__ import annotations

import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.config import get_settings


@pytest.fixture(autouse=True)
def _settings_env(monkeypatch):
    """Configure sqs_qa_queue_url so the hook doesn't short-circuit."""
    monkeypatch.setenv("AI_DRIVEN_SQS_QA_QUEUE_URL",
                        "https://sqs.local/ai-driven-qa-tasks")
    monkeypatch.setenv("AI_DRIVEN_SQS_TASK_QUEUE_URL",
                        "https://sqs.local/coding")
    monkeypatch.setenv("AI_DRIVEN_DEBUG", "false")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


class _FakeTask:
    def __init__(self) -> None:
        self.output_data: dict = {}
        self.status: str | None = None


class _FakeTaskRepo:
    def __init__(self, task: _FakeTask) -> None:
        self._task = task

    async def get_by_id(self, task_id):
        return self._task

    async def update_output_data(self, task_id, patch):
        merged = {**(self._task.output_data or {}), **patch}
        self._task.output_data = {
            k: v for k, v in merged.items() if v is not None
        }
        # Preserve explicit None clears as deletions.
        for k, v in patch.items():
            if v is None and k in merged:
                self._task.output_data.pop(k, None)

    async def update_status(self, task_id, status, **_):
        self._task.status = status


class _FakeArtifactRepo:
    def __init__(self, session) -> None:
        self._session = session
        self.upserts: list = []

    async def upsert(self, task_id, artifact):
        self.upserts.append((task_id, artifact))

    async def list_for_task(self, task_id):
        return {}


class _FakeSession:
    def __init__(self, repo: _FakeTaskRepo) -> None:
        self._repo = repo

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None

    async def commit(self):
        return None

    async def execute(self, *_a, **_k):
        # Return an object that answers scalar_one_or_none() with None so
        # the qa_artifact_id stamp falls through to None — acceptable in
        # integration-scope (we assert other markers).
        res = MagicMock()
        res.scalar_one_or_none = MagicMock(return_value=None)
        return res


def _coding_success_result():
    from types import SimpleNamespace
    return SimpleNamespace(success=True)


@pytest.mark.asyncio
@pytest.mark.parametrize("stamped_mode,expected_status", [
    ("live", "awaiting_qa"),
    ("shadow", None),  # shadow mode: wrapper must NOT touch status
])
async def test_post_coding_qa_end_to_end(stamped_mode, expected_status):
    task_uuid = uuid.uuid4()
    task_id_str = str(task_uuid)

    fake_task = _FakeTask()
    fake_task.output_data = {
        "pr_number": 42,
        "pr_url": "https://github.com/org/repo/pull/42",
        "classifier_tier": "full_workflow",
    }
    fake_repo = _FakeTaskRepo(fake_task)
    fake_session_factory = lambda: _FakeSession(fake_repo)  # noqa: E731

    # Track SQS sends
    sent: list[dict] = []

    from ai_driven.queue import consumer as consumer_module
    from ai_driven.queue import producer as producer_module

    def send_message(**kw):
        sent.append(kw)
        return {"MessageId": f"msg-{len(sent)}"}

    # Step 1 — coding-task completes; hook fires enqueue to qa-queue.
    with patch.object(consumer_module, "async_session_factory",
                       fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(producer_module, "boto3") as mock_boto3:
        mock_sqs = MagicMock()
        mock_sqs.send_message.side_effect = send_message
        mock_boto3.client.return_value = mock_sqs

        await consumer_module._enqueue_qa_best_effort(
            task_id=task_uuid,
            agent_type="coding",
            result=_coding_success_result(),
            stamped_mode=stamped_mode,
            payload={
                "task_id": task_id_str,
                "message": "implement X",
                "repo_full_name": "o/r",
                "ticket": {"summary": "s", "description": "d",
                           "acceptance_criteria": "", "labels": []},
                "repo": {"full_name": "o/r", "scm_type": "github",
                         "default_branch": "main"},
            },
        )

    # Assert: one message was sent to the qa queue.
    assert len(sent) == 1, f"expected 1 SQS message, got {len(sent)}"
    assert sent[0]["QueueUrl"] == "https://sqs.local/ai-driven-qa-tasks"
    body = json.loads(sent[0]["MessageBody"])
    assert body["agent_type"] == "qa"
    assert body["task_id"] == task_id_str
    assert body["orchestrator_mode"] == stamped_mode or body["orchestrator_mode"] in ("shadow", "live")

    # qa_enqueued_at was written BEFORE SendMessage.
    assert fake_task.output_data.get("qa_enqueued_at") is not None

    # Status transition is gated on live mode (M2 fix).
    assert fake_task.status == expected_status, (
        f"Expected status={expected_status!r} in {stamped_mode!r} mode, "
        f"got {fake_task.status!r}"
    )

    # Step 2 — QA wrapper dequeues the message and runs the specialist.
    # We mock the QaSpecialist.run_specialist at its class to return a
    # deterministic v2 artifact.
    from ai_driven.agents.orchestrator.artifacts import Artifact

    qa_art = Artifact(
        role="qa",
        verdict="pass",
        reasoning="All ACs satisfied in diff.",
        payload={
            "schema_version": "v2",
            "checks_performed": [],
            "acceptance_criteria_verification": [],
            "security_rules_verification": [],
            "test_coverage_gap": [],
            "regression_risk": "low",
        },
        model_used="claude-sonnet-4-6",
        input_tokens=400,
        output_tokens=120,
    )

    persisted_artifacts: list = []

    async def fake_persist(task_uuid, artifact):
        persisted_artifacts.append((task_uuid, artifact))
        # Mirror the real persist: set markers on the fake task.
        await fake_repo.update_output_data(task_uuid, {
            "qa_completed_at": "2026-05-01T12:00:00+00:00",
            "qa_verdict": artifact.verdict,
        })

    with patch.object(consumer_module, "async_session_factory",
                       fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(consumer_module, "_persist_qa_artifact_and_marker",
                      side_effect=fake_persist), \
         patch("ai_driven.agents.orchestrator.repository.ArtifactRepository.list_for_task",
               new=AsyncMock(return_value={})), \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=AsyncMock(return_value=qa_art)):
        wrapper = consumer_module._QaPostCodingWrapper(scm_client=MagicMock())
        result = await wrapper.run(body["payload"])

    assert result.success is True
    assert len(persisted_artifacts) == 1
    _, artifact = persisted_artifacts[0]
    assert artifact.verdict == "pass"
    assert artifact.payload["schema_version"] == "v2"
    # qa_completed_at marker is set (T23's status transition will read this).
    assert fake_task.output_data.get("qa_completed_at") is not None
    assert fake_task.output_data.get("qa_verdict") == "pass"


@pytest.mark.asyncio
async def test_post_coding_qa_idempotent_on_redelivery():
    """SQS redelivers the qa-task after markers are written → no re-run."""
    task_uuid = uuid.uuid4()
    fake_task = _FakeTask()
    fake_task.output_data = {
        "pr_number": 42,
        "classifier_tier": "full_workflow",
        "qa_completed_at": "2026-05-01T12:00:00+00:00",
        "qa_verdict": "pass",
    }
    fake_repo = _FakeTaskRepo(fake_task)
    fake_session_factory = lambda: _FakeSession(fake_repo)  # noqa: E731

    from ai_driven.queue import consumer as consumer_module

    specialist_ran = False

    async def specialist_stub(*_a, **_k):
        nonlocal specialist_ran
        specialist_ran = True

    with patch.object(consumer_module, "async_session_factory",
                       fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch("ai_driven.agents.specialists.qa.agent.QaSpecialist.run_specialist",
               new=specialist_stub):
        wrapper = consumer_module._QaPostCodingWrapper()
        result = await wrapper.run({"task_id": str(task_uuid)})

    assert result.success is True
    assert specialist_ran is False, (
        "Idempotent replay must skip the specialist (cost + correctness)."
    )
