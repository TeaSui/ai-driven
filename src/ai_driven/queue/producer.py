"""SQS message producer for task queuing."""

from __future__ import annotations

import json
import logging
from typing import Any

import boto3

from ai_driven.config import get_settings
from ai_driven.observability.trace_context import get_trace_context

logger = logging.getLogger(__name__)


class TaskProducer:
    """Publishes tasks to SQS queues for async processing."""

    def __init__(self) -> None:
        settings = get_settings()
        self._sqs = boto3.client("sqs", region_name=settings.aws_region)
        self._queue_map = {
            "coding": settings.sqs_task_queue_url,
            "review": settings.sqs_review_queue_url,
            "devops": settings.sqs_devops_queue_url,
        }

    async def enqueue_task(
        self, task_id: str, agent_type: str, payload: dict[str, Any]
    ) -> str:
        """Enqueue a task for async processing by an agent worker.

        In local/debug mode (or when SQS is unreachable) we fall back to
        processing the task inline via asyncio so webhook-driven features
        (F1/F2/F4) keep working without AWS. Returns a synthetic message id.
        """
        settings = get_settings()
        queue_url = self._queue_map.get(agent_type)

        # Local-dev / debug fallback: if SQS is unconfigured or the queue URL
        # is missing, process inline instead of crashing the webhook.
        if settings.debug or not queue_url:
            import asyncio
            from ai_driven.api.chat.router import _process_task_inline

            message = payload.get("message") or payload.get("title") or ""
            ctx = payload.get("context") or {}
            trace_ctx = get_trace_context()
            team_id = trace_ctx.get("team_id") or ""
            asyncio.create_task(
                _process_task_inline(task_id, agent_type, str(message), ctx, team_id=team_id)
            )
            logger.info(
                "task_enqueued_inline_fallback",
                extra={"task_id": task_id, "agent_type": agent_type,
                       "reason": "debug" if settings.debug else "no_queue_url"},
            )
            return f"inline-{task_id}"

        # Propagate trace context so the worker can continue the same trace
        ctx = get_trace_context()
        message = {
            "task_id": task_id,
            "agent_type": agent_type,
            "payload": payload,
            "trace_context": {
                "trace_id": ctx["trace_id"],
                "team_id": ctx["team_id"],
            },
        }

        try:
            response = self._sqs.send_message(
                QueueUrl=queue_url,
                MessageBody=json.dumps(message),
                MessageGroupId=agent_type,
            )
        except Exception as ex:
            # Last-resort fallback so a transient SQS outage doesn't drop events.
            logger.warning("SQS send failed (%s); processing inline as fallback", ex)
            import asyncio
            from ai_driven.api.chat.router import _process_task_inline
            trace_ctx = get_trace_context()
            team_id = trace_ctx.get("team_id") or ""
            asyncio.create_task(
                _process_task_inline(task_id, agent_type,
                                     str(payload.get("message") or payload.get("title") or ""),
                                     payload.get("context") or {}, team_id=team_id)
            )
            return f"inline-fallback-{task_id}"

        message_id = response["MessageId"]
        logger.info(
            "task_enqueued",
            extra={"sqs_message_id": message_id, "queue": agent_type},
        )
        return message_id
