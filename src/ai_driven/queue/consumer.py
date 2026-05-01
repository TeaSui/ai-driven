"""SQS message consumer -- ECS worker that processes agent tasks."""

from __future__ import annotations

import asyncio
import json
import logging
import signal
from typing import Any

import boto3

from ai_driven.agents.coding.agent import CodingAgent
from ai_driven.agents.devops.agent import DevOpsAgent
from ai_driven.agents.reviewer.agent import ReviewAgent
from ai_driven.config import get_settings
from ai_driven.db.postgres.repository import TaskRepository
from ai_driven.db.postgres.session import async_session_factory
from ai_driven.jira.client import JiraClient
from ai_driven.observability.metrics import emit_agent_metrics
from ai_driven.observability.trace_context import bind_trace, bind_task, bind_team, clear_trace

logger = logging.getLogger(__name__)


class TaskConsumer:
    """Polls SQS queues and dispatches tasks to the appropriate agent."""

    def __init__(self, max_concurrent: int = 3) -> None:
        settings = get_settings()
        self._sqs = boto3.client(
            "sqs",
            region_name=settings.aws_region,
            endpoint_url=settings.sqs_endpoint_url or None,
        )
        self._queue_urls = [
            url
            for url in [
                settings.sqs_task_queue_url,
                settings.sqs_review_queue_url,
                settings.sqs_devops_queue_url,
                settings.sqs_orchestrator_queue_url,
                settings.sqs_specialists_queue_url,
            ]
            if url
        ]
        self._semaphore = asyncio.Semaphore(max_concurrent)
        self._running = True

    async def start(self) -> None:
        """Start polling all configured queues with graceful shutdown."""
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, self._shutdown)

        logger.info("Starting task consumer with %d queues", len(self._queue_urls))

        while self._running:
            for queue_url in self._queue_urls:
                if not self._running:
                    break
                await self._poll_queue(queue_url)
            await asyncio.sleep(1)

        logger.info("Task consumer stopped")

    def _shutdown(self) -> None:
        logger.info("Shutdown signal received, finishing current tasks...")
        self._running = False

    async def _poll_queue(self, queue_url: str) -> None:
        """Poll a single SQS queue for messages."""
        try:
            response = await asyncio.get_running_loop().run_in_executor(
                None,
                lambda: self._sqs.receive_message(
                    QueueUrl=queue_url,
                    MaxNumberOfMessages=1,
                    WaitTimeSeconds=5,
                    VisibilityTimeout=900,
                ),
            )

            for message in response.get("Messages", []):
                async with self._semaphore:
                    await self._process_message(message, queue_url)

        except Exception as e:
            logger.error("Error polling queue %s: %s", queue_url, e)

    async def _process_message(
        self, message: dict[str, Any], queue_url: str
    ) -> None:
        """Process a single SQS message with heartbeat and status tracking."""
        receipt_handle = message["ReceiptHandle"]
        body = json.loads(message["Body"])

        task_id = body["task_id"]
        agent_type = body["agent_type"]
        payload = body["payload"]

        # Restore trace context propagated from the API request
        trace_ctx = body.get("trace_context", {})
        bind_trace(trace_id=trace_ctx.get("trace_id") or None)
        bind_task(task_id, agent_type=agent_type)
        if trace_ctx.get("team_id"):
            bind_team(trace_ctx["team_id"])

        logger.info("task_processing_start", extra={"queue_url": queue_url})

        # Start heartbeat
        heartbeat_task = asyncio.create_task(
            self._heartbeat(queue_url, receipt_handle)
        )

        try:
            # Update task status to running
            async with async_session_factory() as session:
                task_repo = TaskRepository(session)
                await task_repo.update_status(task_id, "running")
                await session.commit()

            # Create and run agent.
            # CRITICAL: pass task_id as a kwarg. Without it the agent's
            # self._task_id stays None and _publish_event() silently
            # returns — no Redis pub/sub events ever fire and the UI's
            # "Thinking stream" WebSocket stays empty.
            agent = await self._create_agent(agent_type, payload)

            # Roll specialist cost into the CodingAgent per-task ceiling.
            # T-A2 cost-ceiling-compounding fix: specialist runs happen in
            # the orchestrator process and their USD cost is persisted on
            # task_artifacts.cost_usd. If we don't seed CodingAgent with
            # that sum, a task could spend (e.g.) $0.10 in specialists +
            # $2.80 in coding = $2.90 and only the $2.80 would count
            # against the $3.00 claude_max_cost_per_task_usd ceiling.
            # Applying the seed to every non-orchestrator agent covers
            # coding, review, and devops so the ceiling stays a hard
            # per-task cap regardless of which pipeline follows the
            # orchestrator. Skipped when agent_type == "orchestrator" so
            # the orchestrator itself (which doesn't accept initial_cost)
            # keeps working unchanged.
            run_kwargs: dict[str, Any] = {"task_id": str(task_id)}
            if agent_type != "orchestrator":
                initial_cost = await self._sum_specialist_cost_usd(task_id)
                if initial_cost > 0.0:
                    run_kwargs["initial_cost"] = initial_cost

            result = await agent.run(payload, **run_kwargs)

            # Update task status.
            # BaseAgent.run now accumulates a cache-aware cost on the result
            # (see _iteration_cost_usd). Use that when available; fall back
            # to the legacy uncached-only estimate for old callers that
            # construct AgentResult directly (mostly unit tests).
            input_tokens = result.conversation.total_input_tokens
            output_tokens = result.conversation.total_output_tokens
            if getattr(result, "cost_usd", 0.0):
                cost_usd = result.cost_usd
            else:
                cost_usd = (input_tokens * 3.0 / 1_000_000) + (output_tokens * 15.0 / 1_000_000)

            async with async_session_factory() as session:
                task_repo = TaskRepository(session)
                status = "completed" if result.success else "failed"

                # Merge the run-result into output_data instead of overwriting
                # it. CodingAgent writes `pr_number` + `pr_url` mid-run (via
                # _persist_pr_ref on successful github.create_pr) and an
                # overwrite here silently drops those keys, leaving QA with
                # no PR reference to validate against.
                run_result_patch = {
                    "output": result.output[:5000],
                    "tool_calls": result.tool_calls,
                    "iterations": result.iterations,
                    "error": result.error,
                }
                await task_repo.update_output_data(task_id, run_result_patch)
                await task_repo.update_status(
                    task_id,
                    status,
                    total_tokens=result.total_tokens,
                    total_cost_usd=round(cost_usd, 4),
                    iterations=result.iterations,
                )
                await session.commit()

            if result.success:
                logger.info(
                    "task_completed",
                    extra={
                        "iterations": result.iterations,
                        "tool_calls": result.tool_calls,
                        "total_tokens": result.total_tokens,
                        "duration_seconds": round(result.duration_seconds, 2),
                    },
                )
            else:
                logger.error("task_failed", extra={"error": result.error})

            # Delete message on success (or permanent failure)
            await asyncio.get_running_loop().run_in_executor(
                None,
                lambda: self._sqs.delete_message(
                    QueueUrl=queue_url, ReceiptHandle=receipt_handle
                ),
            )

        except Exception as e:
            logger.error("task_processing_error", extra={"error": str(e)}, exc_info=True)
            # Update task status to failed
            try:
                async with async_session_factory() as session:
                    task_repo = TaskRepository(session)
                    await task_repo.update_status(
                        task_id, "failed", output_data={"error": str(e)}
                    )
                    await session.commit()
            except Exception:
                logger.error("task_status_update_failed")
        finally:
            heartbeat_task.cancel()
            # CRITICAL: decrement the per-team concurrent-task counter so
            # subsequent submissions aren't silently 429'd by a stale count.
            # send_message() in chat/router.py increments at dispatch; the
            # counterpart decrement MUST run for every terminal path
            # (success, handled failure, raised exception, task cancel).
            # Running in `finally` covers all four.
            team_id_for_decrement = (trace_ctx or {}).get("team_id") or ""
            if team_id_for_decrement:
                try:
                    from ai_driven.auth.rate_limiter import RateLimiter
                    await RateLimiter().decrement_concurrent(
                        team_id_for_decrement
                    )
                except Exception:
                    # Never let the counter decrement crash the worker —
                    # a leaked slot is recoverable (sweeper will reconcile);
                    # a crashed worker leaks EVERY slot in flight.
                    logger.warning(
                        "decrement_concurrent_failed",
                        extra={"team_id": team_id_for_decrement},
                        exc_info=True,
                    )
            clear_trace()

    async def _heartbeat(
        self, queue_url: str, receipt_handle: str, interval: int = 300
    ) -> None:
        """Extend SQS visibility timeout periodically for long-running tasks."""
        while True:
            await asyncio.sleep(interval)
            try:
                await asyncio.get_running_loop().run_in_executor(
                    None,
                    lambda: self._sqs.change_message_visibility(
                        QueueUrl=queue_url,
                        ReceiptHandle=receipt_handle,
                        VisibilityTimeout=900,
                    ),
                )
                logger.debug(
                    "Heartbeat: extended visibility for %s...",
                    receipt_handle[:20],
                )
            except Exception as e:
                logger.warning("Heartbeat failed: %s", e)

    @staticmethod
    async def _sum_specialist_cost_usd(task_id: Any) -> float:
        """Return the sum of task_artifacts.cost_usd for this task.

        Used by ``_process_message`` to pre-seed ``BaseAgent.run``'s
        ``initial_cost`` so the ``claude_max_cost_per_task_usd`` ceiling
        applies to the COMBINED orchestration run (specialists + coding +
        review), not per-pipeline-stage. Returns 0.0 for tasks with no
        artifacts yet (fast-path, non-orchestrated pipelines) and on any
        DB error — we'd rather under-count than crash the worker.
        """
        import uuid as _uuid
        from sqlalchemy import select
        from ai_driven.db.postgres.models import TaskArtifact

        try:
            tid = task_id if isinstance(task_id, _uuid.UUID) else _uuid.UUID(str(task_id))
        except (ValueError, TypeError):
            return 0.0

        try:
            async with async_session_factory() as session:
                res = await session.execute(
                    select(TaskArtifact.cost_usd).where(
                        TaskArtifact.task_id == tid
                    )
                )
                rows = res.scalars().all()
                return float(sum((c or 0 for c in rows), start=0))
        except Exception:  # noqa: BLE001
            logger.warning(
                "specialist_cost_sum_failed for task %s; seeding initial_cost=0",
                task_id,
                exc_info=True,
            )
            return 0.0

    @staticmethod
    async def _lookup_repo_scm_type(repo_full_name: str) -> str:
        """Return 'github' or 'bitbucket' for a registered repo.

        Defaults to 'github' when the repo isn't registered (legacy tasks
        submitted before repo registration existed).
        """
        from ai_driven.db.postgres.models import Repository as RepoRow
        from ai_driven.db.postgres.session import async_session_factory
        from sqlalchemy import select

        try:
            async with async_session_factory() as session:
                res = await session.execute(
                    select(RepoRow.scm_type).where(
                        RepoRow.full_name == repo_full_name
                    )
                )
                row = res.scalar_one_or_none()
                return row or "github"
        except Exception as ex:
            logger.warning(
                "scm_type lookup failed for %s: %s — defaulting to github",
                repo_full_name, ex,
            )
            return "github"

    @staticmethod
    async def _create_agent(agent_type: str, payload: dict[str, Any]) -> Any:
        """Factory method to create the appropriate agent with real clients."""
        settings = get_settings()

        # Create Jira client if configured
        jira_client = None
        if settings.jira_base_url and settings.jira_api_token:
            jira_client = JiraClient()

        # Create SCM client based on the registered repo's scm_type, not the
        # first-non-empty credential. Looking up by repo_full_name means a
        # Bitbucket-registered repo gets BitbucketClient even when
        # GITHUB_TOKEN is also set (and vice versa).
        scm_client = None
        repo_full_name = payload.get("repo_full_name", "")
        if repo_full_name:
            scm_type = await TaskConsumer._lookup_repo_scm_type(repo_full_name)
            # Stamp the resolved provider into the payload so the coding-agent
            # prompt can render the correct PR tool hint (github.create_pr vs
            # bitbucket.create_pr) and skip jira.get_issue when there's no key.
            payload["scm_type"] = scm_type
            if scm_type == "bitbucket" and settings.bitbucket_username:
                from ai_driven.scm.bitbucket_client import BitbucketClient

                scm_client = BitbucketClient(
                    username=settings.bitbucket_username,
                    app_password=settings.bitbucket_app_password,
                )
            elif settings.github_token:
                from ai_driven.scm.github_client import GitHubClient

                scm_client = GitHubClient(token=settings.github_token)

        agents: dict[str, Any] = {
            "coding": lambda: CodingAgent(
                jira_client=jira_client, scm_client=scm_client
            ),
            "review": lambda: ReviewAgent(scm_client=scm_client),
            "devops": lambda: DevOpsAgent(jira_client=jira_client),
            "orchestrator": lambda: _OrchestratorWrapper(),
        }
        factory = agents.get(agent_type)
        if not factory:
            raise ValueError(f"Unknown agent type: {agent_type}")
        return factory()


def _build_orchestrate_payload(payload: dict[str, Any], task_id: Any) -> dict[str, Any]:
    """Normalize a flat consumer payload into orchestrate()'s nested shape.

    Accepts either:
    - nested: ``{ticket: {...}, repo: {...}}`` (already orchestrate-shaped)
    - flat:   ``{message, jira_key, repo_full_name, scm_type, labels, ...}``

    Returns the orchestrate() input (``task_id + ticket + repo``). Shared by
    the SQS worker wrapper and the debug-mode inline path so both routes
    build identical inputs.
    """
    ticket = payload.get("context", {}).get("ticket") or payload.get("ticket") or {
        "key": payload.get("jira_key", ""),
        "summary": payload.get("title") or payload.get("message", ""),
        "description": payload.get("message", ""),
        "labels": payload.get("labels", []),
        "acceptance_criteria": payload.get("acceptance_criteria", []),
    }
    repo = payload.get("context", {}).get("repo") or payload.get("repo") or {
        "full_name": payload.get("repo_full_name", ""),
        "scm_type": payload.get("scm_type", "github"),
        "default_branch": payload.get("default_branch", "main"),
    }
    return {"task_id": task_id, "ticket": ticket, "repo": repo}


class _OrchestratorWrapper:
    """Adapter exposing OrchestratorAgent.orchestrate() through `.run()`.

    T20 makes this the primary dispatch path: the SQS orchestrator worker
    dequeues an orchestrator-task, calls `.run(payload)` here, which:

      1. Reads the idempotency marker (``Task.output_data.orchestrator_completed_at``)
         — if present, skips orchestrate() entirely (crash-recovery path:
         at-least-once SQS delivery re-runs a task that already produced
         artifacts; we MUST NOT re-spend Claude tokens on specialists).
      2. Otherwise runs `orchestrate()` (classifier + specialists + artifacts).
      3. Writes the marker via `TaskRepository.update_output_data`
         BEFORE enqueueing coding — so a crash between steps 3 and 4 still
         lets us resume safely.
      4. Enqueues the coding-task (agent_type='coding') via
         ``TaskProducer().enqueue_task`` with the ORIGINAL payload, so
         downstream CodingAgent sees the same shape the user sent.

    T20 is SHADOW-MODE ONLY — `halted_on` is logged but never halts the
    pipeline. T21 adds strict gating + `blocked` status.
    """

    def __init__(self) -> None:
        from ai_driven.agents.orchestrator.agent import OrchestratorAgent
        self._inner = OrchestratorAgent()

    async def run(self, payload: dict[str, Any], **run_kwargs: Any) -> Any:
        from datetime import datetime, timezone
        import uuid as _uuid

        from ai_driven.agents.base import AgentResult
        from ai_driven.claude.conversation import Conversation

        # run_kwargs (task_id kwarg from TaskConsumer) is accepted for
        # signature parity with other agents. We prefer payload['task_id']
        # when present (nested orchestrator shape) but fall back to the
        # TaskConsumer-provided kwarg.
        task_id_str = str(payload.get("task_id") or run_kwargs.get("task_id") or "")
        task_uuid: _uuid.UUID | None = None
        try:
            task_uuid = _uuid.UUID(task_id_str) if task_id_str else None
        except (ValueError, TypeError):
            task_uuid = None

        # 1. Idempotency check: skip orchestrate() if marker already written.
        existing_output: dict[str, Any] = {}
        already_ran = False
        if task_uuid is not None:
            try:
                async with async_session_factory() as session:
                    repo = TaskRepository(session)
                    task_row = await repo.get_by_id(task_uuid)
                    if task_row is not None:
                        existing_output = dict(task_row.output_data or {})
                        if existing_output.get("orchestrator_completed_at"):
                            already_ran = True
            except Exception:  # noqa: BLE001
                logger.warning(
                    "orchestrator_idempotency_lookup_failed",
                    extra={"task_id": task_id_str},
                    exc_info=True,
                )

        result: dict[str, Any]
        if already_ran:
            logger.info(
                "orchestrator_skip_idempotent",
                extra={
                    "task_id": task_id_str,
                    "completed_at": existing_output.get("orchestrator_completed_at"),
                },
            )
            # Synthesize a minimal result for the AgentResult below. We do
            # NOT re-load the full artifact set — the coding worker will
                # load prior_artifacts from the DB itself (T19 behaviour).
            result = {
                "tier": existing_output.get("classifier_tier"),
                "specialists_run": [],
                "halted_on": existing_output.get("orchestrator_halted_on"),
                "artifacts": {},
                "total_tokens": {"input": 0, "output": 0},
                "idempotent_replay": True,
            }
        else:
            # 2. Run orchestrate(). Any exception here transitions the task
            #    to failed with failure_reason='orchestrator_error' and
            #    re-raises so SQS redelivers (DLQ catches 3rd failure).
            orchestrate_input = _build_orchestrate_payload(payload, task_id_str)
            try:
                result = await self._inner.orchestrate(orchestrate_input)
            except Exception as exc:  # noqa: BLE001
                logger.error(
                    "orchestrator_run_failed",
                    extra={"task_id": task_id_str},
                    exc_info=True,
                )
                if task_uuid is not None:
                    try:
                        async with async_session_factory() as session:
                            repo = TaskRepository(session)
                            await repo.update_output_data(
                                task_uuid,
                                {"failure_reason": "orchestrator_error",
                                 "error": str(exc)[:500]},
                            )
                            await repo.update_status(task_uuid, "failed")
                            await session.commit()
                    except Exception:  # noqa: BLE001
                        logger.error(
                            "orchestrator_failure_persist_failed",
                            exc_info=True,
                        )
                raise

            # 3. Write the idempotency marker BEFORE enqueueing coding-task.
            #    If we crash between 3 and 4, SQS redelivery hits the
            #    already_ran branch and skips orchestrate() — cheap replay.
            if task_uuid is not None:
                marker_patch: dict[str, Any] = {
                    "orchestrator_completed_at": datetime.now(timezone.utc).isoformat(),
                    "classifier_tier": result.get("tier"),
                }
                halted_on = result.get("halted_on")
                if halted_on:
                    # T20: log specialist blocks but do NOT halt. T21 adds
                    # strict gating + 'blocked' status migration.
                    logger.info(
                        "orchestrator_halted_on_specialist",
                        extra={"task_id": task_id_str, "halted_on": halted_on},
                    )
                    marker_patch["orchestrator_halted_on"] = halted_on
                try:
                    async with async_session_factory() as session:
                        repo = TaskRepository(session)
                        await repo.update_output_data(task_uuid, marker_patch)
                        await session.commit()
                except Exception:  # noqa: BLE001
                    logger.error(
                        "orchestrator_marker_persist_failed",
                        extra={"task_id": task_id_str},
                        exc_info=True,
                    )

        # 4. Enqueue the coding-task with the ORIGINAL payload. We import
        #    locally to avoid a circular import between queue.producer and
        #    queue.consumer at module load. `skip_orchestrator_routing=True`
        #    prevents the producer from re-routing this back to the
        #    orchestrator queue (which would cause an infinite loop since
        #    orchestrator_mode is still 'shadow'/'live' at this point).
        if task_id_str:
            try:
                from ai_driven.queue.producer import TaskProducer

                await TaskProducer().enqueue_task(
                    task_id=task_id_str,
                    agent_type="coding",
                    payload=payload,
                    skip_orchestrator_routing=True,
                )
            except Exception:  # noqa: BLE001
                logger.error(
                    "orchestrator_coding_enqueue_failed",
                    extra={"task_id": task_id_str},
                    exc_info=True,
                )
                raise

        # Surface token usage via Conversation so TaskConsumer.total_tokens
        # reflects the orchestrator's real spend.
        conv = Conversation()
        totals = result.get("total_tokens") or {}
        conv.total_input_tokens = int(totals.get("input", 0) or 0)
        conv.total_output_tokens = int(totals.get("output", 0) or 0)
        return AgentResult(
            success=True,
            output=json.dumps(result),
            conversation=conv,
            tool_calls=0,
            iterations=0,
            duration_seconds=0.0,
        )
