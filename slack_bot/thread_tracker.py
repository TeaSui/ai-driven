"""Slack threaded progress updates for agent tasks.

SlackThreadTracker posts an initial message when a task starts, then
sends subsequent progress updates as thread replies, keeping all updates
for a single task neatly grouped in one Slack conversation.
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

_SLACK_POST_URL = "https://slack.com/api/chat.postMessage"


class SlackThreadTracker:
    """Track Slack threads for agent tasks and post threaded progress updates.

    Usage::

        tracker = SlackThreadTracker()
        await tracker.start_task("task-123", "Starting work on GTX-4821...")
        await tracker.post_thread_update("task-123", "Planning complete - 4 files to change")
        await tracker.post_thread_update("task-123", "PR #2418 opened")
    """

    def __init__(self, channel: str | None = None) -> None:
        settings = get_settings()
        self._token: str = settings.slack_bot_token
        self._channel: str = channel or settings.slack_default_channel
        # task_id -> thread_ts mapping
        self._threads: dict[str, str] = {}

    @property
    def active_threads(self) -> dict[str, str]:
        """Return a copy of the active task-to-thread mapping."""
        return dict(self._threads)

    async def start_task(
        self,
        task_id: str,
        text: str,
        *,
        channel: str | None = None,
        blocks: list[dict[str, Any]] | None = None,
    ) -> str | None:
        """Post the initial message for a task and store the thread ts.

        Returns the message ``ts`` on success, or ``None`` if posting failed.
        """
        target = channel or self._channel
        result = await self._post_message(text, channel=target, blocks=blocks)
        if result is not None:
            self._threads[task_id] = result
        return result

    async def post_thread_update(
        self,
        task_id: str,
        text: str,
        *,
        blocks: list[dict[str, Any]] | None = None,
    ) -> str | None:
        """Post a threaded reply to the initial message for a task.

        If no thread exists for the given ``task_id``, falls back to
        posting as a top-level message so updates are never silently lost.

        Returns the reply ``ts`` on success, or ``None`` on failure.
        """
        thread_ts = self._threads.get(task_id)
        if thread_ts is None:
            logger.warning(
                "No thread found for task %s, posting as top-level message",
                task_id,
            )
            return await self._post_message(text, blocks=blocks)
        return await self._post_message(text, thread_ts=thread_ts, blocks=blocks)

    async def post_pr_card(
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
    ) -> str | None:
        """Post a rich PR card (Slack Block Kit) as a thread reply."""
        blocks = build_pr_card_blocks(
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
        fallback_text = f"PR #{pr_number}: {pr_title} in {repo_name}"
        return await self.post_thread_update(task_id, fallback_text, blocks=blocks)

    def remove_task(self, task_id: str) -> None:
        """Clean up a completed task's thread mapping."""
        self._threads.pop(task_id, None)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    async def _post_message(
        self,
        text: str,
        *,
        channel: str | None = None,
        thread_ts: str | None = None,
        blocks: list[dict[str, Any]] | None = None,
    ) -> str | None:
        """Post a message to Slack and return the message ts."""
        if not self._token:
            logger.debug("Slack token not configured, skipping thread post")
            return None

        target = channel or self._channel
        if not target:
            logger.debug("No Slack channel configured, skipping thread post")
            return None

        payload: dict[str, Any] = {
            "channel": target,
            "text": text,
            "mrkdwn": True,
        }
        if thread_ts:
            payload["thread_ts"] = thread_ts
        if blocks:
            payload["blocks"] = blocks

        try:
            async with httpx.AsyncClient() as client:
                resp = await client.post(
                    _SLACK_POST_URL,
                    headers={"Authorization": f"Bearer {self._token}"},
                    json=payload,
                )
                data = resp.json()
                if not data.get("ok"):
                    logger.warning(
                        "Slack API error in thread post: %s", data.get("error")
                    )
                    return None
                return data.get("ts")
        except Exception:
            logger.warning("Failed to post Slack thread message", exc_info=True)
            return None


# ======================================================================
# PR Card Block Kit builder
# ======================================================================


def build_pr_card_blocks(
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
) -> list[dict[str, Any]]:
    """Build Slack Block Kit blocks for a PR card.

    Returns a list of block dicts suitable for the ``blocks`` parameter
    of ``chat.postMessage``.
    """
    # Status badge line
    badges: list[str] = [status]
    if ai_reviewed:
        badges.append("AI-reviewed")
    ci_label = {
        "passing": "CI passing",
        "failing": "CI failing",
        "pending": "CI pending",
    }.get(ci_status, f"CI {ci_status}")
    badges.append(ci_label)
    badge_text = " \u00b7 ".join(badges)

    blocks: list[dict[str, Any]] = [
        {
            "type": "header",
            "text": {
                "type": "plain_text",
                "text": f"{repo_name} #{pr_number}",
                "emoji": True,
            },
        },
        {
            "type": "section",
            "fields": [
                {
                    "type": "mrkdwn",
                    "text": f"*Title:*\n{pr_title}",
                },
                {
                    "type": "mrkdwn",
                    "text": f"*Branch:*\n`{branch}`",
                },
                {
                    "type": "mrkdwn",
                    "text": f"*Files changed:*\n{file_count}",
                },
                {
                    "type": "mrkdwn",
                    "text": f"*Changes:*\n+{additions} / -{deletions}",
                },
            ],
        },
        {
            "type": "context",
            "elements": [
                {
                    "type": "mrkdwn",
                    "text": badge_text,
                },
            ],
        },
        {
            "type": "actions",
            "elements": [
                {
                    "type": "button",
                    "text": {
                        "type": "plain_text",
                        "text": "View PR",
                        "emoji": True,
                    },
                    "url": pr_url,
                    "action_id": "view_pr",
                },
            ],
        },
    ]
    return blocks
