"""Shared test fixtures for the AI-Driven project."""

from __future__ import annotations

import hashlib
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


@pytest.fixture
def mock_session():
    """AsyncSession mock for database operations."""
    session = AsyncMock()
    session.execute = AsyncMock()
    session.flush = AsyncMock()
    session.commit = AsyncMock()
    session.rollback = AsyncMock()
    session.add = MagicMock()
    session.get = AsyncMock(return_value=None)
    return session


@pytest.fixture
def mock_sqs():
    """Mock SQS client that captures sent messages."""
    sqs = MagicMock()
    sqs.send_message = MagicMock(return_value={"MessageId": "test-msg-id-001"})
    return sqs


@pytest.fixture
def test_team_id():
    return uuid.UUID("00000000-0000-0000-0000-000000000001")


@pytest.fixture
def test_repo_id():
    return uuid.UUID("00000000-0000-0000-0000-000000000002")


@pytest.fixture
def test_team(test_team_id):
    """A pre-created Team object for tests."""
    team = MagicMock()
    team.id = test_team_id
    team.name = "test-team"
    team.api_key_hash = hashlib.sha256(b"test-api-key").hexdigest()
    team.is_active = True
    team.max_concurrent_tasks = 5
    team.created_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return team


@pytest.fixture
def test_repo(test_team_id, test_repo_id):
    """A pre-created Repository object linked to test_team."""
    repo = MagicMock()
    repo.id = test_repo_id
    repo.team_id = test_team_id
    repo.scm_type = "github"
    repo.full_name = "test-org/test-repo"
    repo.clone_url = "https://github.com/test-org/test-repo.git"
    repo.default_branch = "main"
    repo.is_active = True
    repo.settings_json = {"jira_projects": ["TEST"]}
    repo.created_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
    return repo


@pytest.fixture
def mock_webhook_event_store():
    """Mock WebhookEventStore that always returns True (no duplicates)."""
    with patch("ai_driven.db.dynamo.models.WebhookEventStore") as mock_cls:
        instance = MagicMock()
        instance.record_event = MagicMock(return_value=True)
        mock_cls.return_value = instance
        yield instance


@pytest.fixture
def mock_webhook_event_store_duplicate():
    """Mock WebhookEventStore that returns False (duplicate detected)."""
    with patch("ai_driven.db.dynamo.models.WebhookEventStore") as mock_cls:
        instance = MagicMock()
        instance.record_event = MagicMock(return_value=False)
        mock_cls.return_value = instance
        yield instance


@pytest.fixture
def mock_task_producer():
    """Mock TaskProducer that captures enqueued tasks."""
    with patch("ai_driven.queue.producer.TaskProducer") as mock_cls:
        instance = MagicMock()
        instance.enqueue_task = AsyncMock(return_value="test-msg-id-001")
        mock_cls.return_value = instance
        yield instance
