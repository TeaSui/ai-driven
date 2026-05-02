"""Integration tests for workflow pipeline (Task 5.12)."""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.agents.workflow.state_machine import WorkflowStateMachine
from ai_driven.agents.workflow.triggers import WorkflowTriggers


class TestWorkflowTriggers:
    """Test event-driven workflow triggers."""

    @pytest.fixture
    def mock_producer(self) -> MagicMock:
        producer = MagicMock()
        producer.enqueue_task = AsyncMock()
        return producer

    @pytest.fixture
    def mock_notifier(self) -> MagicMock:
        notifier = MagicMock()
        notifier.notify_phase_change = AsyncMock()
        notifier.notify_task_completed = AsyncMock()
        notifier.notify_task_failed = AsyncMock()
        return notifier

    @pytest.fixture
    def triggers(self, mock_producer, mock_notifier) -> WorkflowTriggers:
        return WorkflowTriggers(
            task_producer=mock_producer,
            slack_notifier=mock_notifier,
        )

    @pytest.mark.asyncio
    async def test_coding_complete_triggers_review(
        self, triggers: WorkflowTriggers, mock_producer: MagicMock,
    ) -> None:
        """When coding finishes with a PR, should trigger a review task."""
        result = {
            "success": True,
            "pr_url": "https://github.com/org/repo/pull/42",
            "pr_number": 42,
            "repo_full_name": "org/repo",
        }

        await triggers.on_coding_task_completed("task-1", result)
        mock_producer.enqueue_task.assert_called_once()
        call_kwargs = mock_producer.enqueue_task.call_args[1]
        assert call_kwargs["agent_type"] == "review"

    @pytest.mark.asyncio
    async def test_coding_complete_without_pr_skips(
        self, triggers: WorkflowTriggers, mock_producer: MagicMock,
    ) -> None:
        """When coding finishes without a PR, should not trigger review."""
        result = {"success": True}

        await triggers.on_coding_task_completed("task-1", result)
        mock_producer.enqueue_task.assert_not_called()

    @pytest.mark.asyncio
    async def test_review_approved_triggers_notification(
        self, triggers: WorkflowTriggers, mock_notifier: MagicMock,
    ) -> None:
        """When review passes, should notify team."""
        result = {
            "success": True,
            "verdict": "approved",
            "repo_full_name": "org/repo",
            "pr_number": 42,
        }

        await triggers.on_review_completed("task-2", result)
        mock_notifier.notify_phase_change.assert_called()

    @pytest.mark.asyncio
    async def test_review_changes_requested_notifies(
        self, triggers: WorkflowTriggers, mock_notifier: MagicMock,
    ) -> None:
        """When review requests changes, should notify developer."""
        result = {
            "success": True,
            "verdict": "changes_requested",
            "repo_full_name": "org/repo",
            "pr_number": 42,
            "summary": "Please fix the SQL injection.",
        }

        await triggers.on_review_completed("task-2", result)
        mock_notifier.notify_phase_change.assert_called()
        call_kwargs = mock_notifier.notify_phase_change.call_args[1]
        assert "Changes Requested" in call_kwargs.get("phase", "")

    @pytest.mark.asyncio
    async def test_merge_completed_notifies(
        self, triggers: WorkflowTriggers, mock_notifier: MagicMock,
    ) -> None:
        result = {
            "repo_full_name": "org/repo",
            "pr_number": 42,
            "pr_url": "https://github.com/org/repo/pull/42",
        }
        await triggers.on_merge_completed("task-3", result)
        mock_notifier.notify_task_completed.assert_called_once()


class TestStateMachineIntegration:
    """Test state machine with full workflow transitions."""

    @pytest.mark.asyncio
    async def test_coding_to_review_pipeline(self) -> None:
        sm = WorkflowStateMachine(task_id="pipeline-1")
        transitions = [
            "READY_FOR_DEV", "PLANNING", "CODING", "TESTING",
            "PR_CREATED", "IN_REVIEW", "REVIEW_PASSED",
        ]
        for state in transitions:
            assert await sm.transition(state) is True

    @pytest.mark.asyncio
    async def test_failure_during_coding(self) -> None:
        sm = WorkflowStateMachine(task_id="pipeline-2")
        await sm.transition("READY_FOR_DEV")
        await sm.transition("PLANNING")
        await sm.transition("CODING")
        await sm.transition("FAILED")
        assert sm.current_state == "FAILED"
