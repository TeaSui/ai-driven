"""Slack Events API webhook handler.

Handles two request types:
1. URL verification challenge (Slack sends this during app setup)
2. Event callbacks (app_mention, message, etc.)

For production deployments that use Slack's HTTP-based Events API
instead of Socket Mode.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
import re
import time
from typing import Any

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

from ai_driven.config import get_settings
from ai_driven.observability.trace_context import bind_trace, clear_trace

logger = logging.getLogger(__name__)

router = APIRouter()

JIRA_KEY_SEARCH_PATTERN = re.compile(r"\b([A-Z][A-Z0-9]+-\d+)\b")


class SlackChallengeResponse(BaseModel):
    """Response for Slack URL verification challenge."""

    challenge: str


class SlackEventPayload(BaseModel):
    """Top-level Slack event payload."""

    type: str
    token: str = ""
    challenge: str = ""
    team_id: str = ""
    event: dict[str, Any] = Field(default_factory=dict)
    event_id: str = ""


def _verify_slack_signature(
    body: bytes,
    timestamp: str,
    signature: str,
    signing_secret: str,
) -> bool:
    """Verify Slack request signature (v0 scheme).

    See: https://api.slack.com/authentication/verifying-requests-from-slack
    """
    if not signing_secret:
        return False

    # Reject requests older than 5 minutes to prevent replay attacks
    try:
        req_ts = int(timestamp)
    except (ValueError, TypeError):
        return False
    if abs(time.time() - req_ts) > 300:
        return False

    sig_basestring = f"v0:{timestamp}:{body.decode('utf-8')}"
    computed = (
        "v0="
        + hmac.new(
            signing_secret.encode(), sig_basestring.encode(), hashlib.sha256
        ).hexdigest()
    )
    return hmac.compare_digest(computed, signature)


def parse_mention_intent(text: str) -> dict[str, Any]:
    """Parse an @mention message for intent.

    Returns a dict with:
    - ``jira_key``: extracted Jira key or None
    - ``raw_text``: the cleaned text with bot mention removed
    """
    # Strip bot mention tags
    cleaned = re.sub(r"<@[A-Z0-9]+>", "", text).strip()

    jira_match = JIRA_KEY_SEARCH_PATTERN.search(cleaned)
    return {
        "jira_key": jira_match.group(1) if jira_match else None,
        "raw_text": cleaned,
    }


@router.post("")
async def slack_events(
    request: Request,
    x_slack_request_timestamp: str = Header("", alias="X-Slack-Request-Timestamp"),
    x_slack_signature: str = Header("", alias="X-Slack-Signature"),
) -> dict[str, Any]:
    """Handle incoming Slack Events API requests."""
    settings = get_settings()
    body = await request.body()

    bind_trace()

    # Verify signature when signing secret is configured
    if settings.slack_signing_secret:
        if not _verify_slack_signature(
            body, x_slack_request_timestamp, x_slack_signature, settings.slack_signing_secret
        ):
            raise HTTPException(status_code=401, detail="Invalid Slack signature")

    payload = await request.json()

    # URL verification challenge
    if payload.get("type") == "url_verification":
        challenge = payload.get("challenge", "")
        if not challenge:
            raise HTTPException(status_code=400, detail="Missing challenge")
        return {"challenge": challenge}

    # Event callback
    if payload.get("type") == "event_callback":
        event = payload.get("event", {})
        event_type = event.get("type", "")

        logger.info(
            "Slack event received",
            extra={"event_type": event_type, "event_id": payload.get("event_id", "")},
        )

        if event_type == "app_mention":
            intent = parse_mention_intent(event.get("text", ""))
            logger.info(
                "Mention intent parsed",
                extra={"jira_key": intent["jira_key"], "raw_text": intent["raw_text"][:100]},
            )

            if intent["jira_key"]:
                # Dispatch coding task asynchronously
                await _dispatch_mention_task(
                    jira_key=intent["jira_key"],
                    channel=event.get("channel", ""),
                    thread_ts=event.get("thread_ts") or event.get("ts", ""),
                    user=event.get("user", ""),
                    raw_text=intent["raw_text"],
                )
            clear_trace()
            return {"status": "accepted"}

    clear_trace()
    return {"status": "ok"}


async def _dispatch_mention_task(
    *,
    jira_key: str,
    channel: str,
    thread_ts: str,
    user: str,
    raw_text: str,
) -> None:
    """Create and enqueue a coding task from a Slack @mention.

    This is a best-effort operation; errors are logged but do not
    propagate back to the Slack request handler (Slack expects a quick 200).
    """
    try:
        from ai_driven.db.postgres.repository import RepoRepository, TaskRepository
        from ai_driven.db.postgres.session import async_session_factory
        from ai_driven.queue.producer import TaskProducer

        async with async_session_factory() as session:
            repo_repo = RepoRepository(session)
            db_repo = await repo_repo.get_first_active()
            if not db_repo:
                logger.warning("No active repo for Slack mention dispatch")
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
                    "triggered_by": "slack_mention_webhook",
                    "slack_channel": channel,
                    "slack_thread_ts": thread_ts,
                    "slack_user": user,
                    "original_message": raw_text[:500],
                },
            )
            await session.commit()

            producer = TaskProducer()
            await producer.enqueue_task(
                task_id=str(task.id),
                agent_type="coding",
                payload=task.input_data,
            )
            logger.info(
                "Dispatched coding task from Slack mention",
                extra={"task_id": str(task.id), "jira_key": jira_key},
            )

    except Exception:
        logger.exception("Failed to dispatch task from Slack mention")
