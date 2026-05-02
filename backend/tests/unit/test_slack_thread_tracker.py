"""Tests for SlackThreadTracker, PR cards, and @mention handling."""

from __future__ import annotations

import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


# ======================================================================
# SlackThreadTracker tests
# ======================================================================


class TestSlackThreadTracker:
    """Tests for the SlackThreadTracker class."""

    def _make_tracker(self, token: str = "xoxb-test", channel: str = "#dev"):
        mock_settings = MagicMock()
        mock_settings.slack_bot_token = token
        mock_settings.slack_default_channel = channel

        with patch("ai_driven.slack_bot.thread_tracker.get_settings", return_value=mock_settings):
            from ai_driven.slack_bot.thread_tracker import SlackThreadTracker

            return SlackThreadTracker()

    def _mock_httpx_success(self, ts: str = "1234567890.123456"):
        """Return mock httpx client that returns a successful Slack response."""
        mock_response = MagicMock()
        mock_response.json.return_value = {"ok": True, "ts": ts}

        mock_client = AsyncMock()
        mock_client.post.return_value = mock_response
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        return mock_client

    @pytest.mark.asyncio
    async def test_start_task_posts_initial_message(self):
        tracker = self._make_tracker()
        mock_client = self._mock_httpx_success(ts="111.222")

        with patch("ai_driven.slack_bot.thread_tracker.httpx.AsyncClient", return_value=mock_client):
            ts = await tracker.start_task("task-1", "Starting work on GTX-4821...")

        assert ts == "111.222"
        assert tracker.active_threads == {"task-1": "111.222"}

        call_args = mock_client.post.call_args
        payload = call_args[1]["json"]
        assert payload["channel"] == "#dev"
        assert "GTX-4821" in payload["text"]
        assert "thread_ts" not in payload

    @pytest.mark.asyncio
    async def test_post_thread_update_replies_in_thread(self):
        tracker = self._make_tracker()
        mock_client = self._mock_httpx_success()

        with patch("ai_driven.slack_bot.thread_tracker.httpx.AsyncClient", return_value=mock_client):
            # Start task first
            await tracker.start_task("task-1", "Starting...")

            # Reset mock to check next call
            mock_client.post.reset_mock()
            mock_client.post.return_value = MagicMock(
                json=MagicMock(return_value={"ok": True, "ts": "333.444"})
            )

            ts = await tracker.post_thread_update("task-1", "Planning complete")

        assert ts == "333.444"
        call_args = mock_client.post.call_args
        payload = call_args[1]["json"]
        assert payload["thread_ts"] == tracker.active_threads["task-1"]
        assert "Planning complete" in payload["text"]

    @pytest.mark.asyncio
    async def test_post_thread_update_falls_back_when_no_thread(self):
        tracker = self._make_tracker()
        mock_client = self._mock_httpx_success(ts="555.666")

        with patch("ai_driven.slack_bot.thread_tracker.httpx.AsyncClient", return_value=mock_client):
            ts = await tracker.post_thread_update("nonexistent", "Orphan update")

        assert ts == "555.666"
        call_args = mock_client.post.call_args
        payload = call_args[1]["json"]
        # Should not have thread_ts since no thread exists
        assert "thread_ts" not in payload

    @pytest.mark.asyncio
    async def test_skips_when_no_token(self):
        tracker = self._make_tracker(token="")
        ts = await tracker.start_task("task-1", "Hello")
        assert ts is None
        assert tracker.active_threads == {}

    @pytest.mark.asyncio
    async def test_skips_when_no_channel(self):
        tracker = self._make_tracker(channel="")
        ts = await tracker.start_task("task-1", "Hello")
        assert ts is None

    @pytest.mark.asyncio
    async def test_remove_task_cleans_up(self):
        tracker = self._make_tracker()
        mock_client = self._mock_httpx_success()

        with patch("ai_driven.slack_bot.thread_tracker.httpx.AsyncClient", return_value=mock_client):
            await tracker.start_task("task-1", "Starting...")

        assert "task-1" in tracker.active_threads
        tracker.remove_task("task-1")
        assert "task-1" not in tracker.active_threads

    @pytest.mark.asyncio
    async def test_remove_nonexistent_task_is_safe(self):
        tracker = self._make_tracker()
        tracker.remove_task("nonexistent")  # should not raise

    @pytest.mark.asyncio
    async def test_handles_api_error_gracefully(self):
        tracker = self._make_tracker()
        mock_response = MagicMock()
        mock_response.json.return_value = {"ok": False, "error": "channel_not_found"}

        mock_client = AsyncMock()
        mock_client.post.return_value = mock_response
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with patch("ai_driven.slack_bot.thread_tracker.httpx.AsyncClient", return_value=mock_client):
            ts = await tracker.start_task("task-1", "Hello")

        assert ts is None
        assert tracker.active_threads == {}

    @pytest.mark.asyncio
    async def test_handles_network_error_gracefully(self):
        tracker = self._make_tracker()
        mock_client = AsyncMock()
        mock_client.post.side_effect = Exception("Connection refused")
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with patch("ai_driven.slack_bot.thread_tracker.httpx.AsyncClient", return_value=mock_client):
            ts = await tracker.start_task("task-1", "Hello")

        assert ts is None

    @pytest.mark.asyncio
    async def test_start_task_with_custom_channel(self):
        tracker = self._make_tracker()
        mock_client = self._mock_httpx_success()

        with patch("ai_driven.slack_bot.thread_tracker.httpx.AsyncClient", return_value=mock_client):
            await tracker.start_task("task-1", "Hello", channel="#alerts")

        payload = mock_client.post.call_args[1]["json"]
        assert payload["channel"] == "#alerts"


# ======================================================================
# PR Card Block Kit tests
# ======================================================================


class TestPRCardBlocks:
    def test_build_pr_card_blocks_structure(self):
        from ai_driven.slack_bot.thread_tracker import build_pr_card_blocks

        blocks = build_pr_card_blocks(
            repo_name="org/repo",
            pr_number=2418,
            pr_title="fix(payments): add rate-limit headers",
            branch="feature/rate-limits",
            file_count=4,
            additions=120,
            deletions=30,
            pr_url="https://github.com/org/repo/pull/2418",
            status="Open",
            ai_reviewed=True,
            ci_status="passing",
        )

        assert len(blocks) == 4

        # Header
        header = blocks[0]
        assert header["type"] == "header"
        assert "org/repo #2418" in header["text"]["text"]

        # Section with fields
        section = blocks[1]
        assert section["type"] == "section"
        fields = section["fields"]
        assert len(fields) == 4
        # Check title field
        assert "fix(payments): add rate-limit headers" in fields[0]["text"]
        # Check branch field
        assert "`feature/rate-limits`" in fields[1]["text"]
        # Check file count
        assert "4" in fields[2]["text"]
        # Check additions/deletions
        assert "+120" in fields[3]["text"]
        assert "-30" in fields[3]["text"]

        # Context (badges)
        context = blocks[2]
        assert context["type"] == "context"
        badge_text = context["elements"][0]["text"]
        assert "Open" in badge_text
        assert "AI-reviewed" in badge_text
        assert "CI passing" in badge_text

        # Actions (View PR button)
        actions = blocks[3]
        assert actions["type"] == "actions"
        button = actions["elements"][0]
        assert button["type"] == "button"
        assert button["text"]["text"] == "View PR"
        assert button["url"] == "https://github.com/org/repo/pull/2418"

    def test_build_pr_card_blocks_without_ai_review(self):
        from ai_driven.slack_bot.thread_tracker import build_pr_card_blocks

        blocks = build_pr_card_blocks(
            repo_name="org/repo",
            pr_number=1,
            pr_title="test",
            branch="main",
            pr_url="https://example.com",
            ai_reviewed=False,
            ci_status="failing",
        )

        context = blocks[2]
        badge_text = context["elements"][0]["text"]
        assert "AI-reviewed" not in badge_text
        assert "CI failing" in badge_text

    @pytest.mark.asyncio
    async def test_post_pr_card_via_tracker(self):
        """Test that post_pr_card calls post_thread_update with blocks."""
        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        mock_response = MagicMock()
        mock_response.json.return_value = {"ok": True, "ts": "999.888"}

        mock_client = AsyncMock()
        mock_client.post.return_value = mock_response
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with (
            patch("ai_driven.slack_bot.thread_tracker.get_settings", return_value=mock_settings),
            patch("ai_driven.slack_bot.thread_tracker.httpx.AsyncClient", return_value=mock_client),
        ):
            from ai_driven.slack_bot.thread_tracker import SlackThreadTracker

            tracker = SlackThreadTracker()
            await tracker.start_task("task-1", "Starting...")
            mock_client.post.reset_mock()
            mock_client.post.return_value = MagicMock(
                json=MagicMock(return_value={"ok": True, "ts": "1000.111"})
            )

            ts = await tracker.post_pr_card(
                "task-1",
                repo_name="org/repo",
                pr_number=42,
                pr_title="My PR",
                branch="feature/x",
                file_count=3,
                additions=50,
                deletions=10,
                pr_url="https://github.com/org/repo/pull/42",
            )

        assert ts == "1000.111"
        call_args = mock_client.post.call_args
        payload = call_args[1]["json"]
        assert "blocks" in payload
        assert payload["thread_ts"] is not None


# ======================================================================
# SlackNotifier with thread tracker integration tests
# ======================================================================


class TestSlackNotifierWithTracker:
    @pytest.mark.asyncio
    async def test_notify_task_started_uses_tracker(self):
        mock_tracker = AsyncMock()
        mock_tracker.start_task = AsyncMock()

        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        with patch("ai_driven.slack_bot.notifications.get_settings", return_value=mock_settings):
            from ai_driven.slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier(thread_tracker=mock_tracker)
            await notifier.notify_task_started("task-1", "coding", "Test Task")

        mock_tracker.start_task.assert_called_once()
        call_args = mock_tracker.start_task.call_args
        assert call_args[0][0] == "task-1"
        assert "Coding Agent Started" in call_args[0][1]

    @pytest.mark.asyncio
    async def test_notify_phase_change_uses_tracker(self):
        mock_tracker = AsyncMock()
        mock_tracker.post_thread_update = AsyncMock()

        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        with patch("ai_driven.slack_bot.notifications.get_settings", return_value=mock_settings):
            from ai_driven.slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier(thread_tracker=mock_tracker)
            await notifier.notify_phase_change("task-1", "Planning", "4 files")

        mock_tracker.post_thread_update.assert_called_once()
        assert "task-1" in mock_tracker.post_thread_update.call_args[0]

    @pytest.mark.asyncio
    async def test_notify_task_completed_uses_tracker_and_cleans_up(self):
        mock_tracker = AsyncMock()
        mock_tracker.post_thread_update = AsyncMock()
        mock_tracker.remove_task = MagicMock()

        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        with patch("ai_driven.slack_bot.notifications.get_settings", return_value=mock_settings):
            from ai_driven.slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier(thread_tracker=mock_tracker)
            await notifier.notify_task_completed("task-1", pr_url="https://example.com")

        mock_tracker.post_thread_update.assert_called_once()
        mock_tracker.remove_task.assert_called_once_with("task-1")

    @pytest.mark.asyncio
    async def test_notify_task_failed_uses_tracker_and_cleans_up(self):
        mock_tracker = AsyncMock()
        mock_tracker.post_thread_update = AsyncMock()
        mock_tracker.remove_task = MagicMock()

        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        with patch("ai_driven.slack_bot.notifications.get_settings", return_value=mock_settings):
            from ai_driven.slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier(thread_tracker=mock_tracker)
            await notifier.notify_task_failed("task-1", "Something broke")

        mock_tracker.post_thread_update.assert_called_once()
        mock_tracker.remove_task.assert_called_once_with("task-1")

    @pytest.mark.asyncio
    async def test_notify_pr_created_uses_tracker(self):
        mock_tracker = AsyncMock()
        mock_tracker.post_pr_card = AsyncMock()

        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        with patch("ai_driven.slack_bot.notifications.get_settings", return_value=mock_settings):
            from ai_driven.slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier(thread_tracker=mock_tracker)
            await notifier.notify_pr_created(
                "task-1",
                repo_name="org/repo",
                pr_number=42,
                pr_title="My PR",
                branch="feature/x",
                pr_url="https://github.com/org/repo/pull/42",
            )

        mock_tracker.post_pr_card.assert_called_once()

    @pytest.mark.asyncio
    async def test_notify_pr_created_fallback_without_tracker(self):
        mock_settings = MagicMock()
        mock_settings.slack_bot_token = "xoxb-test"
        mock_settings.slack_default_channel = "#dev"

        mock_response = MagicMock()
        mock_response.json.return_value = {"ok": True}
        mock_client = AsyncMock()
        mock_client.post.return_value = mock_response
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)

        with (
            patch("ai_driven.slack_bot.notifications.get_settings", return_value=mock_settings),
            patch("ai_driven.slack_bot.notifications.httpx.AsyncClient", return_value=mock_client),
        ):
            from ai_driven.slack_bot.notifications import SlackNotifier

            notifier = SlackNotifier()
            await notifier.notify_pr_created(
                "task-1",
                repo_name="org/repo",
                pr_number=42,
                pr_title="My PR",
                branch="feature/x",
                pr_url="https://github.com/org/repo/pull/42",
            )

        mock_client.post.assert_called_once()
        payload = mock_client.post.call_args[1]["json"]
        assert "PR #42" in payload["text"]
        assert "View PR" in payload["text"]


# ======================================================================
# @mention handler tests
# ======================================================================


class TestHandleMention:
    @pytest.mark.asyncio
    async def test_empty_message_shows_help(self):
        from ai_driven.slack_bot.app import _handle_mention

        say = AsyncMock()
        event = {"text": "<@U12345>", "ts": "111.222", "channel": "C123"}

        await _handle_mention(event, say)

        say.assert_called_once()
        assert "Jira key" in say.call_args[1]["text"]
        assert say.call_args[1]["thread_ts"] == "111.222"

    @pytest.mark.asyncio
    async def test_no_jira_key_shows_error(self):
        from ai_driven.slack_bot.app import _handle_mention

        say = AsyncMock()
        event = {
            "text": "<@U12345> please do something",
            "ts": "111.222",
            "channel": "C123",
        }

        await _handle_mention(event, say)

        say.assert_called_once()
        assert "could not find a Jira key" in say.call_args[1]["text"]

    @pytest.mark.asyncio
    async def test_jira_key_dispatches_task(self):
        from ai_driven.slack_bot.app import _handle_mention

        say = AsyncMock()
        event = {
            "text": "<@U12345> please fix GTX-4821",
            "ts": "111.222",
            "channel": "C123",
        }

        task_id = uuid.uuid4()
        mock_repo = MagicMock()
        mock_repo.team_id = uuid.uuid4()
        mock_repo.id = uuid.uuid4()
        mock_repo.full_name = "org/repo"

        mock_task = MagicMock()
        mock_task.id = task_id
        mock_task.input_data = {"jira_key": "GTX-4821"}

        mock_session = AsyncMock()
        mock_repo_repo = MagicMock()
        mock_repo_repo.get_first_active = AsyncMock(return_value=mock_repo)
        mock_task_repo = MagicMock()
        mock_task_repo.create = AsyncMock(return_value=mock_task)
        mock_producer = MagicMock()
        mock_producer.enqueue_task = AsyncMock()

        with (
            patch("ai_driven.slack_bot.app.async_session_factory") as mock_factory,
            patch("ai_driven.slack_bot.app.RepoRepository", return_value=mock_repo_repo),
            patch("ai_driven.slack_bot.app.TaskRepository", return_value=mock_task_repo),
            patch("ai_driven.slack_bot.app.TaskProducer", return_value=mock_producer),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(return_value=mock_session)
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_mention(event, say)

        # Should create a coding task with the jira key
        mock_task_repo.create.assert_called_once()
        create_kwargs = mock_task_repo.create.call_args.kwargs
        assert create_kwargs["jira_key"] == "GTX-4821"
        assert create_kwargs["agent_type"] == "coding"
        assert create_kwargs["input_data"]["triggered_by"] == "slack_mention"

        # Should enqueue the task
        mock_producer.enqueue_task.assert_called_once()

        # Should reply confirming the task
        say.assert_called_once()
        assert "GTX-4821" in say.call_args[1]["text"]
        assert say.call_args[1]["thread_ts"] == "111.222"

    @pytest.mark.asyncio
    async def test_no_active_repo_reports_error(self):
        from ai_driven.slack_bot.app import _handle_mention

        say = AsyncMock()
        event = {
            "text": "<@U12345> fix GTX-4821",
            "ts": "111.222",
            "channel": "C123",
        }

        mock_session = AsyncMock()
        mock_repo_repo = MagicMock()
        mock_repo_repo.get_first_active = AsyncMock(return_value=None)

        with (
            patch("ai_driven.slack_bot.app.async_session_factory") as mock_factory,
            patch("ai_driven.slack_bot.app.RepoRepository", return_value=mock_repo_repo),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(return_value=mock_session)
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_mention(event, say)

        assert "No active repository" in say.call_args[1]["text"]

    @pytest.mark.asyncio
    async def test_uses_thread_ts_from_event(self):
        """When replying in an existing thread, use the thread_ts."""
        from ai_driven.slack_bot.app import _handle_mention

        say = AsyncMock()
        event = {
            "text": "<@U12345> just checking",
            "ts": "222.333",
            "thread_ts": "111.222",
            "channel": "C123",
        }

        await _handle_mention(event, say)

        # Should reply in the existing thread
        assert say.call_args[1]["thread_ts"] == "111.222"

    @pytest.mark.asyncio
    async def test_extracts_jira_key_from_complex_message(self):
        from ai_driven.slack_bot.app import _handle_mention

        say = AsyncMock()
        event = {
            "text": "<@U12345> hey can you please work on PROJ-999 and deploy it?",
            "ts": "111.222",
            "channel": "C123",
        }

        mock_session = AsyncMock()
        mock_repo = MagicMock()
        mock_repo.team_id = uuid.uuid4()
        mock_repo.id = uuid.uuid4()
        mock_repo.full_name = "org/repo"

        mock_task = MagicMock()
        mock_task.id = uuid.uuid4()
        mock_task.input_data = {}

        mock_repo_repo = MagicMock()
        mock_repo_repo.get_first_active = AsyncMock(return_value=mock_repo)
        mock_task_repo = MagicMock()
        mock_task_repo.create = AsyncMock(return_value=mock_task)
        mock_producer = MagicMock()
        mock_producer.enqueue_task = AsyncMock()

        with (
            patch("ai_driven.slack_bot.app.async_session_factory") as mock_factory,
            patch("ai_driven.slack_bot.app.RepoRepository", return_value=mock_repo_repo),
            patch("ai_driven.slack_bot.app.TaskRepository", return_value=mock_task_repo),
            patch("ai_driven.slack_bot.app.TaskProducer", return_value=mock_producer),
        ):
            mock_factory.return_value.__aenter__ = AsyncMock(return_value=mock_session)
            mock_factory.return_value.__aexit__ = AsyncMock(return_value=False)

            await _handle_mention(event, say)

        create_kwargs = mock_task_repo.create.call_args.kwargs
        assert create_kwargs["jira_key"] == "PROJ-999"


# ======================================================================
# Slack webhook intent parsing tests
# ======================================================================


class TestParseMentionIntent:
    def test_extracts_jira_key(self):
        from ai_driven.api.webhooks.slack import parse_mention_intent

        result = parse_mention_intent("<@U12345> please fix GTX-4821")
        assert result["jira_key"] == "GTX-4821"
        assert "please fix GTX-4821" in result["raw_text"]

    def test_no_jira_key(self):
        from ai_driven.api.webhooks.slack import parse_mention_intent

        result = parse_mention_intent("<@U12345> hello there")
        assert result["jira_key"] is None
        assert result["raw_text"] == "hello there"

    def test_empty_after_mention(self):
        from ai_driven.api.webhooks.slack import parse_mention_intent

        result = parse_mention_intent("<@U12345>")
        assert result["jira_key"] is None
        assert result["raw_text"] == ""

    def test_multiple_jira_keys_takes_first(self):
        from ai_driven.api.webhooks.slack import parse_mention_intent

        result = parse_mention_intent("<@U12345> fix PROJ-1 and PROJ-2")
        assert result["jira_key"] == "PROJ-1"


# ======================================================================
# Slack webhook signature verification tests
# ======================================================================


class TestSlackSignatureVerification:
    def test_valid_signature(self):
        import hashlib
        import hmac
        import time

        from ai_driven.api.webhooks.slack import _verify_slack_signature

        secret = "test-signing-secret"
        body = b'{"type":"url_verification","challenge":"abc123"}'
        timestamp = str(int(time.time()))

        sig_basestring = f"v0:{timestamp}:{body.decode('utf-8')}"
        signature = "v0=" + hmac.new(
            secret.encode(), sig_basestring.encode(), hashlib.sha256
        ).hexdigest()

        assert _verify_slack_signature(body, timestamp, signature, secret) is True

    def test_invalid_signature(self):
        import time

        from ai_driven.api.webhooks.slack import _verify_slack_signature

        assert (
            _verify_slack_signature(
                b"body", str(int(time.time())), "v0=invalid", "secret"
            )
            is False
        )

    def test_expired_timestamp(self):
        from ai_driven.api.webhooks.slack import _verify_slack_signature

        # Timestamp from 10 minutes ago
        old_ts = str(int(1000))
        assert (
            _verify_slack_signature(b"body", old_ts, "v0=whatever", "secret")
            is False
        )

    def test_empty_secret_fails(self):
        from ai_driven.api.webhooks.slack import _verify_slack_signature

        assert _verify_slack_signature(b"body", "123", "v0=sig", "") is False

    def test_invalid_timestamp_format(self):
        from ai_driven.api.webhooks.slack import _verify_slack_signature

        assert (
            _verify_slack_signature(b"body", "not-a-number", "v0=sig", "secret")
            is False
        )


# ======================================================================
# Jira key search pattern tests
# ======================================================================


class TestJiraKeySearchPattern:
    def test_finds_key_in_sentence(self):
        from ai_driven.slack_bot.app import JIRA_KEY_SEARCH_PATTERN

        match = JIRA_KEY_SEARCH_PATTERN.search("please fix GTX-4821 asap")
        assert match is not None
        assert match.group(1) == "GTX-4821"

    def test_finds_key_at_start(self):
        from ai_driven.slack_bot.app import JIRA_KEY_SEARCH_PATTERN

        match = JIRA_KEY_SEARCH_PATTERN.search("PROJ-1 is broken")
        assert match is not None
        assert match.group(1) == "PROJ-1"

    def test_no_match_for_lowercase(self):
        from ai_driven.slack_bot.app import JIRA_KEY_SEARCH_PATTERN

        match = JIRA_KEY_SEARCH_PATTERN.search("please fix proj-123")
        assert match is None

    def test_no_match_for_no_key(self):
        from ai_driven.slack_bot.app import JIRA_KEY_SEARCH_PATTERN

        match = JIRA_KEY_SEARCH_PATTERN.search("just a regular message")
        assert match is None
