"""Event-driven triggers that wire task completion events to next workflow steps."""

from __future__ import annotations

import logging
import uuid
from typing import Any

from ai_driven.queue.producer import TaskProducer
from ai_driven.slack_bot.notifications import SlackNotifier

logger = logging.getLogger(__name__)


class WorkflowTriggers:
    """Handles task completion events and triggers next workflow steps.

    Each ``on_*`` method is invoked when its corresponding event fires
    (e.g. from an SQS consumer or state-machine listener).  The trigger
    decides what to do next and enqueues follow-up work.
    """

    def __init__(
        self,
        task_producer: TaskProducer | None = None,
        slack_notifier: SlackNotifier | None = None,
        scm_client: Any = None,
    ) -> None:
        self._producer = task_producer
        self._notifier = slack_notifier
        self._scm = scm_client

    # ------------------------------------------------------------------
    # Coding completed
    # ------------------------------------------------------------------

    async def on_coding_task_completed(
        self, task_id: str, result: dict[str, Any]
    ) -> None:
        """When coding finishes, trigger review if a PR was created."""
        pr_number = result.get("pr_number")
        repo_full_name = result.get("repo_full_name", "")
        pr_title = result.get("pr_title", "")

        if not pr_number or not repo_full_name:
            logger.info(
                "Coding task %s completed without a PR — skipping review dispatch",
                task_id,
            )
            return

        review_task_id = str(uuid.uuid4())
        payload = {
            "repo_full_name": repo_full_name,
            "pr_number": pr_number,
            "pr_title": pr_title,
            "pr_description": result.get("pr_description", ""),
            "originating_task_id": task_id,
        }

        if self._producer:
            await self._producer.enqueue_task(
                task_id=review_task_id,
                agent_type="review",
                payload=payload,
            )
            logger.info(
                "Triggered review task %s for PR #%d (from coding task %s)",
                review_task_id,
                pr_number,
                task_id,
            )

        if self._notifier:
            await self._notifier.notify_phase_change(
                task_id=task_id,
                phase="PR Created",
                detail=f"PR #{pr_number} in {repo_full_name} — review dispatched.",
            )

    # ------------------------------------------------------------------
    # Review completed
    # ------------------------------------------------------------------

    async def on_review_completed(
        self, task_id: str, result: dict[str, Any]
    ) -> None:
        """When review finishes, merge if approved or notify if changes requested."""
        verdict = result.get("verdict", "").lower()
        repo_full_name = result.get("repo_full_name", "")
        pr_number = result.get("pr_number")

        if verdict == "approved" and pr_number and repo_full_name:
            # Attempt merge
            if self._scm:
                try:
                    await self._scm.merge_pull_request(
                        repo_full_name, pr_number, "squash"
                    )
                    logger.info(
                        "Auto-merged PR #%d in %s after review approval (task %s)",
                        pr_number,
                        repo_full_name,
                        task_id,
                    )
                except Exception:
                    logger.exception(
                        "Failed to auto-merge PR #%d in %s (task %s)",
                        pr_number,
                        repo_full_name,
                        task_id,
                    )
                    if self._notifier:
                        await self._notifier.notify_task_failed(
                            task_id=task_id,
                            error=f"Auto-merge failed for PR #{pr_number} in {repo_full_name}",
                        )
                    return

            if self._notifier:
                await self._notifier.notify_phase_change(
                    task_id=task_id,
                    phase="Merged",
                    detail=f"PR #{pr_number} in {repo_full_name} merged after review approval.",
                )
        elif verdict == "changes_requested":
            logger.info(
                "Review task %s requested changes on PR #%s", task_id, pr_number
            )
            if self._notifier:
                summary = result.get("summary", "Changes were requested by the reviewer.")
                await self._notifier.notify_phase_change(
                    task_id=task_id,
                    phase="Changes Requested",
                    detail=f"PR #{pr_number}: {summary}",
                )
        else:
            logger.info(
                "Review task %s finished with verdict '%s' — no automatic action",
                task_id,
                verdict,
            )

    # ------------------------------------------------------------------
    # Merge completed
    # ------------------------------------------------------------------

    async def on_merge_completed(
        self, task_id: str, result: dict[str, Any]
    ) -> None:
        """When merge finishes, notify team and update external trackers."""
        repo_full_name = result.get("repo_full_name", "")
        pr_number = result.get("pr_number")
        pr_url = result.get("pr_url", "")

        if self._notifier:
            await self._notifier.notify_task_completed(
                task_id=task_id,
                pr_url=pr_url,
            )

        logger.info(
            "Merge completed for task %s (PR #%s in %s)",
            task_id,
            pr_number,
            repo_full_name,
        )
