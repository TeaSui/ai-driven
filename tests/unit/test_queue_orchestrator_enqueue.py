"""Unit tests for T20 orchestrator-queue routing in TaskProducer.

The old fire-and-forget ``_schedule_shadow_orchestrator`` path was deleted
in T20. Coding-task submissions now route to the **orchestrator** SQS queue
instead of the coding queue — the orchestrator worker runs classifier +
specialists and then enqueues the downstream coding-task itself.

These tests pin that behaviour so a future accidental re-route back to the
coding queue (skipping orchestration entirely) is caught.
"""
from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.config import get_settings


@pytest.fixture(autouse=True)
def _clear_settings_cache():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _make_producer(
    *,
    orchestrator_mode: str = "shadow",
    orchestrator_queue_url: str = "https://sqs.local/orch",
    coding_queue_url: str = "https://sqs.local/coding",
    debug: bool = False,
):
    """Construct a TaskProducer with stubbed boto3 SQS + stubbed settings."""
    from ai_driven.queue import producer as producer_module

    mock_sqs = MagicMock()
    mock_sqs.send_message.return_value = {"MessageId": "msg-123"}

    s = MagicMock()
    s.debug = debug
    s.orchestrator_mode = orchestrator_mode
    s.sqs_orchestrator_queue_url = orchestrator_queue_url
    s.sqs_task_queue_url = coding_queue_url
    s.sqs_review_queue_url = "https://sqs.local/review"
    s.sqs_devops_queue_url = "https://sqs.local/devops"
    s.sqs_specialists_queue_url = "https://sqs.local/spec"
    s.aws_region = "us-east-1"
    s.sqs_endpoint_url = ""

    with patch.object(producer_module, "boto3") as mock_boto3, \
         patch.object(producer_module, "get_settings", return_value=s):
        mock_boto3.client.return_value = mock_sqs
        prod = producer_module.TaskProducer()
        # Replace the internal client with our mock (the second get_settings
        # inside enqueue_task would otherwise build a new map). The queue_map
        # built at init time already holds the URLs above.
        prod._sqs = mock_sqs
        return prod, mock_sqs, s


@pytest.mark.asyncio
async def test_coding_task_routes_to_orchestrator_queue() -> None:
    """coding agent_type + orchestrator_mode='shadow' → orchestrator queue."""
    prod, sqs, settings_mock = _make_producer()

    with patch("ai_driven.queue.producer.get_settings",
               return_value=settings_mock):
        msg_id = await prod.enqueue_task(
            task_id="t-1",
            agent_type="coding",
            payload={"message": "do the thing"},
        )

    assert msg_id == "msg-123"
    sqs.send_message.assert_called_once()
    call = sqs.send_message.call_args
    assert call.kwargs["QueueUrl"] == "https://sqs.local/orch"
    # MessageGroupId reflects the effective queue, not the original agent_type
    assert call.kwargs["MessageGroupId"] == "orchestrator"
    # The ORIGINAL agent_type stays on the wire so the orchestrator worker
    # knows which downstream queue to enqueue to after orchestrate() runs.
    import json as _json
    body = _json.loads(call.kwargs["MessageBody"])
    assert body["agent_type"] == "coding"
    assert body["task_id"] == "t-1"
    assert body["payload"] == {"message": "do the thing"}
    # T21: orchestrator_mode stamped on the message body so a mid-deploy
    # flip can't confuse a task that's already in flight.
    assert body["orchestrator_mode"] == "shadow"


@pytest.mark.asyncio
async def test_message_body_stamps_orchestrator_mode_live() -> None:
    """T21: producer stamps orchestrator_mode='live' when settings say live."""
    prod, sqs, settings_mock = _make_producer(orchestrator_mode="live")

    with patch("ai_driven.queue.producer.get_settings",
               return_value=settings_mock):
        await prod.enqueue_task(
            task_id="t-live", agent_type="coding",
            payload={"message": "do the thing"},
        )

    sqs.send_message.assert_called_once()
    import json as _json
    body = _json.loads(sqs.send_message.call_args.kwargs["MessageBody"])
    assert body["orchestrator_mode"] == "live"


@pytest.mark.asyncio
async def test_message_body_stamps_orchestrator_mode_for_non_coding() -> None:
    """Stamp is present for every agent_type — review/devops/etc — for uniform worker parsing."""
    prod, sqs, settings_mock = _make_producer(orchestrator_mode="shadow")

    with patch("ai_driven.queue.producer.get_settings",
               return_value=settings_mock):
        await prod.enqueue_task(
            task_id="r-stamp", agent_type="review",
            payload={"message": "x"},
        )

    sqs.send_message.assert_called_once()
    import json as _json
    body = _json.loads(sqs.send_message.call_args.kwargs["MessageBody"])
    assert body["orchestrator_mode"] == "shadow"


@pytest.mark.asyncio
async def test_coding_task_routes_to_coding_queue_when_orchestrator_off() -> None:
    """orchestrator_mode='off' disables routing — goes straight to coding queue."""
    prod, sqs, settings_mock = _make_producer(orchestrator_mode="off")

    with patch("ai_driven.queue.producer.get_settings",
               return_value=settings_mock):
        await prod.enqueue_task(
            task_id="t-2", agent_type="coding", payload={"message": "x"},
        )

    sqs.send_message.assert_called_once()
    assert sqs.send_message.call_args.kwargs["QueueUrl"] == "https://sqs.local/coding"
    assert sqs.send_message.call_args.kwargs["MessageGroupId"] == "coding"


@pytest.mark.asyncio
async def test_coding_task_routes_to_coding_queue_when_orch_queue_missing() -> None:
    """No orchestrator queue URL configured → fall back to coding queue.

    Prevents silently dropping coding-tasks when the orchestrator queue is
    un-provisioned (early deploys, local dev without LocalStack setup).
    """
    prod, sqs, settings_mock = _make_producer(orchestrator_queue_url="")

    with patch("ai_driven.queue.producer.get_settings",
               return_value=settings_mock):
        await prod.enqueue_task(
            task_id="t-3", agent_type="coding", payload={"message": "x"},
        )

    sqs.send_message.assert_called_once()
    assert sqs.send_message.call_args.kwargs["QueueUrl"] == "https://sqs.local/coding"


@pytest.mark.asyncio
async def test_non_coding_tasks_bypass_orchestrator_route() -> None:
    """review/devops/orchestrator agent_types go to their own queues directly."""
    prod, sqs, settings_mock = _make_producer()

    with patch("ai_driven.queue.producer.get_settings",
               return_value=settings_mock):
        await prod.enqueue_task(
            task_id="r-1", agent_type="review", payload={"message": "x"},
        )

    sqs.send_message.assert_called_once()
    assert sqs.send_message.call_args.kwargs["QueueUrl"] == "https://sqs.local/review"
    assert sqs.send_message.call_args.kwargs["MessageGroupId"] == "review"


@pytest.mark.asyncio
async def test_orchestrator_agent_type_routes_to_orchestrator_queue() -> None:
    """Explicit agent_type='orchestrator' (used by worker re-enqueue) respected."""
    prod, sqs, settings_mock = _make_producer()

    with patch("ai_driven.queue.producer.get_settings",
               return_value=settings_mock):
        await prod.enqueue_task(
            task_id="o-1", agent_type="orchestrator", payload={"message": "x"},
        )

    sqs.send_message.assert_called_once()
    assert sqs.send_message.call_args.kwargs["QueueUrl"] == "https://sqs.local/orch"


# ── _OrchestratorWrapper idempotency + downstream-enqueue tests ──────────────


@pytest.mark.asyncio
async def test_wrapper_runs_orchestrate_and_enqueues_coding() -> None:
    """Normal path: no marker → run orchestrate() → write marker → enqueue coding."""
    import uuid as _uuid
    from ai_driven.queue import consumer as consumer_module

    task_uuid = _uuid.uuid4()
    task_id_str = str(task_uuid)

    # Fake DB session + repo so update_output_data is a no-op
    fake_task = MagicMock()
    fake_task.output_data = {}
    fake_repo = MagicMock()
    fake_repo.get_by_id = AsyncMock(return_value=fake_task)
    fake_repo.update_output_data = AsyncMock()
    fake_repo.update_status = AsyncMock()
    fake_session = MagicMock()
    fake_session.commit = AsyncMock()
    fake_session.__aenter__ = AsyncMock(return_value=fake_session)
    fake_session.__aexit__ = AsyncMock(return_value=None)

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock(return_value={
        "tier": "code_impl",
        "halted_on": None,
        "total_tokens": {"input": 100, "output": 50},
        "artifacts": {},
    })

    fake_producer = MagicMock()
    fake_producer.enqueue_task = AsyncMock(return_value="msg-downstream")

    with patch.object(consumer_module, "async_session_factory",
                      return_value=fake_session), \
         patch.object(consumer_module, "TaskRepository",
                      return_value=fake_repo), \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch), \
         patch("ai_driven.queue.producer.TaskProducer",
               return_value=fake_producer):
        wrapper = consumer_module._OrchestratorWrapper()
        payload = {"task_id": task_id_str, "message": "fix bug",
                   "repo_full_name": "o/r"}
        result = await wrapper.run(payload, task_id=task_id_str)

    # orchestrate() was called
    fake_orch.orchestrate.assert_awaited_once()
    # idempotency marker written
    fake_repo.update_output_data.assert_awaited()
    marker_patch = fake_repo.update_output_data.await_args.args[1]
    assert "orchestrator_completed_at" in marker_patch
    assert marker_patch["classifier_tier"] == "code_impl"
    # coding-task enqueued with ORIGINAL payload
    fake_producer.enqueue_task.assert_awaited_once()
    kw = fake_producer.enqueue_task.await_args.kwargs
    assert kw["agent_type"] == "coding"
    assert kw["task_id"] == task_id_str
    assert kw["payload"] is payload  # same dict, not a derivative
    # AgentResult surfaced tokens
    assert result.conversation.total_input_tokens == 100
    assert result.conversation.total_output_tokens == 50


@pytest.mark.asyncio
async def test_wrapper_skips_orchestrate_when_marker_present() -> None:
    """Crash-recovery: marker present → skip orchestrate(), still enqueue coding."""
    import uuid as _uuid
    from ai_driven.queue import consumer as consumer_module

    task_uuid = _uuid.uuid4()
    task_id_str = str(task_uuid)

    fake_task = MagicMock()
    fake_task.output_data = {
        "orchestrator_completed_at": "2026-05-01T00:00:00+00:00",
        "classifier_tier": "code_impl",
    }
    fake_repo = MagicMock()
    fake_repo.get_by_id = AsyncMock(return_value=fake_task)
    fake_repo.update_output_data = AsyncMock()
    fake_session = MagicMock()
    fake_session.commit = AsyncMock()
    fake_session.__aenter__ = AsyncMock(return_value=fake_session)
    fake_session.__aexit__ = AsyncMock(return_value=None)

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock()

    fake_producer = MagicMock()
    fake_producer.enqueue_task = AsyncMock(return_value="msg-downstream")

    with patch.object(consumer_module, "async_session_factory",
                      return_value=fake_session), \
         patch.object(consumer_module, "TaskRepository",
                      return_value=fake_repo), \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch), \
         patch("ai_driven.queue.producer.TaskProducer",
               return_value=fake_producer):
        wrapper = consumer_module._OrchestratorWrapper()
        await wrapper.run({"task_id": task_id_str, "message": "x"},
                          task_id=task_id_str)

    # orchestrate() NOT called (idempotent skip)
    fake_orch.orchestrate.assert_not_awaited()
    # No new marker write (existing marker preserved)
    fake_repo.update_output_data.assert_not_awaited()
    # Coding-task still enqueued so crash between marker-write and enqueue
    # converges on the happy path.
    fake_producer.enqueue_task.assert_awaited_once()


@pytest.mark.asyncio
async def test_wrapper_marks_failed_on_orchestrate_error() -> None:
    """orchestrate() raises → status=failed, failure_reason='orchestrator_error', re-raise."""
    import uuid as _uuid
    from ai_driven.queue import consumer as consumer_module

    task_uuid = _uuid.uuid4()
    task_id_str = str(task_uuid)

    fake_task = MagicMock()
    fake_task.output_data = {}
    fake_repo = MagicMock()
    fake_repo.get_by_id = AsyncMock(return_value=fake_task)
    fake_repo.update_output_data = AsyncMock()
    fake_repo.update_status = AsyncMock()
    fake_session = MagicMock()
    fake_session.commit = AsyncMock()
    fake_session.__aenter__ = AsyncMock(return_value=fake_session)
    fake_session.__aexit__ = AsyncMock(return_value=None)

    fake_orch = MagicMock()
    fake_orch.orchestrate = AsyncMock(side_effect=RuntimeError("boom"))

    fake_producer = MagicMock()
    fake_producer.enqueue_task = AsyncMock()

    with patch.object(consumer_module, "async_session_factory",
                      return_value=fake_session), \
         patch.object(consumer_module, "TaskRepository",
                      return_value=fake_repo), \
         patch("ai_driven.agents.orchestrator.agent.OrchestratorAgent",
               return_value=fake_orch), \
         patch("ai_driven.queue.producer.TaskProducer",
               return_value=fake_producer):
        wrapper = consumer_module._OrchestratorWrapper()
        with pytest.raises(RuntimeError):
            await wrapper.run({"task_id": task_id_str, "message": "x"},
                              task_id=task_id_str)

    # failure_reason persisted + status set to failed
    fake_repo.update_output_data.assert_awaited()
    reason_patch = fake_repo.update_output_data.await_args.args[1]
    assert reason_patch["failure_reason"] == "orchestrator_error"
    fake_repo.update_status.assert_awaited_with(task_uuid, "failed")
    # NO coding-task enqueued on orchestrator failure
    fake_producer.enqueue_task.assert_not_awaited()


def test_build_orchestrate_payload_translates_flat_to_nested() -> None:
    """Helper used by both the wrapper and debug-mode inline path."""
    from ai_driven.queue.consumer import _build_orchestrate_payload

    flat = {
        "message": "fix bug",
        "jira_key": "ABC-1",
        "repo_full_name": "o/r",
        "scm_type": "github",
        "labels": ["backend"],
        "acceptance_criteria": ["it works"],
    }
    out = _build_orchestrate_payload(flat, "task-42")
    assert out["task_id"] == "task-42"
    assert out["ticket"]["key"] == "ABC-1"
    assert out["ticket"]["summary"] == "fix bug"
    assert out["ticket"]["description"] == "fix bug"
    assert out["ticket"]["labels"] == ["backend"]
    assert out["repo"]["full_name"] == "o/r"
    assert out["repo"]["scm_type"] == "github"
    assert out["repo"]["default_branch"] == "main"


def test_build_orchestrate_payload_preserves_nested_shape() -> None:
    """If payload is already orchestrate-shaped, we pass it through."""
    from ai_driven.queue.consumer import _build_orchestrate_payload

    nested = {
        "ticket": {"key": "X-1", "summary": "s", "description": "d"},
        "repo":   {"full_name": "o/r", "scm_type": "bitbucket",
                   "default_branch": "develop"},
    }
    out = _build_orchestrate_payload(nested, "task-9")
    assert out["ticket"] == nested["ticket"]
    assert out["repo"] == nested["repo"]
