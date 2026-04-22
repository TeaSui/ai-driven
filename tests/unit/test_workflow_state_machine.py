"""Tests for WorkflowStateMachine."""

from __future__ import annotations

import pytest

from ai_driven.agents.workflow.state_machine import (
    ALL_STATES,
    CODING,
    COMPLETED,
    DEPLOYED,
    FAILED,
    IN_REVIEW,
    MERGING,
    MONITORING,
    PLANNING,
    PR_CREATED,
    READY_FOR_DEV,
    REVIEW_PASSED,
    TESTING,
    TICKET_CREATED,
    StateTransition,
    WorkflowStateMachine,
)


# ---------------------------------------------------------------------------
# Basic lifecycle
# ---------------------------------------------------------------------------


class TestHappyPath:
    """Walk the entire happy-path lifecycle."""

    @pytest.fixture()
    def sm(self) -> WorkflowStateMachine:
        return WorkflowStateMachine(task_id="task-1")

    @pytest.mark.asyncio
    async def test_full_happy_path(self, sm: WorkflowStateMachine) -> None:
        happy = [
            READY_FOR_DEV,
            PLANNING,
            CODING,
            TESTING,
            PR_CREATED,
            IN_REVIEW,
            REVIEW_PASSED,
            MERGING,
            DEPLOYED,
            MONITORING,
            COMPLETED,
        ]
        for state in happy:
            assert sm.can_transition(state), f"Expected transition to {state}"
            ok = await sm.transition(state)
            assert ok, f"Transition to {state} should succeed"
        assert sm.current_state == COMPLETED

    @pytest.mark.asyncio
    async def test_initial_state(self, sm: WorkflowStateMachine) -> None:
        assert sm.current_state == TICKET_CREATED


# ---------------------------------------------------------------------------
# Invalid transitions
# ---------------------------------------------------------------------------


class TestInvalidTransitions:
    @pytest.mark.asyncio
    async def test_skip_state_rejected(self) -> None:
        sm = WorkflowStateMachine(task_id="task-2")
        assert not sm.can_transition(CODING)
        ok = await sm.transition(CODING)
        assert not ok
        assert sm.current_state == TICKET_CREATED

    @pytest.mark.asyncio
    async def test_unknown_state_rejected(self) -> None:
        sm = WorkflowStateMachine(task_id="task-3")
        assert not sm.can_transition("NONEXISTENT")
        ok = await sm.transition("NONEXISTENT")
        assert not ok

    @pytest.mark.asyncio
    async def test_no_transition_from_completed(self) -> None:
        sm = WorkflowStateMachine(task_id="task-4")
        sm.set_state(COMPLETED)
        assert not sm.can_transition(TICKET_CREATED)
        assert not sm.can_transition(FAILED)

    @pytest.mark.asyncio
    async def test_no_transition_from_failed(self) -> None:
        sm = WorkflowStateMachine(task_id="task-5")
        sm.set_state(FAILED)
        assert not sm.can_transition(TICKET_CREATED)
        assert not sm.can_transition(CODING)


# ---------------------------------------------------------------------------
# FAILED transitions
# ---------------------------------------------------------------------------


class TestFailTransitions:
    @pytest.mark.asyncio
    async def test_fail_from_any_non_terminal(self) -> None:
        for state in ALL_STATES:
            if state in (COMPLETED, FAILED):
                continue
            sm = WorkflowStateMachine(task_id=f"fail-{state}")
            sm.set_state(state)
            assert sm.can_transition(FAILED), f"Should be able to fail from {state}"
            ok = await sm.transition(FAILED)
            assert ok


# ---------------------------------------------------------------------------
# Guards
# ---------------------------------------------------------------------------


class TestGuards:
    @pytest.mark.asyncio
    async def test_guard_blocks_transition(self) -> None:
        sm = WorkflowStateMachine(task_id="guarded-1")
        sm.add_transition(
            StateTransition(
                from_state=TICKET_CREATED,
                to_state=READY_FOR_DEV,
                guard=lambda f, t, m: False,
            )
        )
        assert not sm.can_transition(READY_FOR_DEV)
        ok = await sm.transition(READY_FOR_DEV)
        assert not ok
        assert sm.current_state == TICKET_CREATED

    @pytest.mark.asyncio
    async def test_guard_allows_transition(self) -> None:
        sm = WorkflowStateMachine(task_id="guarded-2")
        sm.add_transition(
            StateTransition(
                from_state=TICKET_CREATED,
                to_state=READY_FOR_DEV,
                guard=lambda f, t, m: True,
            )
        )
        ok = await sm.transition(READY_FOR_DEV)
        assert ok

    @pytest.mark.asyncio
    async def test_guard_exception_blocks(self) -> None:
        sm = WorkflowStateMachine(task_id="guarded-3")

        def bad_guard(f: str, t: str, m: object) -> bool:
            raise RuntimeError("boom")

        sm.add_transition(
            StateTransition(
                from_state=TICKET_CREATED,
                to_state=READY_FOR_DEV,
                guard=bad_guard,
            )
        )
        assert not sm.can_transition(READY_FOR_DEV)
        ok = await sm.transition(READY_FOR_DEV)
        assert not ok


# ---------------------------------------------------------------------------
# Persistence (store)
# ---------------------------------------------------------------------------


class TestPersistence:
    @pytest.mark.asyncio
    async def test_store_records_transition(self) -> None:
        recorded: list[dict] = []

        class FakeStore:
            def record_transition(
                self, task_id: str, from_state: str, to_state: str, metadata: dict | None = None
            ) -> None:
                recorded.append(
                    {"task_id": task_id, "from": from_state, "to": to_state, "meta": metadata}
                )

        sm = WorkflowStateMachine(task_id="persist-1", _store=FakeStore())  # type: ignore[arg-type]
        await sm.transition(READY_FOR_DEV, metadata={"reason": "ticket ready"})

        assert len(recorded) == 1
        assert recorded[0]["task_id"] == "persist-1"
        assert recorded[0]["from"] == TICKET_CREATED
        assert recorded[0]["to"] == READY_FOR_DEV
        assert recorded[0]["meta"] == {"reason": "ticket ready"}

    @pytest.mark.asyncio
    async def test_store_error_does_not_prevent_transition(self) -> None:
        class BrokenStore:
            def record_transition(self, **kwargs: object) -> None:
                raise RuntimeError("DynamoDB down")

        sm = WorkflowStateMachine(task_id="persist-2", _store=BrokenStore())  # type: ignore[arg-type]
        ok = await sm.transition(READY_FOR_DEV)
        assert ok
        assert sm.current_state == READY_FOR_DEV


# ---------------------------------------------------------------------------
# Listeners
# ---------------------------------------------------------------------------


class TestListeners:
    @pytest.mark.asyncio
    async def test_listener_called_on_transition(self) -> None:
        events: list[tuple] = []

        def listener(
            task_id: str, from_state: str, to_state: str, metadata: dict | None
        ) -> None:
            events.append((task_id, from_state, to_state, metadata))

        sm = WorkflowStateMachine(task_id="listen-1")
        sm.add_listener(listener)
        await sm.transition(READY_FOR_DEV, metadata={"x": 1})

        assert len(events) == 1
        assert events[0] == ("listen-1", TICKET_CREATED, READY_FOR_DEV, {"x": 1})

    @pytest.mark.asyncio
    async def test_listener_error_does_not_prevent_transition(self) -> None:
        def bad_listener(
            task_id: str, from_state: str, to_state: str, metadata: dict | None
        ) -> None:
            raise RuntimeError("listener crash")

        sm = WorkflowStateMachine(task_id="listen-2")
        sm.add_listener(bad_listener)
        ok = await sm.transition(READY_FOR_DEV)
        assert ok
        assert sm.current_state == READY_FOR_DEV


# ---------------------------------------------------------------------------
# set_state
# ---------------------------------------------------------------------------


class TestSetState:
    def test_set_valid_state(self) -> None:
        sm = WorkflowStateMachine(task_id="set-1")
        sm.set_state(CODING)
        assert sm.current_state == CODING

    def test_set_invalid_state_raises(self) -> None:
        sm = WorkflowStateMachine(task_id="set-2")
        with pytest.raises(ValueError, match="Unknown state"):
            sm.set_state("BOGUS")
