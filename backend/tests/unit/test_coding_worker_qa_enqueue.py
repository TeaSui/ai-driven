"""Unit tests for the coding worker's post-coding QA enqueue hook (T22).

Invariants per T22 brief section 10:
  * full_workflow + live mode → enqueue, status transitions to awaiting_qa.
  * full_workflow + shadow mode → ALSO enqueues (telemetry); status stays
    'completed' (M2 fix — admin dashboards keep working).
  * fast_path → no enqueue; status remains 'completed'.
  * missing tier → qa.post_coding.classifier_tier_missing counter emitted;
    no enqueue.
  * missing pr_number → skipped (graceful, wrapper handles Sev-1 counter).
  * qa_enqueued_at already present → idempotent, no re-enqueue.
  * SendMessage failure → marker cleared, counter emitted, exception
    SWALLOWED (B1 fix); coding task stays status='completed' with
    pr_number intact.
  * agent_type != 'coding' → no-op.
  * result.success = False → no-op.
"""
from __future__ import annotations

import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.queue import consumer as consumer_mod
from ai_driven.queue.consumer import _enqueue_qa_best_effort


def _mk_task_row(output_data: dict | None = None) -> MagicMock:
    row = MagicMock()
    row.output_data = output_data or {}
    return row


@pytest.fixture(autouse=True)
def _set_qa_queue_url(monkeypatch):
    """Ensure sqs_qa_queue_url is set so the hook doesn't graceful-skip."""
    from ai_driven.config import get_settings
    monkeypatch.setenv("AI_DRIVEN_SQS_QA_QUEUE_URL",
                        "https://sqs/test/ai-driven-qa-tasks")
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _good_result() -> SimpleNamespace:
    return SimpleNamespace(success=True)


async def _run_hook(output_data: dict, stamped_mode: str = "live",
                     send_raises: bool = False):
    """Helper: run _enqueue_qa_best_effort with a stubbed DB + producer.

    Returns (producer_enqueue_call_args, status_update_calls,
             output_patches, emit_counter_calls).
    """
    task_id = uuid.uuid4()
    produced: list = []
    output_patches: list[dict] = []
    status_updates: list[str] = []

    # Track every output_data patch so the test can assert the marker
    # write order (qa_enqueued_at set before SendMessage, cleared on
    # failure).
    fake_task_repo = MagicMock()
    fake_task_repo.get_by_id = AsyncMock(
        return_value=_mk_task_row(output_data)
    )

    async def fake_update_output_data(tid, patch):
        output_patches.append(patch)

    async def fake_update_status(tid, status):
        status_updates.append(status)

    fake_task_repo.update_output_data = fake_update_output_data
    fake_task_repo.update_status = fake_update_status

    fake_producer = MagicMock()
    if send_raises:
        fake_producer.enqueue_task = AsyncMock(
            side_effect=RuntimeError("sqs down")
        )
    else:
        async def capture(*, task_id, agent_type, payload, **kw):
            produced.append({"task_id": task_id, "agent_type": agent_type,
                              "payload": payload})
            return "msg-id"

        fake_producer.enqueue_task = capture

    emit_calls: list[str] = []

    with patch.object(consumer_mod, "async_session_factory") as sess_ctx, \
         patch.object(consumer_mod, "TaskRepository", return_value=fake_task_repo), \
         patch("ai_driven.queue.producer.TaskProducer",
               return_value=fake_producer), \
         patch("ai_driven.observability.metrics.emit_counter",
               side_effect=lambda name, tags=None: emit_calls.append(name)):
        fake_session = AsyncMock()
        sess_ctx.return_value.__aenter__.return_value = fake_session
        sess_ctx.return_value.__aexit__.return_value = False

        await _enqueue_qa_best_effort(
            task_id=task_id,
            agent_type="coding",
            result=_good_result(),
            stamped_mode=stamped_mode,
            payload={"task_id": str(task_id), "message": "hi"},
        )

    return produced, status_updates, output_patches, emit_calls


@pytest.mark.asyncio
async def test_full_workflow_live_mode_enqueues_and_transitions_status():
    produced, status_updates, patches, emits = await _run_hook(
        {"pr_number": 42, "classifier_tier": "full_workflow"},
        stamped_mode="live",
    )
    assert len(produced) == 1
    assert produced[0]["agent_type"] == "qa"
    assert status_updates == ["awaiting_qa"]
    # qa_enqueued_at written BEFORE SendMessage (first patch, non-null).
    assert patches[0]["qa_enqueued_at"] is not None
    # No clear on success.
    assert all(p.get("qa_enqueued_at") is not None for p in patches)


@pytest.mark.asyncio
async def test_full_workflow_shadow_mode_enqueues_without_status_change():
    produced, status_updates, patches, _ = await _run_hook(
        {"pr_number": 42, "classifier_tier": "full_workflow"},
        stamped_mode="shadow",
    )
    assert len(produced) == 1, "Shadow mode must still enqueue QA (telemetry)"
    assert status_updates == [], (
        "Shadow mode must NOT transition to awaiting_qa (T22 M2 fix)."
    )


@pytest.mark.asyncio
async def test_multi_domain_live_mode_enqueues():
    produced, status_updates, _, _ = await _run_hook(
        {"pr_number": 42, "classifier_tier": "multi_domain"},
        stamped_mode="live",
    )
    assert len(produced) == 1
    assert status_updates == ["awaiting_qa"]


@pytest.mark.asyncio
async def test_fast_path_does_not_enqueue_or_transition():
    produced, status_updates, _, _ = await _run_hook(
        {"pr_number": 42, "classifier_tier": "fast_path"},
        stamped_mode="live",
    )
    assert produced == []
    assert status_updates == []


@pytest.mark.asyncio
async def test_missing_tier_emits_counter_and_skips():
    produced, status_updates, _, emits = await _run_hook(
        {"pr_number": 42},  # no classifier_tier
        stamped_mode="live",
    )
    assert produced == []
    assert status_updates == []
    assert "qa.post_coding.classifier_tier_missing" in emits


@pytest.mark.asyncio
async def test_missing_pr_number_skips_gracefully():
    produced, status_updates, patches, emits = await _run_hook(
        {"classifier_tier": "full_workflow"},  # no pr_number
        stamped_mode="live",
    )
    assert produced == []
    assert status_updates == []
    # No qa_enqueued_at marker write if we never intended to enqueue.
    assert patches == []


@pytest.mark.asyncio
async def test_qa_enqueued_at_already_set_is_idempotent():
    produced, status_updates, _, _ = await _run_hook(
        {
            "pr_number": 42, "classifier_tier": "full_workflow",
            "qa_enqueued_at": "2026-05-01T00:00:00Z",
        },
        stamped_mode="live",
    )
    assert produced == [], "Idempotent: must not re-enqueue."
    assert status_updates == []


@pytest.mark.asyncio
async def test_send_message_failure_clears_marker_emits_counter_swallows():
    produced, status_updates, patches, emits = await _run_hook(
        {"pr_number": 42, "classifier_tier": "full_workflow"},
        stamped_mode="live",
        send_raises=True,
    )
    # SendMessage failed → no enqueue successful.
    assert produced == []
    # Status must NOT transition to awaiting_qa (the enqueue never
    # succeeded).
    assert status_updates == []
    # Marker written then cleared so the next redelivery retries.
    assert patches[0]["qa_enqueued_at"] is not None
    assert patches[-1]["qa_enqueued_at"] is None
    # Counter emitted. No exception escaped (pytest would have failed).
    assert "qa.post_coding.enqueue_failed" in emits


@pytest.mark.asyncio
async def test_non_coding_agent_type_noops():
    """The hook fires only for agent_type='coding'."""
    with patch("ai_driven.queue.producer.TaskProducer") as producer_cls:
        await _enqueue_qa_best_effort(
            task_id=uuid.uuid4(),
            agent_type="review",
            result=_good_result(),
            stamped_mode="live",
            payload={},
        )
    producer_cls.assert_not_called()


@pytest.mark.asyncio
async def test_failed_result_noops():
    with patch("ai_driven.queue.producer.TaskProducer") as producer_cls:
        await _enqueue_qa_best_effort(
            task_id=uuid.uuid4(),
            agent_type="coding",
            result=SimpleNamespace(success=False),
            stamped_mode="live",
            payload={},
        )
    producer_cls.assert_not_called()


@pytest.mark.asyncio
async def test_missing_qa_queue_url_skips_gracefully(monkeypatch):
    """Pre-T22 deploys without AI_DRIVEN_SQS_QA_QUEUE_URL set: skip."""
    from ai_driven.config import get_settings
    monkeypatch.setenv("AI_DRIVEN_SQS_QA_QUEUE_URL", "")
    get_settings.cache_clear()

    with patch("ai_driven.queue.producer.TaskProducer") as producer_cls:
        await _enqueue_qa_best_effort(
            task_id=uuid.uuid4(),
            agent_type="coding",
            result=_good_result(),
            stamped_mode="live",
            payload={},
        )
    producer_cls.assert_not_called()
