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
        self._sqs = boto3.client("sqs", region_name=settings.aws_region)
        self._queue_urls = [
            url
            for url in [
                settings.sqs_task_queue_url,
                settings.sqs_review_queue_url,
                settings.sqs_devops_queue_url,
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

            # Create and run agent
            agent = self._create_agent(agent_type, payload)
            result = await agent.run(payload)

            # Update task status
            # Estimate cost: ~$3/M input + ~$15/M output for Sonnet
            input_tokens = result.conversation.total_input_tokens
            output_tokens = result.conversation.total_output_tokens
            cost_usd = (input_tokens * 3.0 / 1_000_000) + (output_tokens * 15.0 / 1_000_000)

            async with async_session_factory() as session:
                task_repo = TaskRepository(session)
                status = "completed" if result.success else "failed"
                await task_repo.update_status(
                    task_id,
                    status,
                    output_data={
                        "output": result.output[:5000],
                        "tool_calls": result.tool_calls,
                        "iterations": result.iterations,
                        "error": result.error,
                    },
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
    def _create_agent(agent_type: str, payload: dict[str, Any]) -> Any:
        """Factory method to create the appropriate agent with real clients."""
        settings = get_settings()

        # Create Jira client if configured
        jira_client = None
        if settings.jira_base_url and settings.jira_api_token:
            jira_client = JiraClient()

        # Create SCM client based on repo info
        scm_client = None
        repo_full_name = payload.get("repo_full_name", "")
        if repo_full_name and settings.github_token:
            from ai_driven.scm.github_client import GitHubClient

            scm_client = GitHubClient(token=settings.github_token)
        elif repo_full_name and settings.bitbucket_username:
            from ai_driven.scm.bitbucket_client import BitbucketClient

            scm_client = BitbucketClient(
                username=settings.bitbucket_username,
                app_password=settings.bitbucket_app_password,
            )

        agents: dict[str, Any] = {
            "coding": lambda: CodingAgent(
                jira_client=jira_client, scm_client=scm_client
            ),
            "review": lambda: ReviewAgent(scm_client=scm_client),
            "devops": lambda: DevOpsAgent(jira_client=jira_client),
        }
        factory = agents.get(agent_type)
        if not factory:
            raise ValueError(f"Unknown agent type: {agent_type}")
        return factory()
