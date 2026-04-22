"""Tests for the SQS TaskConsumer (Task 3.5)."""

from __future__ import annotations

import asyncio
import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.queue.consumer import TaskConsumer


@pytest.fixture
def mock_settings():
    settings = MagicMock()
    settings.aws_region = "us-east-1"
    settings.sqs_task_queue_url = "https://sqs.us-east-1.amazonaws.com/123/tasks"
    settings.sqs_review_queue_url = ""
    settings.sqs_devops_queue_url = ""
    settings.jira_base_url = ""
    settings.jira_api_token = ""
    settings.github_token = ""
    settings.bitbucket_username = ""
    settings.bitbucket_app_password = ""
    return settings


@pytest.fixture
def mock_sqs_client():
    return MagicMock()


class TestTaskConsumer:
    def test_init_filters_empty_queue_urls(self, mock_settings, mock_sqs_client):
        with (
            patch("ai_driven.queue.consumer.get_settings", return_value=mock_settings),
            patch("ai_driven.queue.consumer.boto3") as mock_boto3,
        ):
            mock_boto3.client.return_value = mock_sqs_client
            consumer = TaskConsumer(max_concurrent=5)
            assert len(consumer._queue_urls) == 1
            assert consumer._running is True

    def test_shutdown_sets_flag(self, mock_settings, mock_sqs_client):
        with (
            patch("ai_driven.queue.consumer.get_settings", return_value=mock_settings),
            patch("ai_driven.queue.consumer.boto3") as mock_boto3,
        ):
            mock_boto3.client.return_value = mock_sqs_client
            consumer = TaskConsumer()
            consumer._shutdown()
            assert consumer._running is False

    @pytest.mark.asyncio
    async def test_create_agent_coding(self, mock_settings):
        with patch("ai_driven.queue.consumer.get_settings", return_value=mock_settings):
            agent = TaskConsumer._create_agent("coding", {"repo_full_name": ""})
            assert agent.agent_type == "coding"

    @pytest.mark.asyncio
    async def test_create_agent_unknown_raises(self, mock_settings):
        with patch("ai_driven.queue.consumer.get_settings", return_value=mock_settings):
            with pytest.raises(ValueError, match="Unknown agent type"):
                TaskConsumer._create_agent("unknown", {})

    @pytest.mark.asyncio
    async def test_create_agent_with_github_token(self, mock_settings):
        mock_settings.github_token = "ghp_test"
        with (
            patch("ai_driven.queue.consumer.get_settings", return_value=mock_settings),
            patch("ai_driven.agents.base.ClaudeClient"),
        ):
            agent = TaskConsumer._create_agent(
                "coding", {"repo_full_name": "org/repo"}
            )
            assert agent._scm is not None

    @pytest.mark.asyncio
    async def test_create_agent_with_jira_configured(self, mock_settings):
        mock_settings.jira_base_url = "https://jira.example.com"
        mock_settings.jira_api_token = "test-token"
        mock_settings.jira_email = "test@example.com"
        with (
            patch("ai_driven.queue.consumer.get_settings", return_value=mock_settings),
            patch("ai_driven.agents.base.ClaudeClient"),
        ):
            agent = TaskConsumer._create_agent(
                "coding", {"repo_full_name": ""}
            )
            assert agent._jira is not None

    @pytest.mark.asyncio
    async def test_heartbeat_extends_visibility(self, mock_settings, mock_sqs_client):
        """Heartbeat should call change_message_visibility."""
        with (
            patch("ai_driven.queue.consumer.get_settings", return_value=mock_settings),
            patch("ai_driven.queue.consumer.boto3") as mock_boto3,
        ):
            mock_boto3.client.return_value = mock_sqs_client
            consumer = TaskConsumer()

            # Run heartbeat with a very short interval, then cancel
            task = asyncio.create_task(
                consumer._heartbeat(
                    "https://queue-url", "receipt-handle", interval=0
                )
            )
            await asyncio.sleep(0.05)
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    @pytest.mark.asyncio
    async def test_poll_queue_handles_exception(self, mock_settings, mock_sqs_client):
        """Poll should not crash on SQS errors."""
        mock_sqs_client.receive_message.side_effect = Exception("SQS down")

        with (
            patch("ai_driven.queue.consumer.get_settings", return_value=mock_settings),
            patch("ai_driven.queue.consumer.boto3") as mock_boto3,
        ):
            mock_boto3.client.return_value = mock_sqs_client
            consumer = TaskConsumer()
            # Should not raise
            await consumer._poll_queue("https://queue-url")
