"""Integration tests for webhook endpoints."""

from __future__ import annotations

import hashlib
import hmac
import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import httpx
import pytest
from httpx import ASGITransport

from ai_driven.config import Settings
from ai_driven.db.postgres.session import get_session as _real_get_session


def _github_signature(payload: bytes, secret: str) -> str:
    return "sha256=" + hmac.new(secret.encode(), payload, hashlib.sha256).hexdigest()


def _make_mock_task(team_id: uuid.UUID, repo_id: uuid.UUID, agent_type: str = "review"):
    task = MagicMock()
    task.id = uuid.uuid4()
    task.team_id = team_id
    task.repo_id = repo_id
    task.agent_type = agent_type
    task.input_data = {}
    return task


def _make_mock_repo(team_id: uuid.UUID, repo_id: uuid.UUID, full_name: str = "test-org/test-repo"):
    repo = MagicMock()
    repo.id = repo_id
    repo.team_id = team_id
    repo.full_name = full_name
    repo.is_active = True
    repo.settings_json = {"jira_projects": ["TEST"]}
    return repo


@pytest.fixture
def test_settings():
    """Settings with test defaults, avoiding DB/SQS initialization."""
    return Settings(
        environment="local",
        debug=True,
        github_webhook_secret="test-secret",
        postgres_password="test",
        sqs_task_queue_url="https://sqs.us-east-1.amazonaws.com/123/test-tasks",
        sqs_review_queue_url="https://sqs.us-east-1.amazonaws.com/123/test-reviews",
        sqs_devops_queue_url="https://sqs.us-east-1.amazonaws.com/123/test-devops",
    )


@pytest.fixture
def mock_dependencies(test_repo_id, test_team_id):
    """Patch all external dependencies for webhook handlers."""
    mock_session = AsyncMock()
    mock_repo = _make_mock_repo(test_team_id, test_repo_id)

    # Mock RepoRepository
    mock_repo_repo_instance = MagicMock()
    mock_repo_repo_instance.get_by_full_name = AsyncMock(return_value=mock_repo)

    # Mock TaskRepository
    mock_task = _make_mock_task(test_team_id, test_repo_id)
    mock_task_repo_instance = MagicMock()
    mock_task_repo_instance.create = AsyncMock(return_value=mock_task)

    # Mock WebhookEventStore
    mock_event_store = MagicMock()
    mock_event_store.record_event = MagicMock(return_value=True)

    # Mock TaskProducer
    mock_producer = MagicMock()
    mock_producer.enqueue_task = AsyncMock(return_value="msg-001")

    patches = {
        "event_store": patch("ai_driven.api.webhooks.github.WebhookEventStore", return_value=mock_event_store),
        "event_store_bb": patch("ai_driven.api.webhooks.bitbucket.WebhookEventStore", return_value=mock_event_store),
        "event_store_jira": patch("ai_driven.api.webhooks.jira.WebhookEventStore", return_value=mock_event_store),
        "event_store_dd": patch("ai_driven.api.webhooks.datadog.WebhookEventStore", return_value=mock_event_store),
        "repo_repo_gh": patch("ai_driven.api.webhooks.github.RepoRepository", return_value=mock_repo_repo_instance),
        "repo_repo_bb": patch("ai_driven.api.webhooks.bitbucket.RepoRepository", return_value=mock_repo_repo_instance),
        "task_repo_gh": patch("ai_driven.api.webhooks.github.TaskRepository", return_value=mock_task_repo_instance),
        "task_repo_bb": patch("ai_driven.api.webhooks.bitbucket.TaskRepository", return_value=mock_task_repo_instance),
        "task_repo_jira": patch("ai_driven.api.webhooks.jira.TaskRepository", return_value=mock_task_repo_instance),
        "task_repo_dd": patch("ai_driven.api.webhooks.datadog.TaskRepository", return_value=mock_task_repo_instance),
        "producer_gh": patch("ai_driven.api.webhooks.github.TaskProducer", return_value=mock_producer),
        "producer_bb": patch("ai_driven.api.webhooks.bitbucket.TaskProducer", return_value=mock_producer),
        "producer_jira": patch("ai_driven.api.webhooks.jira.TaskProducer", return_value=mock_producer),
        "producer_dd": patch("ai_driven.api.webhooks.datadog.TaskProducer", return_value=mock_producer),
        "settings": patch("ai_driven.api.webhooks.github.get_settings", return_value=Settings(
            environment="local", debug=True, github_webhook_secret="test-secret", postgres_password="test",
        )),
    }

    return {
        "patches": patches,
        "session": mock_session,
        "event_store": mock_event_store,
        "repo_repo": mock_repo_repo_instance,
        "task_repo": mock_task_repo_instance,
        "producer": mock_producer,
        "task": mock_task,
        "repo": mock_repo,
    }


@pytest.fixture
async def client(mock_dependencies):
    """HTTPX AsyncClient using ASGITransport against the FastAPI app."""
    active_patches = {}
    for name, p in mock_dependencies["patches"].items():
        active_patches[name] = p.start()

    # Patch session and engine at module level to avoid DB connection during app creation
    with (
        patch("ai_driven.db.postgres.session.engine"),
        patch("ai_driven.db.postgres.session.async_session_factory"),
        patch("ai_driven.db.postgres.session.init_db", new_callable=AsyncMock),
        patch("ai_driven.observability.logging.setup_logging"),
        patch("ai_driven.observability.tracing.setup_tracing"),
        patch("ai_driven.config.get_settings", return_value=Settings(
            environment="local", debug=True, github_webhook_secret="test-secret", postgres_password="test",
        )),
    ):
        from ai_driven.main import create_app

        app = create_app()

        # Use FastAPI's dependency_overrides with the stable original reference
        mock_session = mock_dependencies["session"]

        async def _override_get_session():
            yield mock_session

        app.dependency_overrides[_real_get_session] = _override_get_session

        transport = ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
            yield c

    for p in mock_dependencies["patches"].values():
        p.stop()


# --- GitHub Tests ---


async def test_github_pr_opened_creates_review_task(client, mock_dependencies):
    """POST valid PR opened webhook returns 200 and creates a review task."""
    payload = {
        "action": "opened",
        "pull_request": {
            "id": 12345,
            "number": 42,
            "title": "Add feature X",
            "html_url": "https://github.com/test-org/test-repo/pull/42",
            "head": {"ref": "feature/x"},
            "base": {"ref": "main"},
            "updated_at": "2026-04-20T10:00:00Z",
        },
        "repository": {"full_name": "test-org/test-repo"},
    }
    body = json.dumps(payload).encode()
    sig = _github_signature(body, "test-secret")

    response = await client.post(
        "/webhooks/github",
        content=body,
        headers={
            "X-GitHub-Event": "pull_request",
            "X-Hub-Signature-256": sig,
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    assert response.json() == {"status": "accepted"}
    mock_dependencies["task_repo"].create.assert_called_once()
    call_kwargs = mock_dependencies["task_repo"].create.call_args.kwargs
    assert call_kwargs["agent_type"] == "review"
    assert call_kwargs["input_data"]["pr_number"] == 42


async def test_github_webhook_invalid_signature_returns_401(client):
    """POST with bad signature returns 401."""
    payload = {"action": "opened", "pull_request": {"id": 1}, "repository": {"full_name": "x/y"}}
    body = json.dumps(payload).encode()

    response = await client.post(
        "/webhooks/github",
        content=body,
        headers={
            "X-GitHub-Event": "pull_request",
            "X-Hub-Signature-256": "sha256=invalid",
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 401


async def test_github_ai_code_command_creates_coding_task(client, mock_dependencies):
    """POST issue_comment with '/ai-code PROJ-123' creates a coding task."""
    payload = {
        "action": "created",
        "comment": {
            "id": 99887766,
            "body": "/ai-code PROJ-123",
        },
        "issue": {"number": 42},
        "repository": {"full_name": "test-org/test-repo"},
    }
    body = json.dumps(payload).encode()
    sig = _github_signature(body, "test-secret")

    response = await client.post(
        "/webhooks/github",
        content=body,
        headers={
            "X-GitHub-Event": "issue_comment",
            "X-Hub-Signature-256": sig,
            "Content-Type": "application/json",
        },
    )

    assert response.status_code == 200
    mock_dependencies["task_repo"].create.assert_called_once()
    call_kwargs = mock_dependencies["task_repo"].create.call_args.kwargs
    assert call_kwargs["agent_type"] == "coding"
    assert call_kwargs["jira_key"] == "PROJ-123"


# --- Bitbucket Tests ---


async def test_bitbucket_pr_created_returns_accepted(client, mock_dependencies):
    """POST Bitbucket PR created webhook returns 200."""
    payload = {
        "pullrequest": {
            "id": 10,
            "title": "Fix bug",
            "links": {"html": {"href": "https://bitbucket.org/test-org/test-repo/pull-requests/10"}},
            "source": {"branch": {"name": "bugfix/x"}},
            "destination": {"branch": {"name": "main"}},
        },
        "repository": {"full_name": "test-org/test-repo"},
    }

    response = await client.post(
        "/webhooks/bitbucket",
        json=payload,
        headers={"X-Event-Key": "pullrequest:created"},
    )

    assert response.status_code == 200
    assert response.json() == {"status": "accepted"}
    mock_dependencies["task_repo"].create.assert_called_once()


# --- Jira Tests ---


async def test_jira_issue_transition_to_ready(client, mock_dependencies):
    """POST Jira issue_updated with status change to 'Ready for Dev' creates coding task."""
    # For Jira _find_repo_for_jira calls session.execute directly (not through RepoRepository)
    mock_repo = mock_dependencies["repo"]
    scalars_mock = MagicMock()
    scalars_mock.all.return_value = [mock_repo]
    mock_result = MagicMock()
    mock_result.scalars.return_value = scalars_mock
    mock_dependencies["session"].execute = AsyncMock(return_value=mock_result)

    payload = {
        "webhookEvent": "jira:issue_updated",
        "issue": {
            "key": "TEST-42",
            "fields": {
                "summary": "Implement login page",
                "labels": [],
            },
        },
        "changelog": {
            "items": [
                {
                    "field": "status",
                    "fromString": "To Do",
                    "toString": "Ready for Dev",
                }
            ]
        },
    }

    response = await client.post(
        "/webhooks/jira",
        json=payload,
    )

    assert response.status_code == 200
    mock_dependencies["task_repo"].create.assert_called_once()
    call_kwargs = mock_dependencies["task_repo"].create.call_args.kwargs
    assert call_kwargs["agent_type"] == "coding"
    assert call_kwargs["jira_key"] == "TEST-42"


# --- DataDog Tests ---


async def test_datadog_alert_returns_accepted(client, mock_dependencies):
    """POST DataDog alert webhook returns 200 and creates devops task."""
    # Mock session.execute for finding a repository (datadog calls session.execute directly)
    mock_result = MagicMock()
    mock_result.scalar_one_or_none.return_value = mock_dependencies["repo"]
    mock_dependencies["session"].execute = AsyncMock(return_value=mock_result)

    payload = {
        "id": "alert-001",
        "title": "High error rate on /api/orders",
        "alert_type": "error",
        "tags": ["env:prod", "repo:test-org/test-repo"],
        "body": "Error rate exceeded threshold.",
    }

    response = await client.post(
        "/webhooks/datadog",
        json=payload,
    )

    assert response.status_code == 200
    assert response.json() == {"status": "accepted"}
    mock_dependencies["task_repo"].create.assert_called_once()
    call_kwargs = mock_dependencies["task_repo"].create.call_args.kwargs
    assert call_kwargs["agent_type"] == "devops"


# --- Deduplication Tests ---


async def test_duplicate_webhook_ignored(client, mock_dependencies):
    """POST same event twice, second should not create a task."""
    # First call: record_event returns True, second call: returns False
    mock_dependencies["event_store"].record_event = MagicMock(side_effect=[True, False])

    payload = {
        "action": "opened",
        "pull_request": {
            "id": 12345,
            "number": 42,
            "title": "Add feature X",
            "html_url": "https://github.com/test-org/test-repo/pull/42",
            "head": {"ref": "feature/x"},
            "base": {"ref": "main"},
            "updated_at": "2026-04-20T10:00:00Z",
        },
        "repository": {"full_name": "test-org/test-repo"},
    }
    body = json.dumps(payload).encode()
    sig = _github_signature(body, "test-secret")
    headers = {
        "X-GitHub-Event": "pull_request",
        "X-Hub-Signature-256": sig,
        "Content-Type": "application/json",
    }

    # First request
    resp1 = await client.post("/webhooks/github", content=body, headers=headers)
    assert resp1.status_code == 200

    # Second request (duplicate)
    resp2 = await client.post("/webhooks/github", content=body, headers=headers)
    assert resp2.status_code == 200

    # Task should only be created once
    assert mock_dependencies["task_repo"].create.call_count == 1
