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
        self._sqs = boto3.client(
            "sqs",
            region_name=settings.aws_region,
            endpoint_url=settings.sqs_endpoint_url or None,
        )
        self._queue_map = {
            "coding": settings.sqs_task_queue_url,
            "review": settings.sqs_review_queue_url,
            "devops": settings.sqs_devops_queue_url,
            "orchestrator": settings.sqs_orchestrator_queue_url,
            # T22: post-coding QA runs on its own queue (longer visibility
            # timeout, independent DLQ tuning). No rerouting — QA tasks
            # are enqueued directly by the Coding worker hook.
            "qa": settings.sqs_qa_queue_url,
        }

    async def enqueue_task(
        self,
        task_id: str,
        agent_type: str,
        payload: dict[str, Any],
        *,
        skip_orchestrator_routing: bool = False,
    ) -> str:
        """Enqueue a task for async processing by an agent worker.

        In local/debug mode (or when SQS is unreachable) we fall back to
        processing the task inline via asyncio so webhook-driven features
        (F1/F2/F4) keep working without AWS. Returns a synthetic message id.

        T20 routing: when a caller requests ``agent_type='coding'`` and the
        orchestrator is enabled (``orchestrator_mode != 'off'``), the task is
        enqueued to the **orchestrator queue** instead. The orchestrator
        worker runs classifier + specialists + writes the idempotency marker,
        then enqueues the same payload (unchanged) to the coding queue. When
        orchestrator_mode is 'off', coding-tasks go directly to the coding
        queue (legacy behaviour). This removes the fire-and-forget in-process
        shadow path entirely — see the T20 spec for details.
        """
        settings = get_settings()

        # T20: replace in-process shadow fan-out with orchestrator-queue
        # routing. Coding-tasks (when orchestrator is enabled) go to the
        # orchestrator queue; the orchestrator worker re-enqueues coding
        # downstream via TaskProducer with the SAME payload shape.
        #
        # `effective_queue_type` selects the destination queue. When we route
        # through the orchestrator queue, the message body's `agent_type` is
        # set to 'orchestrator' (so the consumer dispatches _OrchestratorWrapper,
        # not CodingAgent) and the ORIGINAL agent_type is preserved under
        # `downstream_agent_type` for the wrapper to re-enqueue after
        # orchestrate() completes.
        effective_queue_type = agent_type
        if (
            agent_type == "coding"
            and settings.orchestrator_mode != "off"
            and settings.sqs_orchestrator_queue_url
            and not skip_orchestrator_routing
        ):
            effective_queue_type = "orchestrator"

        queue_url = self._queue_map.get(effective_queue_type)

        # A missing queue URL is a configuration error — fail loudly rather
        # than silently routing through an inline fallback that used to
        # exist for local dev. The caller is responsible for ensuring all
        # relevant AI_DRIVEN_SQS_*_QUEUE_URL values are set in the
        # environment before accepting traffic.
        if not queue_url:
            raise RuntimeError(
                f"No SQS queue URL configured for queue_type={effective_queue_type!r} "
                f"(agent_type={agent_type!r}). Set the appropriate "
                f"AI_DRIVEN_SQS_*_QUEUE_URL environment variable."
            )

        # Propagate trace context so the worker can continue the same trace.
        # We ALSO stamp the current orchestrator_mode onto the message body so
        # a mid-deploy config flip ('shadow' → 'live' or vice versa) can't
        # change the halt semantics of a task that's already in flight. The
        # orchestrator worker MUST read this stamp rather than re-consulting
        # settings.orchestrator_mode at execute time. See T21 red-team finding
        # #3 (SQS message stamping).
        #
        # When routing a coding-task through the orchestrator queue, the
        # message body's agent_type MUST be 'orchestrator' — the consumer
        # dispatches on this field to pick _OrchestratorWrapper vs CodingAgent.
        # We preserve the original agent_type under `downstream_agent_type`
        # for the wrapper to re-enqueue after orchestrate() completes.
        ctx = get_trace_context()
        body_agent_type = (
            "orchestrator" if effective_queue_type == "orchestrator" else agent_type
        )
        message = {
            "task_id": task_id,
            "agent_type": body_agent_type,
            "downstream_agent_type": agent_type,
            "payload": payload,
            "orchestrator_mode": settings.orchestrator_mode,
            "trace_context": {
                "trace_id": ctx["trace_id"],
                "team_id": ctx["team_id"],
            },
        }

        # Let SQS send failures propagate — the caller logs and returns
        # the error to the user. A silent inline fallback here used to
        # hide transient outages; surfacing them makes the real failure
        # mode obvious (and retry-able).
        response = self._sqs.send_message(
            QueueUrl=queue_url,
            MessageBody=json.dumps(message),
            MessageGroupId=effective_queue_type,
        )

        message_id = response["MessageId"]
        logger.info(
            "task_enqueued",
            extra={
                "sqs_message_id": message_id,
                "queue": effective_queue_type,
                "agent_type": agent_type,
            },
        )
        return message_id
