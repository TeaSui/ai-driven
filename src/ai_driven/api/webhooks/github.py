"""GitHub webhook handler."""

from __future__ import annotations

import hashlib
import hmac
import logging
import re
import uuid
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.config import get_settings
from ai_driven.db.dynamo.models import WebhookEventStore
from ai_driven.db.postgres.repository import RepoRepository, TaskRepository
from ai_driven.db.postgres.session import get_session
from ai_driven.observability.trace_context import bind_trace, clear_trace
from ai_driven.queue.producer import TaskProducer

logger = logging.getLogger(__name__)
router = APIRouter()

AI_COMMAND_PATTERN = re.compile(r"^/ai-(code|review|explain|fix)(?:\s+(\S+))?", re.MULTILINE)


def _verify_signature(payload: bytes, signature: str, secret: str) -> bool:
    """Verify GitHub webhook HMAC-SHA256 signature."""
    expected = "sha256=" + hmac.new(
        secret.encode(), payload, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature)


def _build_event_id(payload: dict[str, Any], event_type: str) -> str:
    """Build a deterministic dedup key from webhook payload."""
    if event_type == "pull_request":
        pr = payload.get("pull_request", {})
        action = payload.get("action", "")
        return f"github-pr-{pr.get('id', '')}-{action}-{pr.get('updated_at', '')}"
    if event_type == "issue_comment":
        comment = payload.get("comment", {})
        return f"github-comment-{comment.get('id', '')}"
    return f"github-{event_type}-{uuid.uuid4()}"


@router.post("")
async def github_webhook(
    request: Request,
    session: AsyncSession = Depends(get_session),
    x_github_event: str = Header(..., alias="X-GitHub-Event"),
    x_hub_signature_256: str = Header("", alias="X-Hub-Signature-256"),
) -> dict[str, str]:
    settings = get_settings()
    body = await request.body()

    # Generate a trace_id for the webhook request lifecycle
    trace_id = bind_trace()

    if settings.github_webhook_secret and x_hub_signature_256:
        if not _verify_signature(body, x_hub_signature_256, settings.github_webhook_secret):
            clear_trace()
            raise HTTPException(status_code=401, detail="Invalid signature")

    payload: dict[str, Any] = await request.json()

    logger.info("github_webhook_received", extra={"event": x_github_event, "action": payload.get("action", "N/A")})

    if x_github_event == "pull_request":
        await _handle_pr_event(payload, session)
    elif x_github_event == "pull_request_review_comment":
        await _handle_pr_comment(payload, session)
    elif x_github_event == "issue_comment":
        await _handle_issue_comment(payload, session)

    clear_trace()
    return {"status": "accepted"}


async def _handle_pr_event(payload: dict[str, Any], session: AsyncSession) -> None:
    """Handle pull request events (opened, synchronize)."""
    action = payload.get("action")
    pr = payload.get("pull_request", {})
    repo_data = payload.get("repository", {})
    repo_full_name = repo_data.get("full_name", "")

    if action not in ("opened", "synchronize"):
        logger.info("Ignoring PR action", extra={"action": action, "repo": repo_full_name})
        return

    # Skip self-review: don't review PRs created by the AI bot itself
    pr_author = pr.get("user", {}).get("login", "")
    if pr_author.endswith("[bot]") or pr_author == "ai-driven-bot":
        logger.info("Skipping self-review for bot PR", extra={"author": pr_author})
        return

    # Skip if PR has "skip-ai-review" label
    labels = [lbl.get("name", "") for lbl in pr.get("labels", [])]
    if "skip-ai-review" in labels:
        logger.info("Skipping review: skip-ai-review label present")
        return

    # 1. Deduplication
    event_store = WebhookEventStore()
    event_id = _build_event_id(payload, "pull_request")
    if not event_store.record_event(event_id, "github", "pull_request", {"action": action, "pr": pr.get("number")}):
        logger.info("Duplicate webhook event, skipping", extra={"event_id": event_id})
        return

    # 2. Look up repository
    repo_repo = RepoRepository(session)
    db_repo = await repo_repo.get_by_full_name(repo_full_name)
    if not db_repo:
        logger.warning("Repository not registered", extra={"full_name": repo_full_name})
        return

    # 3. Check team auto_review_enabled setting
    team_settings = db_repo.settings_json if hasattr(db_repo, "settings_json") else None
    from ai_driven.agents.reviewer.config import ReviewConfig
    review_config = ReviewConfig.from_settings(team_settings)
    if not review_config.auto_review_enabled:
        logger.info("Auto-review disabled for team", extra={"repo": repo_full_name})
        return
    if action == "synchronize" and not review_config.review_on_update:
        logger.info("Review-on-update disabled for team", extra={"repo": repo_full_name})
        return

    # 4. Create review task
    task_repo = TaskRepository(session)
    task = await task_repo.create(
        team_id=db_repo.team_id,
        repo_id=db_repo.id,
        agent_type="review",
        title=f"Review PR #{pr.get('number')}: {pr.get('title', '')}",
        input_data={
            "pr_number": pr.get("number"),
            "pr_title": pr.get("title", ""),
            "pr_description": pr.get("body", ""),
            "pr_url": pr.get("html_url", ""),
            "source_branch": pr.get("head", {}).get("ref", ""),
            "target_branch": pr.get("base", {}).get("ref", ""),
            "repo_full_name": repo_full_name,
            "action": action,
            "diff_lines": pr.get("additions", 0) + pr.get("deletions", 0),
        },
    )
    await session.flush()

    logger.info("Review task created", extra={"task_id": str(task.id), "pr": pr.get("number")})

    # 5. Enqueue to SQS
    producer = TaskProducer()
    await producer.enqueue_task(
        task_id=str(task.id),
        agent_type="review",
        payload=task.input_data,
    )


async def _handle_pr_comment(payload: dict[str, Any], session: AsyncSession) -> None:
    """Handle PR review comments — supports /ai-explain and /ai-fix commands."""
    action = payload.get("action")
    if action != "created":
        return

    comment = payload.get("comment", {})
    body = comment.get("body", "")
    match = AI_COMMAND_PATTERN.search(body)
    if not match:
        return

    command = match.group(1)
    if command not in ("explain", "fix"):
        return

    repo_data = payload.get("repository", {})
    repo_full_name = repo_data.get("full_name", "")
    pr = payload.get("pull_request", {})
    pr_number = pr.get("number")

    # Deduplication
    event_store = WebhookEventStore()
    event_id = f"github-review-comment-{comment.get('id', '')}"
    if not event_store.record_event(event_id, "github", "pr_review_comment", {"command": command}):
        return

    # Look up repository
    repo_repo = RepoRepository(session)
    db_repo = await repo_repo.get_by_full_name(repo_full_name)
    if not db_repo:
        return

    task_repo = TaskRepository(session)

    # Build context from the review comment thread
    comment_context = {
        "file_path": comment.get("path", ""),
        "line": comment.get("line") or comment.get("original_line"),
        "diff_hunk": comment.get("diff_hunk", ""),
        "comment_body": body,
        "in_reply_to_id": comment.get("in_reply_to_id"),
    }

    if command == "explain":
        task = await task_repo.create(
            team_id=db_repo.team_id,
            repo_id=db_repo.id,
            agent_type="review",
            title=f"Explain: {comment_context['file_path']}:{comment_context.get('line', '?')}",
            input_data={
                "pr_number": pr_number,
                "repo_full_name": repo_full_name,
                "triggered_by": "review_comment",
                "mode": "explain",
                "comment_context": comment_context,
            },
        )
    else:  # fix
        task = await task_repo.create(
            team_id=db_repo.team_id,
            repo_id=db_repo.id,
            agent_type="coding",
            title=f"Fix: {comment_context['file_path']}:{comment_context.get('line', '?')}",
            input_data={
                "pr_number": pr_number,
                "repo_full_name": repo_full_name,
                "triggered_by": "review_comment",
                "mode": "fix",
                "comment_context": comment_context,
                "source_branch": pr.get("head", {}).get("ref", ""),
            },
        )

    await session.flush()

    logger.info(
        "Task created from review comment",
        extra={"task_id": str(task.id), "command": command},
    )

    agent_type = "review" if command == "explain" else "coding"
    producer = TaskProducer()
    await producer.enqueue_task(
        task_id=str(task.id),
        agent_type=agent_type,
        payload=task.input_data,
    )


async def _handle_issue_comment(payload: dict[str, Any], session: AsyncSession) -> None:
    """Handle issue/PR comments (for slash commands like /ai-code, /ai-review)."""
    comment_body = payload.get("comment", {}).get("body", "")
    repo_data = payload.get("repository", {})
    repo_full_name = repo_data.get("full_name", "")
    issue = payload.get("issue", {})

    match = AI_COMMAND_PATTERN.search(comment_body)
    if not match:
        return

    command = match.group(1)  # "code" or "review"
    argument = match.group(2)  # e.g. "PROJ-123" or None

    logger.info("AI command detected", extra={"command": f"/ai-{command}", "argument": argument})

    # Deduplication
    event_store = WebhookEventStore()
    comment_id = payload.get("comment", {}).get("id", "")
    event_id = f"github-comment-{comment_id}"
    if not event_store.record_event(event_id, "github", "issue_comment", {"command": command}):
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
                "triggered_by": "github_comment",
                "issue_number": issue.get("number"),
            },
        )
        agent_type = "coding"
    elif command == "review":
        pr_number = issue.get("number")
        task = await task_repo.create(
            team_id=db_repo.team_id,
            repo_id=db_repo.id,
            agent_type="review",
            title=f"AI Review: PR #{pr_number}",
            input_data={
                "pr_number": pr_number,
                "repo_full_name": repo_full_name,
                "triggered_by": "github_comment",
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
