"""Slack bot for triggering AI agents."""

from __future__ import annotations

import asyncio
import logging
import re
from typing import Any

from ai_driven.config import get_settings
from ai_driven.db.postgres.repository import RepoRepository, TaskRepository
from ai_driven.db.postgres.session import async_session_factory
from ai_driven.queue.producer import TaskProducer

logger = logging.getLogger(__name__)

JIRA_KEY_PATTERN = re.compile(r"^[A-Z][A-Z0-9]+-\d+$")
# Pattern to extract Jira key from free-form text (e.g. "@bot please fix GTX-4821")
JIRA_KEY_SEARCH_PATTERN = re.compile(r"\b([A-Z][A-Z0-9]+-\d+)\b")

# Lazy app singleton — avoids requiring SLACK_BOT_TOKEN at import time.
_app = None


def get_app():
    """Create or return the Slack AsyncApp singleton."""
    global _app
    if _app is not None:
        return _app

    from slack_bolt.async_app import AsyncApp

    settings = get_settings()
    _app = AsyncApp(
        token=settings.slack_bot_token,
        signing_secret=settings.slack_signing_secret,
    )
    _register_commands(_app)
    return _app


def _register_commands(app) -> None:
    """Register all slash commands and event handlers on the given app."""

    @app.command("/ai-code")
    async def handle_ai_code(ack: Any, body: dict, say: Any) -> None:
        await _handle_ai_code(ack, body, say)

    @app.command("/ai-status")
    async def handle_ai_status(ack: Any, body: dict, say: Any) -> None:
        await _handle_ai_status(ack, body, say)

    @app.event("app_mention")
    async def handle_app_mention(event: dict, say: Any) -> None:
        await _handle_mention(event, say)


async def _handle_ai_code(ack: Any, body: dict, say: Any) -> None:
    """Handle /ai-code PROJ-123 [in org/repo] command."""
    await ack()

    text = body.get("text", "").strip()
    if not text:
        await say("Usage: `/ai-code PROJ-123` or `/ai-code PROJ-123 in org/repo`")
        return

    # Parse: "PROJ-123" or "PROJ-123 in org/repo"
    parts = text.split(" in ", 1)
    jira_key = parts[0].strip()
    repo_name = parts[1].strip() if len(parts) > 1 else None

    if not JIRA_KEY_PATTERN.match(jira_key):
        await say(
            f"Invalid Jira key format: `{jira_key}`. Expected format: `PROJ-123`"
        )
        return

    try:
        async with async_session_factory() as session:
            # Find repository
            repo_repo = RepoRepository(session)
            if repo_name:
                db_repo = await repo_repo.get_by_full_name(repo_name)
            else:
                db_repo = await repo_repo.get_first_active()

            if not db_repo:
                await say(f"Repository not found: `{repo_name or 'default'}`")
                return

            # Create task
            task_repo = TaskRepository(session)
            task = await task_repo.create(
                team_id=db_repo.team_id,
                repo_id=db_repo.id,
                agent_type="coding",
                jira_key=jira_key,
                title=f"Coding: {jira_key}",
                input_data={
                    "jira_key": jira_key,
                    "repo_full_name": db_repo.full_name,
                    "triggered_by": "slack_command",
                },
            )
            await session.commit()

            # Enqueue
            producer = TaskProducer()
            await producer.enqueue_task(
                task_id=str(task.id),
                agent_type="coding",
                payload=task.input_data,
            )

            await say(
                f"Coding task created!\n"
                f"- Task ID: `{task.id}`\n"
                f"- Jira: `{jira_key}`\n"
                f"- Repo: `{db_repo.full_name}`\n"
                f"- Status: queued"
            )

    except Exception as e:
        logger.error("Error creating task from Slack: %s", e)
        await say(f"Error: {e}")


async def _handle_ai_status(ack: Any, body: dict, say: Any) -> None:
    """Handle /ai-status TASK-ID command."""
    await ack()

    task_id = body.get("text", "").strip()
    if not task_id:
        await say("Usage: `/ai-status <task-id>`")
        return

    try:
        async with async_session_factory() as session:
            task_repo = TaskRepository(session)
            task = await task_repo.get_by_id(task_id)

            if not task:
                await say(f"Task not found: `{task_id}`")
                return

            status_emoji = {
                "queued": "hourglass",
                "running": "arrows_counterclockwise",
                "completed": "white_check_mark",
                "failed": "x",
            }.get(task.status, "question")

            msg = (
                f":{status_emoji}: Task `{task.id}`\n"
                f"- Type: {task.agent_type}\n"
                f"- Status: {task.status}\n"
                f"- Title: {task.title}\n"
                f"- Created: {task.created_at}"
            )

            if task.status == "completed" and task.output_data:
                output = task.output_data.get("output", "")
                if output:
                    msg += f"\n- Output: {output[:500]}"

            if task.status == "failed" and task.output_data:
                error = task.output_data.get("error", "")
                if error:
                    msg += f"\n- Error: {error[:500]}"

            await say(msg)

    except Exception as e:
        logger.error("Error fetching task status: %s", e)
        await say(f"Error: {e}")


async def _handle_mention(event: dict, say: Any) -> None:
    """Handle @mention events in Slack.

    Parses the mention text for intent:
    - If a Jira key is found, dispatches a coding agent task.
    - Otherwise, replies asking for a Jira key.

    Always replies in-thread so the conversation stays grouped.
    """
    text = event.get("text", "")
    thread_ts = event.get("thread_ts") or event.get("ts", "")
    channel = event.get("channel", "")

    # Strip the bot mention tag (e.g. "<@U12345>") to get the actual message
    cleaned_text = re.sub(r"<@[A-Z0-9]+>", "", text).strip()

    if not cleaned_text:
        await say(
            text="Hi! Mention me with a Jira key to start a coding task. "
            "Example: `@bot please fix GTX-4821`",
            thread_ts=thread_ts,
            channel=channel,
        )
        return

    # Search for a Jira key in the message
    match = JIRA_KEY_SEARCH_PATTERN.search(cleaned_text)
    if not match:
        await say(
            text="I could not find a Jira key in your message. "
            "Please include one, e.g. `@bot please fix GTX-4821`",
            thread_ts=thread_ts,
            channel=channel,
        )
        return

    jira_key = match.group(1)

    try:
        async with async_session_factory() as session:
            repo_repo = RepoRepository(session)
            db_repo = await repo_repo.get_first_active()

            if not db_repo:
                await say(
                    text="No active repository configured. "
                    "Please register a repository first.",
                    thread_ts=thread_ts,
                    channel=channel,
                )
                return

            task_repo = TaskRepository(session)
            task = await task_repo.create(
                team_id=db_repo.team_id,
                repo_id=db_repo.id,
                agent_type="coding",
                jira_key=jira_key,
                title=f"Coding: {jira_key}",
                input_data={
                    "jira_key": jira_key,
                    "repo_full_name": db_repo.full_name,
                    "triggered_by": "slack_mention",
                    "original_message": cleaned_text[:500],
                },
            )
            await session.commit()

            producer = TaskProducer()
            await producer.enqueue_task(
                task_id=str(task.id),
                agent_type="coding",
                payload=task.input_data,
            )

            await say(
                text=(
                    f"Got it! Starting work on `{jira_key}`.\n"
                    f"- Task ID: `{task.id}`\n"
                    f"- Repo: `{db_repo.full_name}`\n"
                    f"I'll post updates in this thread."
                ),
                thread_ts=thread_ts,
                channel=channel,
            )

    except Exception as e:
        logger.error("Error handling @mention: %s", e)
        await say(
            text=f"Sorry, something went wrong: {e}",
            thread_ts=thread_ts,
            channel=channel,
        )


async def main() -> None:
    from slack_bolt.adapter.socket_mode.async_handler import AsyncSocketModeHandler

    settings = get_settings()
    handler = AsyncSocketModeHandler(get_app(), settings.slack_app_token)
    await handler.start_async()


if __name__ == "__main__":
    asyncio.run(main())
