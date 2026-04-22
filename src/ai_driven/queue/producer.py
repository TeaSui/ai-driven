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
        """Enqueue a task for async processing by an agent worker."""
        queue_url = self._queue_map.get(agent_type)
        if not queue_url:
            raise ValueError(f"No queue configured for agent type: {agent_type}")

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

        response = self._sqs.send_message(
            QueueUrl=queue_url,
            MessageBody=json.dumps(message),
            MessageGroupId=agent_type,
        )

        message_id = response["MessageId"]
        logger.info(
            "task_enqueued",
            extra={"sqs_message_id": message_id, "queue": agent_type},
        )
        return message_id
