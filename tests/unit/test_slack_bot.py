"""Tests for Slack bot commands (Task 3.6)."""

from __future__ import annotations

import re
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

JIRA_KEY_PATTERN = re.compile(r"^[A-Z][A-Z0-9]+-\d+$")


class TestJiraKeyPattern:
    """Validate the Jira key regex (same pattern as slack_bot.app)."""

    def test_valid_keys(self):
        assert JIRA_KEY_PATTERN.match("PROJ-123")
        assert JIRA_KEY_PATTERN.match("AB-1")
        assert JIRA_KEY_PATTERN.match("TEST99-42")

    def test_invalid_keys(self):
        assert not JIRA_KEY_PATTERN.match("proj-123")  # lowercase
        assert not JIRA_KEY_PATTERN.match("123-ABC")  # starts with digit
        assert not JIRA_KEY_PATTERN.match("PROJ")  # no number
        assert not JIRA_KEY_PATTERN.match("")
        assert not JIRA_KEY_PATTERN.match("P-")  # no digit after dash


class TestHandleAiCode:
    @pytest.mark.asyncio
    async def test_empty_text_shows_usage(self):
        from slack_bot.app import _handle_ai_code

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": ""}

        await _handle_ai_code(ack, body, say)

        ack.assert_called_once()
        say.assert_called_once()
        assert "Usage" in say.call_args[0][0]

    @pytest.mark.asyncio
    async def test_invalid_jira_key_rejected(self):
        from slack_bot.app import _handle_ai_code

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": "invalid-key"}

        await _handle_ai_code(ack, body, say)

        ack.assert_called_once()
        assert "Invalid Jira key" in say.call_args[0][0]

    @pytest.mark.asyncio
    async def test_repo_not_found(self):
        from slack_bot.app import _handle_ai_code

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": "PROJ-123 in org/nonexistent"}

        mock_session = AsyncMock()
        mock_repo_repo = MagicMock()
        mock_repo_repo.get_by_full_name = AsyncMock(return_value=None)

        with (
            patch("slack_bot.app.async_session_factory") as mock_factory,
            patch("slack_bot.app.RepoRepository", return_value=mock_repo_repo),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(
                return_value=mock_session
            )
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_ai_code(ack, body, say)

        assert "Repository not found" in say.call_args[0][0]

    @pytest.mark.asyncio
    async def test_successful_task_creation(self):
        from slack_bot.app import _handle_ai_code

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": "PROJ-123 in org/repo"}

        task_id = uuid.uuid4()
        mock_repo = MagicMock()
        mock_repo.team_id = uuid.uuid4()
        mock_repo.id = uuid.uuid4()
        mock_repo.full_name = "org/repo"

        mock_task = MagicMock()
        mock_task.id = task_id
        mock_task.input_data = {
            "jira_key": "PROJ-123",
            "repo_full_name": "org/repo",
        }

        mock_session = AsyncMock()
        mock_repo_repo = MagicMock()
        mock_repo_repo.get_by_full_name = AsyncMock(return_value=mock_repo)
        mock_task_repo = MagicMock()
        mock_task_repo.create = AsyncMock(return_value=mock_task)
        mock_producer = MagicMock()
        mock_producer.enqueue_task = AsyncMock()

        with (
            patch("slack_bot.app.async_session_factory") as mock_factory,
            patch("slack_bot.app.RepoRepository", return_value=mock_repo_repo),
            patch("slack_bot.app.TaskRepository", return_value=mock_task_repo),
            patch("slack_bot.app.TaskProducer", return_value=mock_producer),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(
                return_value=mock_session
            )
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_ai_code(ack, body, say)

        assert "Coding task created" in say.call_args[0][0]
        assert "PROJ-123" in say.call_args[0][0]
        mock_producer.enqueue_task.assert_called_once()

    @pytest.mark.asyncio
    async def test_parses_jira_key_without_repo(self):
        """When no 'in org/repo' is given, should use get_first_active."""
        from slack_bot.app import _handle_ai_code

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": "PROJ-456"}

        mock_session = AsyncMock()
        mock_repo_repo = MagicMock()
        mock_repo_repo.get_first_active = AsyncMock(return_value=None)

        with (
            patch("slack_bot.app.async_session_factory") as mock_factory,
            patch("slack_bot.app.RepoRepository", return_value=mock_repo_repo),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(
                return_value=mock_session
            )
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_ai_code(ack, body, say)

        mock_repo_repo.get_first_active.assert_called_once()


class TestHandleAiStatus:
    @pytest.mark.asyncio
    async def test_empty_task_id_shows_usage(self):
        from slack_bot.app import _handle_ai_status

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": ""}

        await _handle_ai_status(ack, body, say)

        ack.assert_called_once()
        assert "Usage" in say.call_args[0][0]

    @pytest.mark.asyncio
    async def test_task_not_found(self):
        from slack_bot.app import _handle_ai_status

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": "nonexistent-id"}

        mock_session = AsyncMock()
        mock_task_repo = MagicMock()
        mock_task_repo.get_by_id = AsyncMock(return_value=None)

        with (
            patch("slack_bot.app.async_session_factory") as mock_factory,
            patch("slack_bot.app.TaskRepository", return_value=mock_task_repo),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(
                return_value=mock_session
            )
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_ai_status(ack, body, say)

        assert "Task not found" in say.call_args[0][0]

    @pytest.mark.asyncio
    async def test_completed_task_shows_output(self):
        from slack_bot.app import _handle_ai_status

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": "test-task-id"}

        mock_task = MagicMock()
        mock_task.id = "test-task-id"
        mock_task.agent_type = "coding"
        mock_task.status = "completed"
        mock_task.title = "Test task"
        mock_task.created_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
        mock_task.output_data = {"output": "PR created successfully"}

        mock_session = AsyncMock()
        mock_task_repo = MagicMock()
        mock_task_repo.get_by_id = AsyncMock(return_value=mock_task)

        with (
            patch("slack_bot.app.async_session_factory") as mock_factory,
            patch("slack_bot.app.TaskRepository", return_value=mock_task_repo),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(
                return_value=mock_session
            )
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_ai_status(ack, body, say)

        output = say.call_args[0][0]
        assert "completed" in output.lower() or "white_check_mark" in output
        assert "PR created successfully" in output

    @pytest.mark.asyncio
    async def test_failed_task_shows_error(self):
        from slack_bot.app import _handle_ai_status

        ack = AsyncMock()
        say = AsyncMock()
        body = {"text": "test-task-id"}

        mock_task = MagicMock()
        mock_task.id = "test-task-id"
        mock_task.agent_type = "coding"
        mock_task.status = "failed"
        mock_task.title = "Test task"
        mock_task.created_at = datetime(2026, 1, 1, tzinfo=timezone.utc)
        mock_task.output_data = {"error": "Claude API timeout"}

        mock_session = AsyncMock()
        mock_task_repo = MagicMock()
        mock_task_repo.get_by_id = AsyncMock(return_value=mock_task)

        with (
            patch("slack_bot.app.async_session_factory") as mock_factory,
            patch("slack_bot.app.TaskRepository", return_value=mock_task_repo),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(
                return_value=mock_session
            )
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_ai_status(ack, body, say)

        output = say.call_args[0][0]
        assert "Claude API timeout" in output


class TestSlackNotifier:
    @pytest.mark.asyncio
    async def test_send_skips_when_no_token(self):
        """Notifier should silently skip when no token is configured."""
        mock_settings = MagicMock()
        mock_settings.slack_bot_token = ""
        mock_settings.slack_default_channel = "#general"

        with patch(
            "slack_bot.notifications.get_settings", return_value=mock_settings
        ):
            from slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier()
            # Should not raise
            await notifier.notify_task_started("task-1", "coding", "Test")

    @pytest.mark.asyncio
    async def test_send_skips_when_no_channel(self):
        """Notifier should silently skip when no channel is configured."""
        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = ""

        with patch(
            "slack_bot.notifications.get_settings", return_value=mock_settings
        ):
            from slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier()
            await notifier.notify_task_completed("task-1")

    @pytest.mark.asyncio
    async def test_send_posts_to_slack_api(self):
        """Notifier should POST to Slack API when configured."""
        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test-token"
        mock_settings.slack_default_channel = "#dev"

        mock_response = MagicMock()
        mock_response.json.return_value = {"ok": True}

        mock_client = AsyncMock()
        mock_client.post.return_value = mock_response
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with (
            patch(
                "slack_bot.notifications.get_settings",
                return_value=mock_settings,
            ),
            patch("slack_bot.notifications.httpx.AsyncClient", return_value=mock_client),
        ):
            from slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier()
            await notifier.notify_task_failed("task-1", "Something broke")

            mock_client.post.assert_called_once()
            call_args = mock_client.post.call_args
            assert call_args[0][0] == "https://slack.com/api/chat.postMessage"
            assert call_args[1]["json"]["channel"] == "#dev"
            assert "Something broke" in call_args[1]["json"]["text"]

    @pytest.mark.asyncio
    async def test_send_handles_api_error(self):
        """Notifier should log warning on Slack API errors, not raise."""
        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        mock_response = MagicMock()
        mock_response.json.return_value = {
            "ok": False,
            "error": "channel_not_found",
        }

        mock_client = AsyncMock()
        mock_client.post.return_value = mock_response
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with (
            patch(
                "slack_bot.notifications.get_settings",
                return_value=mock_settings,
            ),
            patch("slack_bot.notifications.httpx.AsyncClient", return_value=mock_client),
        ):
            from slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier()
            # Should not raise
            await notifier.notify_task_started("task-1", "coding", "Test")

    @pytest.mark.asyncio
    async def test_send_handles_http_exception(self):
        """Notifier should handle network errors gracefully."""
        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        mock_client = AsyncMock()
        mock_client.post.side_effect = Exception("Connection refused")
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with (
            patch(
                "slack_bot.notifications.get_settings",
                return_value=mock_settings,
            ),
            patch("slack_bot.notifications.httpx.AsyncClient", return_value=mock_client),
        ):
            from slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier()
            # Should not raise
            await notifier.notify_phase_change("task-1", "IMPLEMENT", "Working on it")


class TestRepoRepositoryGetFirstActive:
    """Test the get_first_active method added to RepoRepository."""

    @pytest.mark.asyncio
    async def test_get_first_active_returns_repo(self):
        from ai_driven.db.postgres.repository import RepoRepository

        mock_session = AsyncMock()
        mock_result = MagicMock()
        mock_repo = MagicMock()
        mock_result.scalar_one_or_none.return_value = mock_repo
        mock_session.execute.return_value = mock_result

        repo_repo = RepoRepository(mock_session)
        result = await repo_repo.get_first_active()
        assert result == mock_repo
        mock_session.execute.assert_called_once()

    @pytest.mark.asyncio
    async def test_get_first_active_returns_none(self):
        from ai_driven.db.postgres.repository import RepoRepository

        mock_session = AsyncMock()
        mock_result = MagicMock()
        mock_result.scalar_one_or_none.return_value = None
        mock_session.execute.return_value = mock_result

        repo_repo = RepoRepository(mock_session)
        result = await repo_repo.get_first_active()
        assert result is None
