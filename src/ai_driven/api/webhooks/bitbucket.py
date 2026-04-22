"""Bitbucket Cloud webhook handler."""

from __future__ import annotations

import logging
import re
import uuid
from typing import Any

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.db.dynamo.models import WebhookEventStore
from ai_driven.db.postgres.repository import RepoRepository, TaskRepository
from ai_driven.db.postgres.session import get_session
from ai_driven.observability.trace_context import bind_trace, clear_trace
from ai_driven.queue.producer import TaskProducer

logger = logging.getLogger(__name__)
router = APIRouter()

AI_COMMAND_PATTERN = re.compile(r"^/ai-(code|review)(?:\s+(\S+))?", re.MULTILINE)


@router.post("")
async def bitbucket_webhook(
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> dict[str, str]:
    payload: dict[str, Any] = await request.json()
    event_key = request.headers.get("X-Event-Key", "unknown")

    bind_trace()
    logger.info("bitbucket_webhook_received", extra={"event": event_key})

    if event_key == "pullrequest:created":
        await _handle_pr_created(payload, session)
    elif event_key == "pullrequest:updated":
        await _handle_pr_updated(payload, session)
    elif event_key == "pullrequest:comment_created":
        await _handle_pr_comment(payload, session)

    clear_trace()
    return {"status": "accepted"}


async def _handle_pr_created(payload: dict[str, Any], session: AsyncSession) -> None:
    """Handle new pull request -- trigger review."""
    pr = payload.get("pullrequest", {})
    repo_data = payload.get("repository", {})
    repo_full_name = repo_data.get("full_name", "")

    # Deduplication
    event_store = WebhookEventStore()
    event_id = f"bitbucket-pr-{pr.get('id', '')}-created"
    if not event_store.record_event(event_id, "bitbucket", "pullrequest:created", {"pr_id": pr.get("id")}):
        logger.info("Duplicate webhook event, skipping", extra={"event_id": event_id})
        return

    # Look up repository
    repo_repo = RepoRepository(session)
    db_repo = await repo_repo.get_by_full_name(repo_full_name)
    if not db_repo:
        logger.warning("Repository not registered", extra={"full_name": repo_full_name})
        return

    # Create review task
    task_repo = TaskRepository(session)
    task = await task_repo.create(
        team_id=db_repo.team_id,
        repo_id=db_repo.id,
        agent_type="review",
        title=f"Review PR #{pr.get('id')}: {pr.get('title', '')}",
        input_data={
            "pr_number": pr.get("id"),
            "pr_url": pr.get("links", {}).get("html", {}).get("href", ""),
            "source_branch": pr.get("source", {}).get("branch", {}).get("name", ""),
            "target_branch": pr.get("destination", {}).get("branch", {}).get("name", ""),
            "repo_full_name": repo_full_name,
        },
    )
    await session.flush()

    logger.info("Review task created", extra={"task_id": str(task.id), "pr": pr.get("id")})

    producer = TaskProducer()
    await producer.enqueue_task(
        task_id=str(task.id),
        agent_type="review",
        payload=task.input_data,
    )


async def _handle_pr_updated(payload: dict[str, Any], session: AsyncSession) -> None:
    """Handle PR update -- re-trigger review if needed."""
    # Same logic as created for now; dedup prevents double-processing
    await _handle_pr_created(payload, session)


async def _handle_pr_comment(payload: dict[str, Any], session: AsyncSession) -> None:
    """Handle PR comment -- check for AI commands."""
    comment = payload.get("comment", {})
    body = comment.get("content", {}).get("raw", "")
    pr = payload.get("pullrequest", {})
    repo_data = payload.get("repository", {})
    repo_full_name = repo_data.get("full_name", "")

    match = AI_COMMAND_PATTERN.search(body)
    if not match:
        return

    command = match.group(1)
    argument = match.group(2)

    logger.info("AI command detected", extra={"command": f"/ai-{command}", "argument": argument})

    # Deduplication
    event_store = WebhookEventStore()
    event_id = f"bitbucket-comment-{comment.get('id', '')}"
    if not event_store.record_event(event_id, "bitbucket", "comment", {"command": command}):
        logger.info("Duplicate command event, skipping", extra={"event_id": event_id})
        return

    # Look up repository
    repo_repo = RepoRepository(session)
    db_repo = await repo_repo.get_by_full_name(repo_full_name)
    if not db_repo:
        logger.warning("Repository not registered", extra={"full_name": repo_full_name})
        return

    task_repo = TaskRepository(session)

    if command == "code":
        jira_key = argument if argument else None
        task = await task_repo.create(
            team_id=db_repo.team_id,
            repo_id=db_repo.id,
            agent_type="coding",
            jira_key=jira_key,
            title=f"AI Coding: {jira_key or 'from PR comment'}",
            input_data={
                "jira_key": jira_key,
                "repo_full_name": repo_full_name,
                "triggered_by": "bitbucket_comment",
                "pr_id": pr.get("id"),
            },
        )
        agent_type = "coding"
    elif command == "review":
        pr_id = pr.get("id")
        task = await task_repo.create(
            team_id=db_repo.team_id,
            repo_id=db_repo.id,
            agent_type="review",
            title=f"AI Review: PR #{pr_id}",
            input_data={
                "pr_number": pr_id,
                "repo_full_name": repo_full_name,
                "triggered_by": "bitbucket_comment",
            },
        )
        agent_type = "review"
    else:
        return

    await session.flush()

    logger.info("Task created from AI command", extra={"task_id": str(task.id), "agent_type": agent_type})

    producer = TaskProducer()
    await producer.enqueue_task(
        task_id=str(task.id),
        agent_type=agent_type,
        payload=task.input_data,
    )
