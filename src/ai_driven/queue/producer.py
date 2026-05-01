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
            "specialist": settings.sqs_specialists_queue_url,
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

        # T22 blocker-fix 1: QA never runs inline. The debug/inline fallback
        # below dispatches through `_process_task_inline` which only knows
        # the legacy agent types (coding/review/devops). A QA payload hitting
        # that path would crash. Fail loudly so a misconfigured env surfaces
        # immediately instead of silently dropping QA runs.
        if agent_type == "qa" and (settings.debug or not queue_url):
            raise RuntimeError(
                "QA worker cannot run inline/debug — configure "
                "AI_DRIVEN_SQS_QA_QUEUE_URL and disable debug mode."
            )

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

        try:
            response = self._sqs.send_message(
                QueueUrl=queue_url,
                MessageBody=json.dumps(message),
                MessageGroupId=effective_queue_type,
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
            extra={
                "sqs_message_id": message_id,
                "queue": effective_queue_type,
                "agent_type": agent_type,
            },
        )
        return message_id
