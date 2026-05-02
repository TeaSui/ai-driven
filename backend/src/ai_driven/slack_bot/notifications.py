"""Slack notifications for agent progress updates.

This module provides ``SlackNotifier`` for simple fire-and-forget
messages and integrates with ``SlackThreadTracker`` for threaded
progress updates when a tracker instance is provided.
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)


class SlackNotifier:
    """Send notifications to Slack channels about agent progress.

    When a ``SlackThreadTracker`` is provided, task lifecycle methods
    (``notify_task_started``, ``notify_phase_change``, etc.) will
    automatically post as thread replies, keeping all updates for one
    task grouped together.
    """

    def __init__(
        self,
        channel: str | None = None,
        thread_tracker: Any | None = None,
    ) -> None:
        settings = get_settings()
        self._token = settings.slack_bot_token
        self._default_channel = channel or settings.slack_default_channel
        self._tracker = thread_tracker

    async def notify_task_started(
        self, task_id: str, agent_type: str, title: str
    ) -> None:
        text = (
            f"*{agent_type.title()} Agent Started*\n"
            f"Task: `{task_id}`\n"
            f"Title: {title}"
        )
        if self._tracker is not None:
            await self._tracker.start_task(task_id, text)
            return
        await self._send(text)

    async def notify_phase_change(
        self, task_id: str, phase: str, detail: str = ""
    ) -> None:
        text = (
            f"*Phase Update*: {phase}\n"
            f"Task: `{task_id}`\n"
            f"{detail}"
        )
        if self._tracker is not None:
            await self._tracker.post_thread_update(task_id, text)
            return
        await self._send(text)

    async def notify_pr_created(
        self,
        task_id: str,
        *,
        repo_name: str,
        pr_number: int,
        pr_title: str,
        branch: str,
        file_count: int = 0,
        additions: int = 0,
        deletions: int = 0,
        pr_url: str,
        status: str = "Open",
        ai_reviewed: bool = False,
        ci_status: str = "pending",
    ) -> None:
        """Post a rich PR card notification, threaded if a tracker is available."""
        if self._tracker is not None:
            await self._tracker.post_pr_card(
                task_id,
                repo_name=repo_name,
                pr_number=pr_number,
                pr_title=pr_title,
                branch=branch,
                file_count=file_count,
                additions=additions,
                deletions=deletions,
                pr_url=pr_url,
                status=status,
                ai_reviewed=ai_reviewed,
                ci_status=ci_status,
            )
            return
        # Fallback: plain text
        await self._send(
            f"*PR #{pr_number} opened* in {repo_name}\n"
            f"Title: {pr_title}\n"
            f"Branch: `{branch}`\n"
            f"<{pr_url}|View PR>"
        )

    async def notify_task_completed(
        self, task_id: str, pr_url: str | None = None
    ) -> None:
        msg = f"*Task Completed*\nTask: `{task_id}`"
        if pr_url:
            msg += f"\nPR: {pr_url}"
        if self._tracker is not None:
            await self._tracker.post_thread_update(task_id, msg)
            self._tracker.remove_task(task_id)
            return
        await self._send(msg)

    async def notify_task_failed(self, task_id: str, error: str) -> None:
        text = f"*Task Failed*\nTask: `{task_id}`\nError: {error[:500]}"
        if self._tracker is not None:
            await self._tracker.post_thread_update(task_id, text)
            self._tracker.remove_task(task_id)
            return
        await self._send(text)

    async def _send(self, text: str, channel: str | None = None) -> None:
        """Send a message to Slack via API."""
        if not self._token:
            logger.debug("Slack token not configured, skipping notification")
            return

        target = channel or self._default_channel
        if not target:
            logger.debug("No Slack channel configured, skipping notification")
            return

        try:
            async with httpx.AsyncClient() as client:
                resp = await client.post(
                    "https://slack.com/api/chat.postMessage",
                    headers={"Authorization": f"Bearer {self._token}"},
                    json={"channel": target, "text": text, "mrkdwn": True},
                )
                data = resp.json()
                if not data.get("ok"):
                    logger.warning("Slack API error: %s", data.get("error"))
        except Exception as e:
            logger.warning("Failed to send Slack notification: %s", e)
