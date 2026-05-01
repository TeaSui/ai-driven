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
                # T22: poll the QA post-coding queue too.
                settings.sqs_qa_queue_url,
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
        # T21: orchestrator_mode is stamped onto the SQS message by the
        # producer so a mid-deploy config flip ('shadow'↔'live') can't change
        # halt semantics of a task that's already in flight. Default to
        # 'shadow' for backward compatibility with any in-flight messages
        # enqueued before T21 rolled out.
        stamped_orchestrator_mode: str = body.get("orchestrator_mode", "shadow")

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
            else:
                # T21: the orchestrator wrapper's halt predicate reads the
                # stamped mode from the SQS message body, not live settings.
                run_kwargs["orchestrator_mode"] = stamped_orchestrator_mode
            if agent_type == "qa":
                # T23: the QA wrapper's gate decision is mode-scoped -- live
                # mode transitions status, shadow mode only writes
                # observability markers. Same stamp source as the
                # orchestrator (SQS body) to preserve cross-deploy
                # flip-safety.
                run_kwargs["orchestrator_mode"] = stamped_orchestrator_mode

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

            # T22: enqueue post-coding QA (best-effort). Wrapped in its
            # OWN try/except so a QA-enqueue failure NEVER propagates to
            # the outer _process_message except — that would flip a
            # completed coding run to status='failed' and clobber
            # pr_number via output_data={"error":...}. See T22 brief
            # red-team blocker #1.
            try:
                await _enqueue_qa_best_effort(
                    task_id=task_id,
                    agent_type=agent_type,
                    result=result,
                    stamped_mode=stamped_orchestrator_mode,
                    payload=payload,
                )
            except Exception:  # noqa: BLE001
                logger.exception(
                    "qa_enqueue_outer_guard_tripped",
                    extra={"task_id": task_id},
                )

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
            # T22: post-coding QA validates the real PR diff + prior
            # artifacts. Wrapper owns idempotency + shared-session
            # persistence (artifact + output_data markers in ONE commit).
            "qa": lambda: _QaPostCodingWrapper(scm_client=scm_client),
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


def _should_halt_pipeline(
    result: dict[str, Any], stamped_mode: str
) -> bool:
    """T21 strict-gating halt predicate.

    Halt the pipeline (set status=blocked, skip coding-task enqueue) ONLY
    when ALL of:

    - the message was enqueued under ``orchestrator_mode='live'`` (shadow
      mode runs specialists for telemetry but never blocks),
    - orchestrate() reported a ``halted_on`` role, AND
    - that role's verdict is specifically ``'block'`` (NOT ``'error'`` —
      strict-error mode sets ``halted_on`` on Bedrock flakes and we do
      not want silent flake-to-blocked conversion), AND
    - the halting role is NOT ``'qa'`` — routing.py currently runs QA
      BEFORE CodingAgent as ``final_gate``. Pre-coding QA has no PR to
      validate so it would block every full_workflow/multi_domain task.
      T22 will split pre/post QA; until then we exclude it here.

    See pre-baked red-team findings #1 (QA pre-coding false-halt) and
    #2 (block-vs-error disambiguation) in the T21 task brief.
    """
    if stamped_mode != "live":
        return False
    halted_on = result.get("halted_on")
    if not halted_on:
        return False
    if halted_on == "qa":
        return False
    artifacts = result.get("artifacts") or {}
    return artifacts.get(halted_on) == "block"


def _extract_blocking_reasons(role: str, artifact: Any) -> list[str]:
    """Return a non-empty list of human-readable reasons a specialist blocked.

    Dispatch on role because each specialist puts its blocker-list under a
    different payload key:

    - business_analyst: ``payload.blocking_questions`` (list[str])
    - security:         ``payload.blocking_concerns`` (list[str])
    - stack_analyst, tech_lead, ux_designer, api_test: no blocker field;
      fall back to ``[artifact.reasoning]``.

    When the role-specific key is present but empty, we still fall back to
    reasoning — an empty list tells an operator nothing. When both the key
    and reasoning are empty we emit a generic sentinel so UI consumers can
    rely on a non-empty list.

    See pre-baked red-team finding #4 (per-role blocking_reasons extractor)
    in the T21 task brief.
    """
    payload: dict[str, Any] = {}
    reasoning: str | None = None
    if artifact is not None:
        payload = getattr(artifact, "payload", None) or {}
        reasoning = getattr(artifact, "reasoning", None)

    candidates: list[str] = []
    if role == "business_analyst":
        candidates = [str(q) for q in (payload.get("blocking_questions") or []) if q]
    elif role == "security":
        candidates = [str(c) for c in (payload.get("blocking_concerns") or []) if c]
    # Other roles have no dedicated blocker field — fall through to reasoning.

    if candidates:
        return candidates
    if reasoning:
        return [str(reasoning)]
    return [f"{role} specialist returned verdict=block without a reason"]


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

    T21 adds strict gating: in live mode, a specialist returning
    verdict=block transitions the task to status='blocked' and skips
    the coding-task enqueue. In shadow mode, halts are still logged but
    coding-task is enqueued anyway — so existing flows are unchanged
    until an operator flips ORCHESTRATOR_MODE=live.
    """

    def __init__(self) -> None:
        from ai_driven.agents.orchestrator.agent import OrchestratorAgent
        self._inner = OrchestratorAgent()

    @staticmethod
    async def _load_blocker_artifact(
        task_uuid: Any, role: str,
    ) -> tuple[Any, str | None]:
        """Return (Artifact-like, artifact_row_id_str) for (task_id, role).

        Used by the T21 strict-gating halt path to capture the row id that
        caused the block (for downstream UI deep-linking) and to pull the
        payload out for _extract_blocking_reasons. Returns (None, None) on
        any DB error — the caller still writes status=blocked with a
        generic reason, which is better than crashing the worker.
        """
        from sqlalchemy import select
        from ai_driven.db.postgres.models import TaskArtifact

        try:
            async with async_session_factory() as session:
                res = await session.execute(
                    select(TaskArtifact).where(
                        TaskArtifact.task_id == task_uuid,
                        TaskArtifact.role == role,
                    )
                )
                row = res.scalar_one_or_none()
                if row is None:
                    return None, None
                # Duck-type: _extract_blocking_reasons reads .payload and
                # .reasoning — the ORM row exposes both so we pass it
                # through without constructing a full Artifact dataclass.
                return row, str(row.id)
        except Exception:  # noqa: BLE001
            logger.warning(
                "blocker_artifact_lookup_failed",
                extra={"task_id": str(task_uuid), "role": role},
                exc_info=True,
            )
            return None, None

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
        # T21: the TaskConsumer passes orchestrator_mode through from the
        # SQS message stamp (see producer). We default to 'shadow' so a
        # direct caller (e.g. the debug inline path) that doesn't stamp
        # gets the pre-T21 behaviour.
        stamped_mode: str = str(run_kwargs.get("orchestrator_mode") or "shadow")
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
                    logger.info(
                        "orchestrator_halted_on_specialist",
                        extra={"task_id": task_id_str, "halted_on": halted_on,
                               "stamped_mode": stamped_mode},
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

        # T21: strict-gating halt check. Only evaluated in live mode; shadow
        # mode pre-T21 behaviour (always enqueue coding) is preserved.
        # Pre-coding QA is excluded — see _should_halt_pipeline docstring.
        if _should_halt_pipeline(result, stamped_mode) and task_uuid is not None:
            halted_on = str(result.get("halted_on"))
            blocker_artifact, blocker_id = await self._load_blocker_artifact(
                task_uuid, halted_on,
            )
            reasons = _extract_blocking_reasons(halted_on, blocker_artifact)
            logger.warning(
                "orchestrator_strict_halt",
                extra={
                    "task_id": task_id_str,
                    "halted_on": halted_on,
                    "blocker_artifact_id": blocker_id,
                    "reason_count": len(reasons),
                },
            )
            try:
                async with async_session_factory() as session:
                    repo = TaskRepository(session)
                    await repo.update_output_data(
                        task_uuid,
                        {
                            "blocked_by": halted_on,
                            "blocking_reasons": reasons,
                            "blocker_artifact_id": blocker_id,
                        },
                    )
                    await repo.update_status(task_uuid, "blocked")
                    await session.commit()
            except Exception:  # noqa: BLE001
                logger.error(
                    "orchestrator_block_persist_failed",
                    extra={"task_id": task_id_str},
                    exc_info=True,
                )
                # Fall through to raise so SQS redelivers; on a DB-only
                # failure a redelivery will hit the idempotent-replay branch
                # and re-attempt the block persistence.
                raise
            # Return early — do NOT enqueue coding-task. The
            # orchestrator_completed_at marker was already persisted above
            # so redelivery won't re-run orchestrate().
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


async def _enqueue_qa_best_effort(
    *,
    task_id: Any,
    agent_type: str,
    result: Any,
    stamped_mode: str,
    payload: dict[str, Any],
) -> None:
    """Best-effort post-coding QA enqueue (T22).

    Invoked by ``_process_message`` immediately after a coding-task has
    transitioned to ``status='completed'``. If the tier is
    full_workflow / multi_domain AND a ``pr_number`` was persisted AND
    ``sqs_qa_queue_url`` is configured, enqueue a qa-task via
    :class:`TaskProducer`.

    Safety invariants (T22 red-team blockers 1 + 2 + M2):

    * Exceptions are logged + counter'd + swallowed — a QA-enqueue
      failure must never flip a completed coding task back to ``failed``.
      The caller also wraps this in its own try/except as belt-and-braces.
    * ``qa_enqueued_at`` is written BEFORE ``SendMessage`` so a crash
      between marker-set and send is recoverable (marker cleared on
      failure → next redelivery retries). Read-modify-write semantics
      keep replays idempotent.
    * Status transition to ``awaiting_qa`` is gated on
      ``stamped_mode == "live"``. Shadow mode STILL enqueues QA (for
      telemetry) but leaves status at ``completed`` so admin dashboards
      that filter on ``status == "completed"`` keep working across T22-
      only deploys.
    """
    from datetime import datetime, timezone

    from ai_driven.config import get_settings
    from ai_driven.observability.metrics import emit_counter

    if agent_type != "coding":
        return
    if not getattr(result, "success", False):
        return

    settings = get_settings()
    if not settings.sqs_qa_queue_url:
        # Graceful skip: deploys pre-dating the T22 infra still work.
        return

    # Re-read output_data AFTER the coding-run's status=completed commit
    # so ``pr_number`` and ``classifier_tier`` reflect post-coding state
    # (not the stale top-of-_process_message snapshot).
    existing_output: dict[str, Any] = {}
    try:
        async with async_session_factory() as session:
            task_row = await TaskRepository(session).get_by_id(task_id)
            if task_row is not None:
                existing_output = dict(task_row.output_data or {})
    except Exception:  # noqa: BLE001
        logger.warning(
            "qa_enqueue_output_data_load_failed",
            extra={"task_id": str(task_id)},
            exc_info=True,
        )
        return

    tier = existing_output.get("classifier_tier")
    pr_number = existing_output.get("pr_number")

    if tier is None:
        emit_counter("qa.post_coding.classifier_tier_missing")
        logger.warning(
            "qa_enqueue_skipped_tier_missing",
            extra={"task_id": str(task_id)},
        )
        return
    if tier not in {"full_workflow", "multi_domain"}:
        return  # fast_path doesn't run QA.
    if not pr_number:
        # Coding run succeeded but never persisted a pr_number — typical
        # for non-PR-emitting tickets or a T19b regression. The T22
        # wrapper's Sev-1 counter catches this too; here we just skip.
        logger.info(
            "qa_enqueue_skipped_no_pr_number",
            extra={"task_id": str(task_id)},
        )
        return
    if existing_output.get("qa_enqueued_at"):
        # Idempotent: a prior partial attempt already set the marker.
        return

    # T22 blocker-fix 2: write marker BEFORE SendMessage. If SendMessage
    # fails we clear it so the next SQS redelivery retries cleanly.
    try:
        async with async_session_factory() as session:
            await TaskRepository(session).update_output_data(
                task_id,
                {"qa_enqueued_at": datetime.now(timezone.utc).isoformat()},
            )
            await session.commit()
    except Exception:  # noqa: BLE001
        logger.warning(
            "qa_enqueue_marker_persist_failed",
            extra={"task_id": str(task_id)},
            exc_info=True,
        )
        emit_counter("qa.post_coding.enqueue_failed")
        return

    try:
        from ai_driven.queue.producer import TaskProducer

        await TaskProducer().enqueue_task(
            task_id=str(task_id),
            agent_type="qa",
            payload=payload,
            skip_orchestrator_routing=True,
        )
    except Exception:  # noqa: BLE001
        logger.error(
            "qa_enqueue_failed",
            extra={"task_id": str(task_id)},
            exc_info=True,
        )
        emit_counter("qa.post_coding.enqueue_failed")
        # Clear the marker so the next redelivery retries.
        try:
            async with async_session_factory() as session:
                await TaskRepository(session).update_output_data(
                    task_id, {"qa_enqueued_at": None}
                )
                await session.commit()
        except Exception:  # noqa: BLE001
            logger.warning(
                "qa_enqueue_marker_clear_failed",
                extra={"task_id": str(task_id)},
                exc_info=True,
            )
        # Swallow — coding succeeded, don't flip the outer task to
        # failed. Stuck tasks without QA are recoverable via T24's
        # reaper; a failed task with clobbered pr_number is not.
        return

    # T22 M2: only live mode transitions to awaiting_qa. Shadow mode
    # keeps status=completed so admin dashboards / webhooks that treat
    # completed as terminal continue to work during the T22-only
    # window (before T23 lands).
    if stamped_mode == "live":
        try:
            async with async_session_factory() as session:
                await TaskRepository(session).update_status(
                    task_id, "awaiting_qa"
                )
                await session.commit()
        except Exception:  # noqa: BLE001
            logger.warning(
                "qa_enqueue_status_transition_failed",
                extra={"task_id": str(task_id)},
                exc_info=True,
            )
            # Don't swallow here? We already have qa_enqueued_at set +
            # the QA message in flight. Leaving status=completed is a
            # soft degradation, not a data-loss bug. Swallow.


class _QaPostCodingWrapper:
    """Post-coding QA adapter exposed through ``.run()`` like a regular agent.

    Dispatched by :meth:`TaskConsumer._create_agent` when a qa-task is
    dequeued from ``sqs_qa_queue_url``. The wrapper:

      1. Idempotency check — if ``output_data.qa_completed_at`` is set,
         short-circuit. SQS at-least-once delivery means a worker crash
         between artifact-persist and sqs_delete re-delivers the task;
         QA must not re-spend Claude tokens on a replay.
      2. Missing-pr_number guard — if ``output_data.pr_number`` is not
         set (CodingAgent T19b regression), emit a Sev-1 counter and
         persist an error artifact so the row is marked
         ``qa_completed_at`` and a T24 reaper can resolve it.
      3. Load prior artifacts (BA + Security at minimum) and run
         :class:`QaSpecialist` with ``pr_ref`` populated. The specialist
         prompt switches to post-coding mode (v2 payload).
      4. Persist the artifact + ``output_data.qa_*`` markers in ONE
         shared session commit so a partial write never leaves the
         task in a half-QA state.
      5. Schema-drift belt-and-braces — if the specialist returned a
         payload without ``schema_version=='v2'``, emit
         ``qa.post_coding.schema_drift`` and force ``verdict='error'``
         so T23 treats it as lenient-pass, not a clean verdict.

    The wrapper writes ONLY these keys to ``output_data``:
    ``qa_completed_at``, ``qa_verdict``, ``qa_artifact_id``,
    ``qa_reasoning``. It MUST NOT write ``output``, ``tool_calls``,
    ``iterations``, or ``error`` — the generic ``_process_message``
    post-run patch (consumer.py:~188) owns those four and any overlap
    would clobber. See T22 brief red-team finding M5.
    """

    def __init__(self, scm_client: Any = None) -> None:
        self._scm = scm_client

    async def run(
        self, payload: dict[str, Any], **run_kwargs: Any
    ) -> Any:
        from dataclasses import replace
        from datetime import datetime, timezone
        import uuid as _uuid

        from ai_driven.agents.base import AgentResult
        from ai_driven.agents.orchestrator.artifacts import Artifact
        from ai_driven.agents.orchestrator.repository import ArtifactRepository
        from ai_driven.agents.specialists.base import SpecialistContext
        from ai_driven.agents.specialists.qa.agent import QaSpecialist
        from ai_driven.claude.conversation import Conversation
        from ai_driven.observability.metrics import emit_counter

        task_id_str = str(
            payload.get("task_id") or run_kwargs.get("task_id") or ""
        )
        try:
            task_uuid = _uuid.UUID(task_id_str) if task_id_str else None
        except (ValueError, TypeError):
            task_uuid = None

        # T23: the stamped orchestrator mode drives the gate decision.
        # Default 'shadow' so a direct caller (tests, debug inline path)
        # that doesn't stamp gets pre-T23 behaviour.
        stamped_mode: str = str(run_kwargs.get("orchestrator_mode") or "shadow")

        if task_uuid is None:
            # Without a task_id we cannot load prior artifacts or persist
            # a marker. Emit a counter and return a no-op success so the
            # message is deleted from SQS.
            logger.error(
                "qa_post_coding_missing_task_id",
                extra={"payload_keys": sorted(payload.keys())},
            )
            emit_counter("qa.post_coding.missing_task_id")
            return _synthesize_noop_result()

        # 1. Idempotency + load existing output_data.
        existing_output: dict[str, Any] = {}
        try:
            async with async_session_factory() as session:
                task_row = await TaskRepository(session).get_by_id(task_uuid)
                if task_row is not None:
                    existing_output = dict(task_row.output_data or {})
        except Exception:  # noqa: BLE001
            logger.warning(
                "qa_post_coding_task_load_failed",
                extra={"task_id": task_id_str},
                exc_info=True,
            )

        if existing_output.get("qa_completed_at"):
            logger.info(
                "qa_post_coding_skip_idempotent",
                extra={"task_id": task_id_str,
                       "qa_completed_at": existing_output.get("qa_completed_at")},
            )
            return _synthesize_noop_result()

        # 2. Missing pr_number — Sev-1 signal (CodingAgent T19b regression).
        pr_number = existing_output.get("pr_number")
        if not pr_number:
            emit_counter("qa.post_coding.missing_pr_number")
            logger.error(
                "qa_post_coding_missing_pr_number",
                extra={"task_id": task_id_str,
                       "output_data_keys": sorted(existing_output.keys())},
            )
            await _persist_qa_artifact_and_marker(
                task_uuid,
                Artifact(
                    role="qa",
                    verdict="error",
                    reasoning=(
                        "pr_number missing on task output_data — likely "
                        "CodingAgent T19b regression. Lenient pass-through."
                    ),
                    payload={
                        "schema_version": "v2",
                        "checks_performed": [],
                        "acceptance_criteria_verification": [],
                        "security_rules_verification": [],
                        "test_coverage_gap": [],
                        "regression_risk": "low",
                    },
                    model_used="",
                    input_tokens=0,
                    output_tokens=0,
                ),
                stamped_mode,
            )
            return _synthesize_noop_result()

        # 3. Load prior artifacts (BA + Security minimum).
        prior_map: dict[str, Any] = {}
        try:
            async with async_session_factory() as session:
                prior_map = await ArtifactRepository(session).list_for_task(
                    task_uuid
                )
        except Exception:  # noqa: BLE001
            logger.warning(
                "qa_post_coding_prior_artifacts_load_failed",
                extra={"task_id": task_id_str},
                exc_info=True,
            )

        # Build SpecialistContext. ticket + repo shapes mirror
        # _build_orchestrate_payload so QA's envelope looks the same as
        # every other specialist run.
        ticket = payload.get("context", {}).get("ticket") or payload.get(
            "ticket"
        ) or {
            "key": payload.get("jira_key", ""),
            "summary": payload.get("title") or payload.get("message", ""),
            "description": payload.get("message", ""),
            "labels": payload.get("labels", []),
            "acceptance_criteria": payload.get("acceptance_criteria", []),
        }
        repo = payload.get("context", {}).get("repo") or payload.get(
            "repo"
        ) or {
            "full_name": payload.get("repo_full_name", ""),
            "scm_type": payload.get("scm_type", "github"),
            "default_branch": payload.get("default_branch", "main"),
        }
        ctx = SpecialistContext(
            task_id=task_id_str,
            ticket=ticket,
            repo=repo,
            tier=existing_output.get("classifier_tier") or "full_workflow",
            prior_artifacts=prior_map,
            team_settings={},
            pr_ref={
                "pr_number": pr_number,
                "pr_url": existing_output.get("pr_url") or "",
            },
        )

        specialist = QaSpecialist(scm_client=self._scm)
        try:
            artifact = await specialist.run_specialist(ctx)
        except Exception as exc:  # noqa: BLE001
            logger.error(
                "qa_post_coding_specialist_raised",
                extra={"task_id": task_id_str, "error": str(exc)[:300]},
                exc_info=True,
            )
            artifact = Artifact(
                role="qa",
                verdict="error",
                reasoning=f"QA specialist raised: {exc}",
                payload={
                    "schema_version": "v2",
                    "checks_performed": [],
                    "acceptance_criteria_verification": [],
                    "security_rules_verification": [],
                    "test_coverage_gap": [],
                    "regression_risk": "low",
                },
                model_used="",
                input_tokens=0,
                output_tokens=0,
            )

        # 5. Schema-drift belt-and-braces. POST-coding QA MUST emit
        # schema_version="v2". A specialist that slipped through to v1
        # (missing key → validate_payload fallback) is schema drift; we
        # force verdict=error so T23 treats it as lenient-pass.
        schema_version = (artifact.payload or {}).get("schema_version")
        if schema_version != "v2":
            emit_counter("qa.post_coding.schema_drift")
            logger.error(
                "qa_post_coding_schema_drift",
                extra={"task_id": task_id_str,
                       "got_schema_version": schema_version,
                       "had_verdict": artifact.verdict},
            )
            artifact = replace(
                artifact,
                verdict="error",
                reasoning=(
                    "schema_version drift; post-coding QA must emit v2. "
                    f"Got: {schema_version!r}. Original reasoning: "
                    f"{artifact.reasoning or '(none)'}"
                ),
            )

        # 4. Shared-session persistence (artifact + markers + gate
        # decision in ONE commit -- T23 M2).
        await _persist_qa_artifact_and_marker(task_uuid, artifact, stamped_mode)
        emit_counter(f"qa.post_coding.verdict.{artifact.verdict}")

        # Return an AgentResult so TaskConsumer.run_result_patch doesn't
        # clobber our markers (the generic path writes {output,
        # tool_calls, iterations, error}; we write qa_* keys so they
        # don't collide).
        conv = Conversation()
        conv.total_input_tokens = int(artifact.input_tokens or 0)
        conv.total_output_tokens = int(artifact.output_tokens or 0)
        return AgentResult(
            success=True,
            output=json.dumps({
                "qa_verdict": artifact.verdict,
                "schema_version": (artifact.payload or {}).get("schema_version"),
            }),
            conversation=conv,
            tool_calls=0,
            iterations=0,
            duration_seconds=0.0,
        )


def _synthesize_noop_result() -> Any:
    """Return a lightweight AgentResult so TaskConsumer treats the run as
    a clean success and deletes the SQS message without writing extra
    output keys."""
    from ai_driven.agents.base import AgentResult
    from ai_driven.claude.conversation import Conversation

    return AgentResult(
        success=True,
        output="",
        conversation=Conversation(),
        tool_calls=0,
        iterations=0,
        duration_seconds=0.0,
    )


async def _persist_qa_artifact_and_marker(
    task_uuid: Any,
    artifact: Any,
    stamped_mode: str = "shadow",
) -> "Any":
    """Upsert the QA artifact, output_data markers, AND status transition
    in ONE shared session / ONE commit.

    T22 introduced this helper to atomically persist the artifact + the
    ``qa_*`` markers (``qa_completed_at`` / ``qa_verdict`` /
    ``qa_artifact_id``). T23 extends it to ALSO apply the gate decision
    inside the same commit (red-team M2) so there is no race window
    between "artifact exists" and "status reflects verdict".

    T23 additions:
      * Computes :func:`decide_qa_gate` from ``artifact.verdict`` +
        ``stamped_mode`` + ``artifact.payload``.
      * Writes ``output_data.qa_failures`` when non-empty (live+block,
        shadow+block for observability).
      * Applies ``decision.new_status`` under a compare-and-set guard:
        the ``qa_failed`` transition is only allowed when the current
        status is ``awaiting_qa``. Any other current status (already
        ``completed`` via shadow path, manually ``blocked`` by an
        operator, etc.) emits ``qa.gate.status_mismatch`` and leaves
        the row alone for T24 reaper / admin override.
      * Counter emission happens AFTER the DB commit so a rolled-back
        transaction never leaks a counter.

    Returns the :class:`QaGateDecision` so callers (e.g. the replay
    branch) can log / assert against it. Callers that don't need it
    may ignore the return value.
    """
    from datetime import datetime, timezone

    from ai_driven.agents.orchestrator.qa_gate import (
        QaGateDecision,
        decide_qa_gate,
    )
    from ai_driven.agents.orchestrator.repository import ArtifactRepository
    from ai_driven.observability.metrics import emit_counter

    payload = dict(artifact.payload or {})
    decision: QaGateDecision = decide_qa_gate(
        artifact.verdict, payload, stamped_mode,
    )

    # Flags set DURING the DB block, read AFTER commit to decide which
    # counters to emit. Keeping them out of the session context avoids
    # a partial-emit window if the commit raises.
    status_applied: bool = False
    status_mismatch_current: str | None = None

    try:
        async with async_session_factory() as session:
            art_repo = ArtifactRepository(session)
            task_repo = TaskRepository(session)
            await art_repo.upsert(task_uuid, artifact)

            # Fetch the artifact row id post-upsert so we can stamp
            # qa_artifact_id. Avoids adding a new return type to
            # ArtifactRepository.upsert and keeps that contract stable.
            from sqlalchemy import select
            from ai_driven.db.postgres.models import TaskArtifact

            res = await session.execute(
                select(TaskArtifact.id).where(
                    TaskArtifact.task_id == task_uuid,
                    TaskArtifact.role == "qa",
                )
            )
            qa_artifact_id = res.scalar_one_or_none()

            # Load current status for M3 compare-and-set on qa_failed.
            task_row = await task_repo.get_by_id(task_uuid)
            current_status = task_row.status if task_row is not None else None

            out_patch: dict[str, Any] = {
                "qa_completed_at": datetime.now(timezone.utc).isoformat(),
                "qa_verdict": artifact.verdict,
                "qa_artifact_id": (
                    str(qa_artifact_id) if qa_artifact_id is not None else None
                ),
                "qa_reasoning": (artifact.reasoning or "")[:500],
            }
            if decision.qa_failures:
                # Populated on block in both live + shadow modes. UI
                # (T25) renders this list.
                out_patch["qa_failures"] = decision.qa_failures
            await task_repo.update_output_data(task_uuid, out_patch)

            # Status transition (live mode only; shadow yields new_status=None).
            if decision.new_status is not None:
                # M3 compare-and-set: qa_failed is a downgrade, only
                # allowed from awaiting_qa. Other currents (completed
                # via shadow, blocked by operator, ...) keep the row
                # untouched and emit a mismatch counter for T24 to
                # inspect.
                if (
                    decision.new_status == "qa_failed"
                    and current_status != "awaiting_qa"
                ):
                    status_mismatch_current = current_status or "unknown"
                else:
                    await task_repo.update_status(
                        task_uuid, decision.new_status,
                    )
                    status_applied = True

            await session.commit()
    except Exception:  # noqa: BLE001
        logger.error(
            "qa_post_coding_persist_failed",
            extra={"task_id": str(task_uuid)},
            exc_info=True,
        )
        # Re-raise so SQS redelivers — an idempotent replay will re-run
        # QA (expensive but correct) rather than silently dropping the
        # verdict.
        raise

    # Post-commit counter emission. Ordering matters: we want the
    # transition counter to only fire when the DB actually changed.
    if decision.counter_name:
        emit_counter(decision.counter_name)
    if status_mismatch_current is not None:
        emit_counter(
            "qa.gate.status_mismatch",
            tags=[f"current:{status_mismatch_current}"],
        )
        logger.warning(
            "qa_gate_status_mismatch",
            extra={
                "task_id": str(task_uuid),
                "current_status": status_mismatch_current,
                "desired_status": decision.new_status,
                "verdict": artifact.verdict,
            },
        )
    if status_applied:
        logger.info(
            "qa_gate_status_applied",
            extra={
                "task_id": str(task_uuid),
                "new_status": decision.new_status,
                "verdict": artifact.verdict,
                "stamped_mode": stamped_mode,
            },
        )

    return decision
