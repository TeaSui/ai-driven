"""DataDog webhook handler for alert notifications."""

from __future__ import annotations

import logging
import uuid
from typing import Any

from fastapi import APIRouter, Depends, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.db.dynamo.models import WebhookEventStore
from ai_driven.db.postgres.models import Repository as RepositoryModel
from ai_driven.db.postgres.repository import TaskRepository
from ai_driven.db.postgres.session import get_session
from ai_driven.observability.trace_context import bind_trace, clear_trace
from ai_driven.queue.producer import TaskProducer

logger = logging.getLogger(__name__)
router = APIRouter()


@router.post("")
async def datadog_webhook(
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> dict[str, str]:
    payload: dict[str, Any] = await request.json()

    bind_trace()

    alert_title = payload.get("title", "Unknown alert")
    alert_type = payload.get("alert_type", "info")

    logger.info("datadog_webhook_received", extra={"alert_type": alert_type, "title": alert_title})

    if alert_type in ("error", "warning"):
        await _handle_alert(payload, session)

    clear_trace()
    return {"status": "accepted"}


async def _handle_alert(payload: dict[str, Any], session: AsyncSession) -> None:
    """Handle DataDog alert -- trigger DevOps assistant for triage."""
    alert_title = payload.get("title", "Unknown alert")
    alert_id = payload.get("id", str(uuid.uuid4()))
    tags = payload.get("tags", [])

    # Deduplication
    event_store = WebhookEventStore()
    event_id = f"datadog-alert-{alert_id}"
    if not event_store.record_event(event_id, "datadog", "alert", {"title": alert_title}):
        logger.info("Duplicate alert event, skipping", extra={"event_id": event_id})
        return

    # Find a repository from tags (tag format: "repo:owner/name") or use first active
    repo_full_name = None
    for tag in tags:
        if isinstance(tag, str) and tag.startswith("repo:"):
            repo_full_name = tag[5:]
            break

    db_repo = None
    if repo_full_name:
        result = await session.execute(
            select(RepositoryModel).where(
                RepositoryModel.full_name == repo_full_name,
                RepositoryModel.is_active.is_(True),
            )
        )
        db_repo = result.scalar_one_or_none()

    if not db_repo:
        # Fallback: get any active repo to determine team
        result = await session.execute(
            select(RepositoryModel).where(RepositoryModel.is_active.is_(True)).limit(1)
        )
        db_repo = result.scalar_one_or_none()

    if not db_repo:
        logger.warning("No repository found for DataDog alert", extra={"title": alert_title})
        return

    # Create devops task
    task_repo = TaskRepository(session)
    task = await task_repo.create(
        team_id=db_repo.team_id,
        repo_id=db_repo.id,
        agent_type="devops",
        title=f"DevOps Triage: {alert_title[:200]}",
        input_data={
            "alert_title": alert_title,
            "alert_type": payload.get("alert_type", ""),
            "alert_id": str(alert_id),
            "tags": tags,
            "body": payload.get("body", ""),
            "repo_full_name": db_repo.full_name,
            "triggered_by": "datadog_alert",
        },
    )
    await session.flush()

    logger.info("DevOps task created from DataDog alert", extra={"task_id": str(task.id), "alert": alert_title})

    producer = TaskProducer()
    await producer.enqueue_task(
        task_id=str(task.id),
        agent_type="devops",
        payload=task.input_data,
    )
