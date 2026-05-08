"""Jira webhook handler."""

from __future__ import annotations

import logging
from typing import Any

from fastapi import APIRouter, Depends, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.db.dynamo.models import WebhookEventStore
from ai_driven.db.postgres.models import Repository as RepositoryModel
from ai_driven.db.postgres.repository import TaskRepository
from ai_driven.db.postgres.session import get_session
from ai_driven.observability.trace_context import bind_trace, clear_trace
from ai_driven.queue.producer import TaskProducer

logger = logging.getLogger(__name__)
router = APIRouter()

READY_STATUSES = {"ready for dev", "ready for development"}


def _adf_to_text(adf: Any) -> str:
    """Flatten a Jira Atlassian Document Format node (or plain string) to text.

    Jira v3 REST returns rich-text fields (``description``, custom AC
    field) as ADF JSON; v2/plain text returns strings. Handle both so
    the orchestrator classifier sees readable content.
    """
    if adf is None:
        return ""
    if isinstance(adf, str):
        return adf
    if not isinstance(adf, dict):
        return ""
    out: list[str] = []

    def walk(node: Any) -> None:
        if isinstance(node, list):
            for n in node:
                walk(n)
            return
        if not isinstance(node, dict):
            return
        if node.get("type") == "text" and isinstance(node.get("text"), str):
            out.append(node["text"])
        for child in node.get("content", []) or []:
            walk(child)
        if node.get("type") in {"paragraph", "heading", "listItem"}:
            out.append("\n")

    walk(adf)
    return " ".join(s.strip() for s in "".join(out).splitlines() if s.strip())


@router.post("")
async def jira_webhook(
    request: Request,
    session: AsyncSession = Depends(get_session),
) -> dict[str, str]:
    payload: dict[str, Any] = await request.json()
    event = payload.get("webhookEvent", "unknown")

    bind_trace()
    logger.info("jira_webhook_received", extra={"event": event})

    if event == "jira:issue_updated":
        await _handle_issue_updated(payload, session)
    elif event == "jira:issue_created":
        await _handle_issue_created(payload)

    clear_trace()
    return {"status": "accepted"}


async def _handle_issue_updated(payload: dict[str, Any], session: AsyncSession) -> None:
    """Handle Jira issue update -- check for status transitions to trigger coding agent."""
    changelog = payload.get("changelog", {})
    issue = payload.get("issue", {})
    issue_key = issue.get("key", "")
    fields = issue.get("fields", {})

    for item in changelog.get("items", []):
        if item.get("field") != "status":
            continue

        from_status = item.get("fromString", "")
        to_status = item.get("toString", "")
        logger.info(
            "Issue status transition",
            extra={"issue_key": issue_key, "from": from_status, "to": to_status},
        )

        if to_status.lower() not in READY_STATUSES:
            continue

        # Deduplication
        event_store = WebhookEventStore()
        event_id = f"jira-transition-{issue_key}-{to_status}"
        if not event_store.record_event(event_id, "jira", "issue_transition", {"issue_key": issue_key}):
            logger.info("Duplicate transition event, skipping", extra={"event_id": event_id})
            return

        # Look up repository via project key or labels
        project_key = issue_key.split("-")[0] if "-" in issue_key else ""
        labels = fields.get("labels", [])

        db_repo = await _find_repo_for_jira(session, project_key, labels)
        if not db_repo:
            logger.warning(
                "No repository found for Jira issue",
                extra={"issue_key": issue_key, "project_key": project_key},
            )
            return

        # Forward labels, description, issue type, and acceptance
        # criteria into the task payload so the orchestrator's tier
        # classifier can see the actual trust-boundary signal (labels
        # like "security"/"auth"/"payments"/"pii"). Without these the
        # classifier only sees the summary and defaults everything to
        # fast_path — which skips specialists entirely.
        description = _adf_to_text(fields.get("description"))
        issue_type = (fields.get("issuetype") or {}).get("name", "")
        from ai_driven.config import get_settings as _get_settings
        ac_field = _get_settings().jira_acceptance_criteria_field
        acceptance_criteria = _adf_to_text(fields.get(ac_field)) if ac_field else ""

        # Create coding task
        task_repo = TaskRepository(session)
        task = await task_repo.create(
            team_id=db_repo.team_id,
            repo_id=db_repo.id,
            agent_type="coding",
            jira_key=issue_key,
            title=f"Coding: {issue_key} - {fields.get('summary', '')}",
            input_data={
                "jira_key": issue_key,
                "summary": fields.get("summary", ""),
                "description": description,
                "acceptance_criteria": acceptance_criteria,
                "issue_type": issue_type,
                "labels": labels,
                "repo_full_name": db_repo.full_name,
                "triggered_by": "jira_transition",
                "from_status": from_status,
                "to_status": to_status,
            },
        )
        await session.flush()

        logger.info("Coding task created from Jira transition", extra={"task_id": str(task.id), "issue_key": issue_key})

        producer = TaskProducer()
        await producer.enqueue_task(
            task_id=str(task.id),
            agent_type="coding",
            payload=task.input_data,
        )


async def _find_repo_for_jira(
    session: AsyncSession, project_key: str, labels: list[str]
) -> RepositoryModel | None:
    """Find the repository associated with a Jira project via settings_json or labels."""
    # Strategy 1: Check repositories whose settings_json contains the project key
    result = await session.execute(
        select(RepositoryModel).where(
            RepositoryModel.is_active.is_(True),
        )
    )
    repos = result.scalars().all()

    for repo in repos:
        settings = repo.settings_json or {}
        jira_projects = settings.get("jira_projects", [])
        if project_key in jira_projects:
            return repo

    # Strategy 2: Match repo by label (label format: "repo:owner/name")
    for label in labels:
        if label.startswith("repo:"):
            repo_name = label[5:]
            for repo in repos:
                if repo.full_name == repo_name:
                    return repo

    # Strategy 3: Return the first active repo for the team if only one exists
    if len(repos) == 1:
        return repos[0]

    return None


async def _handle_issue_created(payload: dict[str, Any]) -> None:
    """Handle new Jira issue creation."""
    issue = payload.get("issue", {})
    logger.info("New issue created", extra={"issue_key": issue.get("key", "")})
