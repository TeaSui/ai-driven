"""T22 blocker-fix 1: QA must never fall through to the inline path.

Producer.enqueue_task explicitly raises RuntimeError when the agent_type
is 'qa' AND the debug/inline fallback would be taken (debug mode OR
missing sqs_qa_queue_url). The inline path only knows the legacy agent
types and would crash on a QA payload otherwise.
"""
from __future__ import annotations

import pytest

from ai_driven.config import get_settings
from ai_driven.queue.producer import TaskProducer


@pytest.fixture(autouse=True)
def _clear_cache():
    """Settings is lru_cached; force a fresh read per test."""
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.mark.asyncio
async def test_enqueue_qa_raises_in_debug_mode(monkeypatch):
    monkeypatch.setenv("AI_DRIVEN_DEBUG", "true")
    # Provide a queue URL so the fail path can ONLY be the debug branch.
    monkeypatch.setenv("AI_DRIVEN_SQS_QA_QUEUE_URL",
                        "https://sqs/local/ai-driven-qa-tasks")
    get_settings.cache_clear()

    producer = TaskProducer()
    with pytest.raises(RuntimeError, match="QA worker cannot run inline"):
        await producer.enqueue_task(
            task_id="t1", agent_type="qa", payload={},
        )


@pytest.mark.asyncio
async def test_enqueue_qa_raises_when_queue_url_missing(monkeypatch):
    monkeypatch.setenv("AI_DRIVEN_DEBUG", "false")
    monkeypatch.setenv("AI_DRIVEN_SQS_QA_QUEUE_URL", "")
    get_settings.cache_clear()

    producer = TaskProducer()
    with pytest.raises(RuntimeError, match="QA worker cannot run inline"):
        await producer.enqueue_task(
            task_id="t1", agent_type="qa", payload={},
        )


@pytest.mark.asyncio
async def test_enqueue_non_qa_agent_still_uses_inline_fallback(monkeypatch):
    """Regression guard: the new raise branch must ONLY fire for qa."""
    monkeypatch.setenv("AI_DRIVEN_DEBUG", "true")
    monkeypatch.setenv("AI_DRIVEN_SQS_QA_QUEUE_URL", "")
    get_settings.cache_clear()

    producer = TaskProducer()
    # Any non-qa agent must still fall through to the inline path without
    # raising. We patch _process_task_inline to short-circuit; we only care
    # that RuntimeError is NOT raised.
    import ai_driven.api.chat.router as router_mod

    called = {}

    async def fake_inline(*args, **kwargs):
        called["yes"] = True

    monkeypatch.setattr(router_mod, "_process_task_inline", fake_inline)
    result = await producer.enqueue_task(
        task_id="t1", agent_type="coding", payload={"message": "hi"},
    )
    assert result.startswith("inline-")
