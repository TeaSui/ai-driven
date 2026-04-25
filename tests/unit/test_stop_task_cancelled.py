"""Stopping a task sets status='cancelled' and publishes a complete event."""

from __future__ import annotations

import asyncio
import json
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.api.chat.router import stop_task


class TestStopTaskYieldsCancelled:
    """stop_task() must set cancelled (not failed) and publish stopped=true."""

    def test_stop_sets_cancelled_and_publishes_stopped_event(self) -> None:
        task_id = str(uuid.uuid4())

        fake_redis = AsyncMock()
        fake_redis.set = AsyncMock()
        fake_redis.publish = AsyncMock()

        fake_session = AsyncMock()
        fake_session.commit = AsyncMock()
        async_session_cm = MagicMock()
        async_session_cm.__aenter__ = AsyncMock(return_value=fake_session)
        async_session_cm.__aexit__ = AsyncMock(return_value=None)

        fake_task_repo = MagicMock()
        fake_task_repo.update_status = AsyncMock()

        sentinel_task = MagicMock()

        with patch(
            "ai_driven.api.chat.router.aioredis.from_url",
            return_value=fake_redis,
        ), patch(
            "ai_driven.db.postgres.session.async_session_factory",
            return_value=async_session_cm,
        ), patch(
            "ai_driven.db.postgres.repository.TaskRepository",
            return_value=fake_task_repo,
        ):
            # Call the route handler directly, bypassing the auth dependency.
            result = asyncio.run(stop_task(task_id=task_id, _task=sentinel_task))

        assert result == {"status": "cancelled", "taskId": task_id}, (
            f"expected cancelled response; got {result}"
        )

        # Status update went through with 'cancelled', not 'failed'.
        fake_task_repo.update_status.assert_awaited_once()
        args, kwargs = fake_task_repo.update_status.call_args
        all_values = list(args) + list(kwargs.values())
        assert "cancelled" in all_values, (
            f"update_status must receive 'cancelled'; got args={args} kwargs={kwargs}"
        )
        assert "failed" not in all_values, "stop must not set status='failed'"

        # Complete event published with stopped=True.
        fake_redis.publish.assert_awaited_once()
        channel, payload = fake_redis.publish.call_args.args
        assert channel == f"task:{task_id}:events"
        event = json.loads(payload)
        assert event["type"] == "complete"
        assert event["data"]["stopped"] is True
