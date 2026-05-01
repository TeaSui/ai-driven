"""Integration test for the T20 orchestrator-as-SQS-worker flow.

Rather than stand up LocalStack/moto for this test, we mock the SQS client
at the boto3 boundary and drive the full chain:

    enqueue_task('coding')
        → TaskProducer sends one message to the orchestrator queue
        → _OrchestratorWrapper.run() executes (mocked OrchestratorAgent)
        → idempotency marker written to the in-memory DB task
        → TaskProducer sends one message to the coding queue

Failure modes exercised:
    * at-least-once redelivery: a second .run() invocation after the marker
      is persisted MUST NOT re-call orchestrate().
    * orchestrate() raising: task transitions to status='failed' with
      failure_reason='orchestrator_error' and the coding-task is NOT enqueued.
"""
from __future__ import annotations

import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.config import get_settings


@pytest.fixture(autouse=True)
def _clear_settings_cache():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


class _FakeTask:
    """In-memory Task row whose ``output_data`` mutates across update calls."""

    def __init__(self) -> None:
        self.output_data: dict = {}
        self.status: str | None = None


class _FakeTaskRepo:
    """Just enough TaskRepository surface for the wrapper integration test."""

    def __init__(self, task: _FakeTask) -> None:
        self._task = task

    async def get_by_id(self, task_id):
        return self._task

    async def update_output_data(self, task_id, patch):
        self._task.output_data = {**(self._task.output_data or {}), **patch}

    async def update_status(self, task_id, status, **_):
        self._task.status = status


class _FakeSession:
    def __init__(self, repo: _FakeTaskRepo) -> None:
        self._repo = repo

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None

    async def commit(self):
        return None


def _settings_mock(**overrides):
    s = MagicMock()
    s.debug = False
    s.orchestrator_mode = "shadow"
    s.sqs_orchestrator_queue_url = "https://sqs.local/orch"
    s.sqs_task_queue_url = "https://sqs.local/coding"
    s.sqs_review_queue_url = "https://sqs.local/review"
    s.sqs_devops_queue_url = "https://sqs.local/devops"
    s.sqs_specialists_queue_url = "https://sqs.local/spec"
    s.aws_region = "us-east-1"
    s.sqs_endpoint_url = ""
    for k, v in overrides.items():
        setattr(s, k, v)
    return s


@pytest.mark.asyncio
async def test_orchestrator_worker_happy_path() -> None:
    """Full flow: producer → orchestrator queue → wrapper → marker → coding queue."""
    task_uuid = uuid.uuid4()
    task_id_str = str(task_uuid)

    # In-memory "DB" — the marker persists between the two wrapper invocations
    fake_task = _FakeTask()
    fake_repo = _FakeTaskRepo(fake_task)
    fake_session_factory = lambda: _FakeSession(fake_repo)  # noqa: E731

    # Two mocked SQS clients — one per TaskProducer instance. We track sent
    # messages per queue URL so we can assert end-to-end routing.
    sent: list[dict] = []

    def make_sqs_client():
        client = MagicMock()

        def send(**kwargs):
            sent.append(kwargs)
            return {"MessageId": f"msg-{len(sent)}"}

        client.send_message.side_effect = send
        return client

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock(return_value={
        "tier": "code_impl",
        "halted_on": None,
        "specialists_run": ["security", "qa"],
        "total_tokens": {"input": 1200, "output": 400},
        "artifacts": {},
    })

    settings_mock = _settings_mock()

    from ai_driven.queue import consumer as consumer_module
    from ai_driven.queue import producer as producer_module

    # Step 1 — API path enqueues via TaskProducer
    with patch.object(consumer_module, "async_session_factory", fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(producer_module, "get_settings",
                      return_value=settings_mock), \
         patch.object(producer_module, "boto3") as mock_boto3, \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch):
        mock_boto3.client.return_value = make_sqs_client()
        producer = producer_module.TaskProducer()
        # Re-use a single mock SQS client so all `sent` messages go in the same list
        producer._sqs = make_sqs_client()
        # rewire side effect on the new client
        producer._sqs.send_message.side_effect = lambda **kw: (
            sent.append(kw) or {"MessageId": f"msg-{len(sent)}"}
        )
        await producer.enqueue_task(
            task_id=task_id_str,
            agent_type="coding",
            payload={"message": "implement X", "repo_full_name": "o/r"},
        )

    # Assert: one message, routed to orchestrator queue
    assert len(sent) == 1
    assert sent[0]["QueueUrl"] == "https://sqs.local/orch"
    body_1 = json.loads(sent[0]["MessageBody"])
    # Body agent_type is 'orchestrator' so the consumer dispatches the
    # wrapper (not CodingAgent) when this message is received. The original
    # agent_type is preserved under `downstream_agent_type`.
    assert body_1["agent_type"] == "orchestrator"
    assert body_1["downstream_agent_type"] == "coding"
    assert body_1["task_id"] == task_id_str
    original_payload = body_1["payload"]

    # Step 2 — worker picks up the message, runs the wrapper
    # TaskProducer inside the wrapper will also send to the coding queue.
    with patch.object(consumer_module, "async_session_factory", fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(producer_module, "get_settings",
                      return_value=settings_mock), \
         patch.object(producer_module, "boto3") as mock_boto3, \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch):
        downstream_client = MagicMock()
        downstream_client.send_message.side_effect = lambda **kw: (
            sent.append(kw) or {"MessageId": f"msg-{len(sent)}"}
        )
        mock_boto3.client.return_value = downstream_client

        wrapper = consumer_module._OrchestratorWrapper()
        # Simulate the consumer calling the wrapper with the wire payload
        result = await wrapper.run(original_payload, task_id=task_id_str)

    # Assert: orchestrate() ran once
    fake_orch.orchestrate.assert_awaited_once()
    # Assert: idempotency marker persisted
    assert "orchestrator_completed_at" in fake_task.output_data
    assert fake_task.output_data["classifier_tier"] == "code_impl"
    # Assert: second SQS send is to the coding queue with ORIGINAL payload
    assert len(sent) == 2
    assert sent[1]["QueueUrl"] == "https://sqs.local/coding"
    body_2 = json.loads(sent[1]["MessageBody"])
    assert body_2["agent_type"] == "coding"
    assert body_2["payload"] == original_payload
    # AgentResult surfaced token counts from orchestrate()
    assert result.conversation.total_input_tokens == 1200
    assert result.conversation.total_output_tokens == 400


@pytest.mark.asyncio
async def test_orchestrator_worker_at_least_once_redelivery_idempotent() -> None:
    """SQS redelivers after the marker is written — second run MUST NOT re-orchestrate."""
    task_uuid = uuid.uuid4()
    task_id_str = str(task_uuid)

    # Pre-seed the task with a completed marker (simulating the first run)
    fake_task = _FakeTask()
    fake_task.output_data = {
        "orchestrator_completed_at": "2026-05-01T00:00:00+00:00",
        "classifier_tier": "code_impl",
    }
    fake_repo = _FakeTaskRepo(fake_task)
    fake_session_factory = lambda: _FakeSession(fake_repo)  # noqa: E731

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock()  # should never be called

    sent: list[dict] = []
    settings_mock = _settings_mock()

    from ai_driven.queue import consumer as consumer_module
    from ai_driven.queue import producer as producer_module

    with patch.object(consumer_module, "async_session_factory", fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(producer_module, "get_settings",
                      return_value=settings_mock), \
         patch.object(producer_module, "boto3") as mock_boto3, \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch):
        downstream_client = MagicMock()
        downstream_client.send_message.side_effect = lambda **kw: (
            sent.append(kw) or {"MessageId": f"msg-{len(sent)}"}
        )
        mock_boto3.client.return_value = downstream_client

        wrapper = consumer_module._OrchestratorWrapper()
        await wrapper.run({"task_id": task_id_str, "message": "x"},
                          task_id=task_id_str)

    # orchestrate() NOT re-invoked
    fake_orch.orchestrate.assert_not_awaited()
    # Coding-task still enqueued (crash between marker + enqueue → recover)
    assert len(sent) == 1
    assert sent[0]["QueueUrl"] == "https://sqs.local/coding"


@pytest.mark.asyncio
async def test_orchestrator_worker_marks_task_failed_on_exception() -> None:
    """orchestrate() raising → task.status='failed' + failure_reason + no coding enqueue."""
    task_uuid = uuid.uuid4()
    task_id_str = str(task_uuid)

    fake_task = _FakeTask()
    fake_repo = _FakeTaskRepo(fake_task)
    fake_session_factory = lambda: _FakeSession(fake_repo)  # noqa: E731

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock(side_effect=RuntimeError("classifier crashed"))

    sent: list[dict] = []
    settings_mock = _settings_mock()

    from ai_driven.queue import consumer as consumer_module
    from ai_driven.queue import producer as producer_module

    with patch.object(consumer_module, "async_session_factory", fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(producer_module, "get_settings",
                      return_value=settings_mock), \
         patch.object(producer_module, "boto3") as mock_boto3, \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch):
        downstream_client = MagicMock()
        downstream_client.send_message.side_effect = lambda **kw: (
            sent.append(kw) or {"MessageId": f"msg-{len(sent)}"}
        )
        mock_boto3.client.return_value = downstream_client

        wrapper = consumer_module._OrchestratorWrapper()
        with pytest.raises(RuntimeError):
            await wrapper.run({"task_id": task_id_str, "message": "x"},
                              task_id=task_id_str)

    # Task marked failed with the T20 failure_reason
    assert fake_task.status == "failed"
    assert fake_task.output_data.get("failure_reason") == "orchestrator_error"
    # NO downstream coding-task enqueued
    assert sent == []


# ── T21 strict-gating integration tests ─────────────────────────────────────


def _block_result(role: str) -> dict:
    """orchestrate() return value simulating a specialist block."""
    return {
        "tier": "full_workflow",
        "halted_on": role,
        "specialists_run": [role],
        "total_tokens": {"input": 200, "output": 80},
        "artifacts": {role: "block"},
    }


class _FakeArtifactRow:
    """Minimal TaskArtifact row stand-in for the blocker_artifact lookup."""

    def __init__(self, role: str, *, payload=None, reasoning=None,
                 artifact_id: str = "art-123"):
        self.id = artifact_id
        self.role = role
        self.payload = payload or {}
        self.reasoning = reasoning


@pytest.mark.asyncio
async def test_t21_shadow_mode_block_still_enqueues_coding() -> None:
    """Shadow mode: BA block → coding-task STILL enqueued (pre-T21 behaviour)."""
    task_uuid = uuid.uuid4()
    task_id_str = str(task_uuid)

    fake_task = _FakeTask()
    fake_repo = _FakeTaskRepo(fake_task)
    fake_session_factory = lambda: _FakeSession(fake_repo)  # noqa: E731

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock(return_value=_block_result("business_analyst"))

    sent: list[dict] = []
    settings_mock = _settings_mock(orchestrator_mode="shadow")

    from ai_driven.queue import consumer as consumer_module
    from ai_driven.queue import producer as producer_module

    with patch.object(consumer_module, "async_session_factory", fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(producer_module, "get_settings",
                      return_value=settings_mock), \
         patch.object(producer_module, "boto3") as mock_boto3, \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch):
        downstream_client = MagicMock()
        downstream_client.send_message.side_effect = lambda **kw: (
            sent.append(kw) or {"MessageId": f"msg-{len(sent)}"}
        )
        mock_boto3.client.return_value = downstream_client

        wrapper = consumer_module._OrchestratorWrapper()
        # TaskConsumer would pass orchestrator_mode='shadow' through from the
        # SQS message stamp.
        await wrapper.run(
            {"task_id": task_id_str, "message": "x"},
            task_id=task_id_str,
            orchestrator_mode="shadow",
        )

    # Coding task STILL enqueued — shadow mode never halts.
    assert len(sent) == 1
    assert sent[0]["QueueUrl"] == "https://sqs.local/coding"
    # Task not marked blocked.
    assert fake_task.status != "blocked"
    # halted_on still written to output_data for telemetry.
    assert fake_task.output_data.get("orchestrator_halted_on") == "business_analyst"


@pytest.mark.asyncio
async def test_t21_live_mode_block_halts_and_persists_blocked_status() -> None:
    """Live mode: BA block → Task.status='blocked', no coding-task enqueued."""
    task_uuid = uuid.uuid4()
    task_id_str = str(task_uuid)

    fake_task = _FakeTask()
    fake_repo = _FakeTaskRepo(fake_task)
    fake_session_factory = lambda: _FakeSession(fake_repo)  # noqa: E731

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock(return_value=_block_result("business_analyst"))

    sent: list[dict] = []
    settings_mock = _settings_mock(orchestrator_mode="live")

    from ai_driven.queue import consumer as consumer_module
    from ai_driven.queue import producer as producer_module

    # Stub the blocker-artifact lookup to return a BA payload with
    # blocking_questions so _extract_blocking_reasons has real data.
    blocker_row = _FakeArtifactRow(
        "business_analyst",
        payload={"blocking_questions": ["Is customer_id a uuid?"]},
        reasoning="ambiguous spec",
        artifact_id="ba-row-1",
    )

    async def _load_blocker(task_uuid_arg, role):
        assert role == "business_analyst"
        return blocker_row, blocker_row.id

    with patch.object(consumer_module, "async_session_factory", fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(consumer_module._OrchestratorWrapper,
                      "_load_blocker_artifact",
                      staticmethod(_load_blocker)), \
         patch.object(producer_module, "get_settings",
                      return_value=settings_mock), \
         patch.object(producer_module, "boto3") as mock_boto3, \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch):
        downstream_client = MagicMock()
        downstream_client.send_message.side_effect = lambda **kw: (
            sent.append(kw) or {"MessageId": f"msg-{len(sent)}"}
        )
        mock_boto3.client.return_value = downstream_client

        wrapper = consumer_module._OrchestratorWrapper()
        await wrapper.run(
            {"task_id": task_id_str, "message": "add customer lookup"},
            task_id=task_id_str,
            orchestrator_mode="live",
        )

    # Task transitioned to 'blocked' (T21 migration added this to ck_tasks_status).
    assert fake_task.status == "blocked"
    # Blocker details captured on output_data.
    assert fake_task.output_data["blocked_by"] == "business_analyst"
    assert fake_task.output_data["blocking_reasons"] == ["Is customer_id a uuid?"]
    assert fake_task.output_data["blocker_artifact_id"] == "ba-row-1"
    # Idempotency marker still written so SQS redelivery won't re-run specialists.
    assert "orchestrator_completed_at" in fake_task.output_data
    # NO coding-task enqueued.
    assert sent == []


@pytest.mark.asyncio
async def test_t21_live_mode_qa_block_still_enqueues_coding() -> None:
    """Live mode + QA block (pre-coding final_gate) → coding-task STILL enqueued.

    routing.py currently runs QA BEFORE CodingAgent as ``final_gate``.
    Pre-coding QA has no PR to validate so it would false-halt every
    full_workflow / multi_domain task. T22 will split pre/post QA; until
    then _should_halt_pipeline hard-codes the qa exclusion.

    See pre-baked red-team finding #1 in the T21 task brief.
    """
    task_uuid = uuid.uuid4()
    task_id_str = str(task_uuid)

    fake_task = _FakeTask()
    fake_repo = _FakeTaskRepo(fake_task)
    fake_session_factory = lambda: _FakeSession(fake_repo)  # noqa: E731

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock(return_value=_block_result("qa"))

    sent: list[dict] = []
    settings_mock = _settings_mock(orchestrator_mode="live")

    from ai_driven.queue import consumer as consumer_module
    from ai_driven.queue import producer as producer_module

    with patch.object(consumer_module, "async_session_factory", fake_session_factory), \
         patch.object(consumer_module, "TaskRepository",
                      side_effect=lambda _s: fake_repo), \
         patch.object(producer_module, "get_settings",
                      return_value=settings_mock), \
         patch.object(producer_module, "boto3") as mock_boto3, \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch):
        downstream_client = MagicMock()
        downstream_client.send_message.side_effect = lambda **kw: (
            sent.append(kw) or {"MessageId": f"msg-{len(sent)}"}
        )
        mock_boto3.client.return_value = downstream_client

        wrapper = consumer_module._OrchestratorWrapper()
        await wrapper.run(
            {"task_id": task_id_str, "message": "x"},
            task_id=task_id_str,
            orchestrator_mode="live",
        )

    # QA halt is IGNORED — coding-task still enqueued.
    assert len(sent) == 1
    assert sent[0]["QueueUrl"] == "https://sqs.local/coding"
    # Task NOT blocked.
    assert fake_task.status != "blocked"
    assert "blocked_by" not in fake_task.output_data
