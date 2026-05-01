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

        # SHADOW: every coding-task submission also kicks off the orchestrator.
        # The orchestrator runs fire-and-forget via asyncio.create_task — do
        # NOT re-enqueue through enqueue_task (would silently drop the call if
        # the orchestrator queue URL is empty and inline-fallback doesn't know
        # the 'orchestrator' agent_type). See revision red-team block in
        # docs/plans/2026-04-29-orchestrator-build.md (line 32).
        if (
            agent_type == "coding"
            and settings.orchestrator_mode != "off"
        ):
            try:
                self._schedule_shadow_orchestrator(task_id, payload)
            except Exception:  # noqa: BLE001
                logger.exception(
                    "shadow orchestrator dispatch failed (non-fatal)"
                )

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

    def _schedule_shadow_orchestrator(
        self, task_id: str, payload: dict[str, Any],
    ) -> None:
        """Fire-and-forget direct invocation of OrchestratorAgent.

        We do NOT re-enter enqueue_task with agent_type='orchestrator' —
        that would either hit an empty queue URL or the inline-fallback
        dispatch in consumer.py, neither of which knows about the
        orchestrator agent factory. Instead we invoke orchestrate()
        directly inside the current event loop.
        """
        import asyncio

        from ai_driven.agents.orchestrator.agent import OrchestratorAgent

        shadow_payload = {**payload, "task_id": task_id}
        orch = OrchestratorAgent()
        asyncio.create_task(_run_shadow(orch, shadow_payload))


async def _run_shadow(orch: Any, payload: dict[str, Any]) -> None:
    """Run the orchestrator in the background, swallowing all errors.

    Module-level async helper (not a method) so asyncio.create_task has
    a clean coroutine object to schedule without dragging `self` through
    event-loop garbage collection semantics.
    """
    try:
        await orch.orchestrate(payload)
    except Exception:  # noqa: BLE001
        logger.exception(
            "shadow orchestrator orchestrate() crashed (non-fatal)"
        )
