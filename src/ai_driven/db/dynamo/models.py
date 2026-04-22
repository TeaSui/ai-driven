"""DynamoDB models for events and state machines."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

import boto3
from boto3.dynamodb.conditions import Key

from ai_driven.config import get_settings


def _get_table(table_name: str) -> Any:
    settings = get_settings()
    kwargs: dict[str, Any] = {"region_name": settings.aws_region}
    if settings.dynamodb_endpoint_url:
        kwargs["endpoint_url"] = settings.dynamodb_endpoint_url
    dynamodb = boto3.resource("dynamodb", **kwargs)
    return dynamodb.Table(f"{settings.dynamodb_table_prefix}-{table_name}")


class AgentSessionStore:
    """Real-time agent execution state in DynamoDB."""

    def __init__(self) -> None:
        self._table = _get_table("agent-sessions")

    def put_state(self, session_id: str, state: dict[str, Any]) -> None:
        self._table.put_item(Item={
            "session_id": session_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "state": state,
        })

    def get_latest_state(self, session_id: str) -> dict[str, Any] | None:
        response = self._table.query(
            KeyConditionExpression=Key("session_id").eq(session_id),
            ScanIndexForward=False,
            Limit=1,
        )
        items = response.get("Items", [])
        return items[0]["state"] if items else None

    def get_history(self, session_id: str, limit: int = 50) -> list[dict[str, Any]]:
        response = self._table.query(
            KeyConditionExpression=Key("session_id").eq(session_id),
            ScanIndexForward=False,
            Limit=limit,
        )
        return response.get("Items", [])


class WebhookEventStore:
    """Webhook event deduplication and audit trail."""

    def __init__(self) -> None:
        self._table = _get_table("webhook-events")

    def record_event(
        self, event_id: str, source: str, event_type: str, payload: dict[str, Any]
    ) -> bool:
        """Record a webhook event. Returns False if duplicate."""
        try:
            self._table.put_item(
                Item={
                    "event_id": event_id,
                    "source_timestamp": f"{source}#{datetime.now(timezone.utc).isoformat()}",
                    "event_type": event_type,
                    "payload": payload,
                },
                ConditionExpression="attribute_not_exists(event_id)",
            )
            return True
        except self._table.meta.client.exceptions.ConditionalCheckFailedException:
            return False


class TaskStateMachineStore:
    """State machine transitions for long-running tasks."""

    def __init__(self) -> None:
        self._table = _get_table("task-state-machine")

    def record_transition(
        self, task_id: str, from_state: str, to_state: str, metadata: dict[str, Any] | None = None
    ) -> None:
        self._table.put_item(Item={
            "task_id": task_id,
            "state_timestamp": f"{to_state}#{datetime.now(timezone.utc).isoformat()}",
            "from_state": from_state,
            "to_state": to_state,
            "metadata": metadata or {},
        })

    def get_transitions(self, task_id: str) -> list[dict[str, Any]]:
        response = self._table.query(
            KeyConditionExpression=Key("task_id").eq(task_id),
            ScanIndexForward=True,
        )
        return response.get("Items", [])
