"""Tests for WorkflowStateMachine (Task 5.9)."""

from __future__ import annotations

from unittest.mock import MagicMock

import pytest

from ai_driven.agents.workflow.state_machine import (
    ALL_STATES,
    WorkflowStateMachine,
)


class TestWorkflowStateMachine:
    """Test state machine transitions and guards."""

    def test_initial_state_is_ticket_created(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        assert sm.current_state == "TICKET_CREATED"

    @pytest.mark.asyncio
    async def test_valid_transition(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        result = await sm.transition("READY_FOR_DEV")
        assert result is True
        assert sm.current_state == "READY_FOR_DEV"

    @pytest.mark.asyncio
    async def test_invalid_transition_rejected(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        # Cannot go directly from TICKET_CREATED to CODING
        result = await sm.transition("CODING")
        assert result is False
        assert sm.current_state == "TICKET_CREATED"

    def test_can_transition_check(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        assert sm.can_transition("READY_FOR_DEV") is True
        assert sm.can_transition("DEPLOYED") is False

    @pytest.mark.asyncio
    async def test_transition_to_failed_from_any_state(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        await sm.transition("READY_FOR_DEV")
        await sm.transition("PLANNING")
        result = await sm.transition("FAILED")
        assert result is True
        assert sm.current_state == "FAILED"

    @pytest.mark.asyncio
    async def test_full_happy_path(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        states = [
            "READY_FOR_DEV",
            "PLANNING",
            "CODING",
            "TESTING",
            "PR_CREATED",
            "IN_REVIEW",
            "REVIEW_PASSED",
            "MERGING",
            "DEPLOYED",
            "MONITORING",
            "COMPLETED",
        ]
        for state in states:
            assert await sm.transition(state) is True, f"Failed to transition to {state}"
        assert sm.current_state == "COMPLETED"

    @pytest.mark.asyncio
    async def test_transition_records_to_store(self) -> None:
        mock_store = MagicMock()
        sm = WorkflowStateMachine(task_id="task-1", _store=mock_store)
        await sm.transition("READY_FOR_DEV")
        mock_store.record_transition.assert_called_once_with(
            task_id="task-1",
            from_state="TICKET_CREATED",
            to_state="READY_FOR_DEV",
            metadata=None,
        )

    @pytest.mark.asyncio
    async def test_transition_with_metadata(self) -> None:
        mock_store = MagicMock()
        sm = WorkflowStateMachine(task_id="task-1", _store=mock_store)
        await sm.transition("READY_FOR_DEV", metadata={"reason": "ticket assigned"})
        mock_store.record_transition.assert_called_once_with(
            task_id="task-1",
            from_state="TICKET_CREATED",
            to_state="READY_FOR_DEV",
            metadata={"reason": "ticket assigned"},
        )

    @pytest.mark.asyncio
    async def test_cannot_transition_from_completed(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        for s in [
            "READY_FOR_DEV", "PLANNING", "CODING", "TESTING", "PR_CREATED",
            "IN_REVIEW", "REVIEW_PASSED", "MERGING", "DEPLOYED", "MONITORING",
            "COMPLETED",
        ]:
            await sm.transition(s)
        result = await sm.transition("MONITORING")
        assert result is False

    def test_all_states_contains_expected(self) -> None:
        assert isinstance(ALL_STATES, frozenset)
        assert "TICKET_CREATED" in ALL_STATES
        assert "COMPLETED" in ALL_STATES
        assert "FAILED" in ALL_STATES

    def test_set_state_restores(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        sm.set_state("CODING")
        assert sm.current_state == "CODING"

    def test_set_state_invalid_raises(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        with pytest.raises(ValueError, match="Unknown state"):
            sm.set_state("INVALID_STATE")

    @pytest.mark.asyncio
    async def test_listener_called_on_transition(self) -> None:
        sm = WorkflowStateMachine(task_id="task-1")
        calls = []
        sm.add_listener(lambda tid, from_s, to_s, meta: calls.append((from_s, to_s)))
        await sm.transition("READY_FOR_DEV")
        assert len(calls) == 1
        assert calls[0] == ("TICKET_CREATED", "READY_FOR_DEV")
