"""Producer.enqueue_task raises when no SQS queue URL is configured.

The inline/debug fallback path has been removed — ``TaskProducer`` MUST
resolve a real queue URL for every agent type. A missing URL surfaces
as a loud ``RuntimeError`` at dispatch time instead of silently routing
through a legacy inline processor.
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
async def test_enqueue_qa_raises_when_queue_url_missing(monkeypatch):
    """QA with no queue URL configured → RuntimeError, no silent fallback."""
    monkeypatch.setenv("AI_DRIVEN_SQS_QA_QUEUE_URL", "")
    get_settings.cache_clear()

    producer = TaskProducer()
    with pytest.raises(RuntimeError, match="No SQS queue URL configured"):
        await producer.enqueue_task(
            task_id="t1", agent_type="qa", payload={},
        )


@pytest.mark.asyncio
async def test_enqueue_coding_raises_when_queue_url_missing(monkeypatch):
    """Same rule applies to every agent_type now, not just qa."""
    # Clear every possible queue URL so the lookup yields empty.
    for var in (
        "AI_DRIVEN_SQS_TASK_QUEUE_URL",
        "AI_DRIVEN_SQS_REVIEW_QUEUE_URL",
        "AI_DRIVEN_SQS_DEVOPS_QUEUE_URL",
        "AI_DRIVEN_SQS_ORCHESTRATOR_QUEUE_URL",
        "AI_DRIVEN_SQS_QA_QUEUE_URL",
    ):
        monkeypatch.setenv(var, "")
    monkeypatch.setenv("AI_DRIVEN_ORCHESTRATOR_MODE", "off")
    get_settings.cache_clear()

    producer = TaskProducer()
    with pytest.raises(RuntimeError, match="No SQS queue URL configured"):
        await producer.enqueue_task(
            task_id="t1", agent_type="coding", payload={"message": "hi"},
        )
