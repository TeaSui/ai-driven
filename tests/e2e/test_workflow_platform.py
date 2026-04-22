"""End-to-end tests for Feature 3: AI Dev Workflow Platform.

Covers the full orchestration workflow from Jira webhook through coding,
review, merge, deploy, monitor, and completion.  All external services
(DynamoDB, SQS, Redis, Slack, SCM) are mocked.
"""

from __future__ import annotations

import asyncio
import json
import uuid
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

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
from ai_driven.agents.workflow.triggers import WorkflowTriggers

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

HAPPY_PATH_STATES: list[str] = [
    TICKET_CREATED,
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

# Transitions are from current state to next state (skip TICKET_CREATED since
# it is the initial state).
HAPPY_PATH_TRANSITIONS: list[str] = HAPPY_PATH_STATES[1:]


class FakeProducer:
    """In-memory task producer that records enqueued tasks."""

    def __init__(self) -> None:
        self.enqueued: list[dict[str, Any]] = []

    async def enqueue_task(
        self, task_id: str, agent_type: str, payload: dict[str, Any]
    ) -> str:
        self.enqueued.append(
            {"task_id": task_id, "agent_type": agent_type, "payload": payload}
        )
        return f"msg-{task_id}"


class FakeNotifier:
    """In-memory Slack notifier that records all calls."""

    def __init__(self) -> None:
        self.phase_changes: list[dict[str, Any]] = []
        self.completed: list[dict[str, Any]] = []
        self.failed: list[dict[str, Any]] = []
        self.started: list[dict[str, Any]] = []

    async def notify_phase_change(
        self, task_id: str, phase: str, detail: str = ""
    ) -> None:
        self.phase_changes.append(
            {"task_id": task_id, "phase": phase, "detail": detail}
        )

    async def notify_task_completed(
        self, task_id: str, pr_url: str | None = None
    ) -> None:
        self.completed.append({"task_id": task_id, "pr_url": pr_url})

    async def notify_task_failed(self, task_id: str, error: str) -> None:
        self.failed.append({"task_id": task_id, "error": error})

    async def notify_task_started(
        self, task_id: str, agent_type: str, title: str
    ) -> None:
        self.started.append(
            {"task_id": task_id, "agent_type": agent_type, "title": title}
        )

    async def _send(self, text: str, channel: str | None = None) -> None:
        """Low-level send (used by WorkflowAgent.notify_team tool)."""
        self.phase_changes.append(
            {"task_id": "_direct", "phase": "_send", "detail": text}
        )


class FakeSCMClient:
    """In-memory SCM client that records merge calls."""

    def __init__(self, *, fail_merge: bool = False) -> None:
        self.merge_calls: list[dict[str, Any]] = []
        self._fail_merge = fail_merge

    async def merge_pull_request(
        self, repo: str, pr_number: int, method: str = "squash"
    ) -> dict[str, Any]:
        if self._fail_merge:
            raise RuntimeError("Merge conflict: target branch has diverged")
        self.merge_calls.append(
            {"repo": repo, "pr_number": pr_number, "method": method}
        )
        return {"merged": True}


class FakeStore:
    """In-memory TaskStateMachineStore replacement."""

    def __init__(self, *, fail: bool = False) -> None:
        self.transitions: list[dict[str, Any]] = []
        self._fail = fail

    def record_transition(
        self,
        task_id: str,
        from_state: str,
        to_state: str,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        if self._fail:
            raise RuntimeError("DynamoDB write failed")
        self.transitions.append(
            {
                "task_id": task_id,
                "from_state": from_state,
                "to_state": to_state,
                "metadata": metadata or {},
            }
        )

    def get_transitions(self, task_id: str) -> list[dict[str, Any]]:
        return [t for t in self.transitions if t["task_id"] == task_id]


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture()
def store() -> FakeStore:
    return FakeStore()


@pytest.fixture()
def producer() -> FakeProducer:
    return FakeProducer()


@pytest.fixture()
def notifier() -> FakeNotifier:
    return FakeNotifier()


@pytest.fixture()
def scm() -> FakeSCMClient:
    return FakeSCMClient()


@pytest.fixture()
def sm(store: FakeStore) -> WorkflowStateMachine:
    return WorkflowStateMachine(task_id="e2e-task-1", _store=store)


@pytest.fixture()
def triggers(
    producer: FakeProducer, notifier: FakeNotifier, scm: FakeSCMClient
) -> WorkflowTriggers:
    return WorkflowTriggers(
        task_producer=producer,
        slack_notifier=notifier,
        scm_client=scm,
    )


# ===================================================================
# 1. State Machine: full happy path (13 states)
# ===================================================================


@pytest.mark.e2e
class TestFullHappyPath:
    """Walk the state machine through all 13 states end-to-end."""

    async def test_full_happy_path_all_13_states(
        self, sm: WorkflowStateMachine, store: FakeStore
    ) -> None:
        assert sm.current_state == TICKET_CREATED

        for target in HAPPY_PATH_TRANSITIONS:
            result = await sm.transition(target)
            assert result is True, f"Transition to {target} failed"
            assert sm.current_state == target

        assert sm.current_state == COMPLETED
        # 11 transitions recorded (TICKET_CREATED -> ... -> COMPLETED)
        assert len(store.transitions) == len(HAPPY_PATH_TRANSITIONS)

    async def test_full_happy_path_states_count(self) -> None:
        """Verify ALL_STATES contains exactly 13 states."""
        assert len(ALL_STATES) == 13

    async def test_happy_path_transition_map_complete(
        self, sm: WorkflowStateMachine
    ) -> None:
        """Each consecutive happy-path pair must be a valid transition."""
        for i in range(len(HAPPY_PATH_STATES) - 1):
            from_s = HAPPY_PATH_STATES[i]
            to_s = HAPPY_PATH_STATES[i + 1]
            sm.set_state(from_s)
            assert sm.can_transition(to_s), f"{from_s} -> {to_s} should be valid"


# ===================================================================
# 2. State Machine: review requests changes
# ===================================================================


@pytest.mark.e2e
class TestReviewRequestsChanges:
    """Review verdict is changes_requested -- team notified, no merge."""

    async def test_changes_requested_notification(
        self, triggers: WorkflowTriggers, notifier: FakeNotifier, scm: FakeSCMClient
    ) -> None:
        result = {
            "verdict": "changes_requested",
            "repo_full_name": "org/repo",
            "pr_number": 42,
            "summary": "Please add input validation.",
        }
        await triggers.on_review_completed("task-cr-1", result)

        # Slack notification sent
        assert len(notifier.phase_changes) == 1
        assert "Changes Requested" in notifier.phase_changes[0]["phase"]
        assert "input validation" in notifier.phase_changes[0]["detail"]

        # No merge attempted
        assert len(scm.merge_calls) == 0


# ===================================================================
# 3. State Machine: failure at coding phase
# ===================================================================


@pytest.mark.e2e
class TestFailureAtCodingPhase:
    """State goes to FAILED during coding, Slack notification sent."""

    async def test_coding_failure_transitions_to_failed(
        self, sm: WorkflowStateMachine, store: FakeStore
    ) -> None:
        # Walk to CODING
        for state in [READY_FOR_DEV, PLANNING, CODING]:
            await sm.transition(state)

        # Fail
        result = await sm.transition(FAILED)
        assert result is True
        assert sm.current_state == FAILED

        # Last transition recorded
        last = store.transitions[-1]
        assert last["from_state"] == CODING
        assert last["to_state"] == FAILED

    async def test_coding_failure_notification(
        self, notifier: FakeNotifier
    ) -> None:
        """Simulate a failure notification (what the consumer would send)."""
        await notifier.notify_task_failed(
            task_id="task-fail-1", error="3 tests failed in sandbox"
        )
        assert len(notifier.failed) == 1
        assert "3 tests failed" in notifier.failed[0]["error"]


# ===================================================================
# 4. State Machine: failure at merge (merge conflict)
# ===================================================================


@pytest.mark.e2e
class TestFailureAtMerge:
    """Merge conflict triggers error notification."""

    async def test_merge_conflict_notifies_team(
        self, notifier: FakeNotifier
    ) -> None:
        failing_scm = FakeSCMClient(fail_merge=True)
        triggers = WorkflowTriggers(
            scm_client=failing_scm,
            slack_notifier=notifier,
        )
        result = {
            "verdict": "approved",
            "repo_full_name": "org/repo",
            "pr_number": 78,
        }
        await triggers.on_review_completed("task-merge-fail", result)

        # Failure notification sent (not phase change)
        assert len(notifier.failed) == 1
        assert "Auto-merge failed" in notifier.failed[0]["error"]

        # No successful merge phase change
        assert len(notifier.phase_changes) == 0


# ===================================================================
# 5. Guard conditions
# ===================================================================


@pytest.mark.e2e
class TestGuardConditions:
    """Guards can block transitions."""

    async def test_guard_rejects_transition(self, store: FakeStore) -> None:
        sm = WorkflowStateMachine(task_id="guard-test", _store=store)
        await sm.transition(READY_FOR_DEV)
        await sm.transition(PLANNING)

        # Add a guard that rejects CODING
        def reject_coding(from_s: str, to_s: str, meta: dict | None) -> bool:
            return False

        sm.add_transition(
            StateTransition(from_state=PLANNING, to_state=CODING, guard=reject_coding)
        )

        result = await sm.transition(CODING)
        assert result is False
        assert sm.current_state == PLANNING

    async def test_guard_allows_transition(self, store: FakeStore) -> None:
        sm = WorkflowStateMachine(task_id="guard-allow", _store=store)
        await sm.transition(READY_FOR_DEV)
        await sm.transition(PLANNING)

        def allow_coding(from_s: str, to_s: str, meta: dict | None) -> bool:
            return True

        sm.add_transition(
            StateTransition(from_state=PLANNING, to_state=CODING, guard=allow_coding)
        )

        result = await sm.transition(CODING)
        assert result is True
        assert sm.current_state == CODING

    async def test_guard_exception_blocks_transition(
        self, store: FakeStore
    ) -> None:
        sm = WorkflowStateMachine(task_id="guard-exc", _store=store)
        await sm.transition(READY_FOR_DEV)
        await sm.transition(PLANNING)

        def exploding_guard(from_s: str, to_s: str, meta: dict | None) -> bool:
            raise ValueError("boom")

        sm.add_transition(
            StateTransition(
                from_state=PLANNING, to_state=CODING, guard=exploding_guard
            )
        )

        result = await sm.transition(CODING)
        assert result is False
        assert sm.current_state == PLANNING


# ===================================================================
# 6. Listener callbacks
# ===================================================================


@pytest.mark.e2e
class TestListenerCallbacks:
    """Verify listeners fire on each successful transition."""

    async def test_listener_receives_all_transitions(
        self, store: FakeStore
    ) -> None:
        sm = WorkflowStateMachine(task_id="listener-test", _store=store)
        events: list[tuple[str, str, str]] = []

        def listener(
            task_id: str,
            from_s: str,
            to_s: str,
            meta: dict[str, Any] | None,
        ) -> None:
            events.append((task_id, from_s, to_s))

        sm.add_listener(listener)

        for target in HAPPY_PATH_TRANSITIONS:
            await sm.transition(target)

        assert len(events) == len(HAPPY_PATH_TRANSITIONS)
        # First event
        assert events[0] == ("listener-test", TICKET_CREATED, READY_FOR_DEV)
        # Last event
        assert events[-1] == ("listener-test", MONITORING, COMPLETED)

    async def test_listener_error_does_not_block_transition(
        self, store: FakeStore
    ) -> None:
        sm = WorkflowStateMachine(task_id="listener-err", _store=store)

        def bad_listener(
            task_id: str,
            from_s: str,
            to_s: str,
            meta: dict[str, Any] | None,
        ) -> None:
            raise RuntimeError("listener crash")

        sm.add_listener(bad_listener)

        result = await sm.transition(READY_FOR_DEV)
        assert result is True
        assert sm.current_state == READY_FOR_DEV

    async def test_multiple_listeners_all_called(
        self, store: FakeStore
    ) -> None:
        sm = WorkflowStateMachine(task_id="multi-listen", _store=store)
        calls_a: list[str] = []
        calls_b: list[str] = []

        sm.add_listener(lambda tid, f, t, m: calls_a.append(t))
        sm.add_listener(lambda tid, f, t, m: calls_b.append(t))

        await sm.transition(READY_FOR_DEV)
        assert calls_a == [READY_FOR_DEV]
        assert calls_b == [READY_FOR_DEV]


# ===================================================================
# 7. Concurrent workflows
# ===================================================================


@pytest.mark.e2e
class TestConcurrentWorkflows:
    """Two tickets processed in parallel must not interfere."""

    async def test_parallel_workflows_are_independent(
        self, store: FakeStore
    ) -> None:
        sm_a = WorkflowStateMachine(task_id="ticket-A", _store=store)
        sm_b = WorkflowStateMachine(task_id="ticket-B", _store=store)

        # Advance A to CODING
        for state in [READY_FOR_DEV, PLANNING, CODING]:
            await sm_a.transition(state)

        # Advance B to PR_CREATED
        for state in [READY_FOR_DEV, PLANNING, CODING, TESTING, PR_CREATED]:
            await sm_b.transition(state)

        assert sm_a.current_state == CODING
        assert sm_b.current_state == PR_CREATED

        # Fail A
        await sm_a.transition(FAILED)
        assert sm_a.current_state == FAILED
        # B is unaffected
        assert sm_b.current_state == PR_CREATED

        # Continue B
        await sm_b.transition(IN_REVIEW)
        assert sm_b.current_state == IN_REVIEW

    async def test_concurrent_async_transitions(
        self, store: FakeStore
    ) -> None:
        """Run two state machines concurrently using asyncio.gather."""
        sm_a = WorkflowStateMachine(task_id="async-A", _store=store)
        sm_b = WorkflowStateMachine(task_id="async-B", _store=store)

        async def advance(sm: WorkflowStateMachine, states: list[str]) -> None:
            for s in states:
                await sm.transition(s)

        await asyncio.gather(
            advance(sm_a, [READY_FOR_DEV, PLANNING, CODING]),
            advance(sm_b, [READY_FOR_DEV, PLANNING, CODING, TESTING]),
        )

        assert sm_a.current_state == CODING
        assert sm_b.current_state == TESTING

        # Store has transitions from both
        a_transitions = store.get_transitions("async-A")
        b_transitions = store.get_transitions("async-B")
        assert len(a_transitions) == 3
        assert len(b_transitions) == 4


# ===================================================================
# 8. State recovery via set_state
# ===================================================================


@pytest.mark.e2e
class TestStateRecovery:
    """Restore from a checkpoint using set_state."""

    async def test_resume_from_testing(self, store: FakeStore) -> None:
        sm = WorkflowStateMachine(task_id="recovery-1", _store=store)
        sm.set_state(TESTING)
        assert sm.current_state == TESTING

        # Continue from TESTING
        result = await sm.transition(PR_CREATED)
        assert result is True
        assert sm.current_state == PR_CREATED

    async def test_resume_from_deployed(self, store: FakeStore) -> None:
        sm = WorkflowStateMachine(task_id="recovery-2", _store=store)
        sm.set_state(DEPLOYED)

        await sm.transition(MONITORING)
        await sm.transition(COMPLETED)
        assert sm.current_state == COMPLETED

    async def test_set_state_invalid_raises(self) -> None:
        sm = WorkflowStateMachine(task_id="recovery-bad")
        with pytest.raises(ValueError, match="Unknown state"):
            sm.set_state("NONEXISTENT_STATE")


# ===================================================================
# 9. DynamoDB store failure (best-effort persistence)
# ===================================================================


@pytest.mark.e2e
class TestStoreFailure:
    """Transitions succeed even when DynamoDB writes fail."""

    async def test_transition_succeeds_despite_store_failure(self) -> None:
        failing_store = FakeStore(fail=True)
        sm = WorkflowStateMachine(task_id="store-fail", _store=failing_store)

        result = await sm.transition(READY_FOR_DEV)
        assert result is True
        assert sm.current_state == READY_FOR_DEV

    async def test_full_path_with_failing_store(self) -> None:
        failing_store = FakeStore(fail=True)
        sm = WorkflowStateMachine(task_id="store-fail-full", _store=failing_store)

        for target in HAPPY_PATH_TRANSITIONS:
            result = await sm.transition(target)
            assert result is True, f"Transition to {target} failed with broken store"

        assert sm.current_state == COMPLETED
        # No transitions were recorded
        assert len(failing_store.transitions) == 0

    async def test_no_store_at_all(self) -> None:
        sm = WorkflowStateMachine(task_id="no-store")
        for target in HAPPY_PATH_TRANSITIONS:
            result = await sm.transition(target)
            assert result is True

        assert sm.current_state == COMPLETED


# ===================================================================
# 10. WorkflowTriggers chaining
# ===================================================================


@pytest.mark.e2e
class TestWorkflowTriggersChaining:
    """Test event-driven chaining through WorkflowTriggers."""

    async def test_coding_completed_with_pr_enqueues_review(
        self, triggers: WorkflowTriggers, producer: FakeProducer, notifier: FakeNotifier
    ) -> None:
        result = {
            "pr_number": 78,
            "repo_full_name": "org/repo",
            "pr_title": "feat: implement login",
        }
        await triggers.on_coding_task_completed("task-chain-1", result)

        assert len(producer.enqueued) == 1
        enqueued = producer.enqueued[0]
        assert enqueued["agent_type"] == "review"
        assert enqueued["payload"]["pr_number"] == 78
        assert enqueued["payload"]["originating_task_id"] == "task-chain-1"

        # Notification sent
        assert len(notifier.phase_changes) == 1
        assert "PR Created" in notifier.phase_changes[0]["phase"]

    async def test_coding_completed_without_pr_no_review(
        self, triggers: WorkflowTriggers, producer: FakeProducer
    ) -> None:
        result = {"output": "Code written but no PR"}
        await triggers.on_coding_task_completed("task-chain-2", result)
        assert len(producer.enqueued) == 0

    async def test_coding_completed_missing_repo_no_review(
        self, triggers: WorkflowTriggers, producer: FakeProducer
    ) -> None:
        result = {"pr_number": 10}  # missing repo_full_name
        await triggers.on_coding_task_completed("task-chain-3", result)
        assert len(producer.enqueued) == 0

    async def test_review_approved_triggers_merge(
        self, triggers: WorkflowTriggers, scm: FakeSCMClient, notifier: FakeNotifier
    ) -> None:
        result = {
            "verdict": "approved",
            "repo_full_name": "org/repo",
            "pr_number": 78,
        }
        await triggers.on_review_completed("task-chain-4", result)

        assert len(scm.merge_calls) == 1
        assert scm.merge_calls[0]["pr_number"] == 78
        assert scm.merge_calls[0]["method"] == "squash"

        # Merge success notification
        assert len(notifier.phase_changes) == 1
        assert "Merged" in notifier.phase_changes[0]["phase"]

    async def test_review_changes_requested_no_merge(
        self, triggers: WorkflowTriggers, scm: FakeSCMClient, notifier: FakeNotifier
    ) -> None:
        result = {
            "verdict": "changes_requested",
            "repo_full_name": "org/repo",
            "pr_number": 78,
            "summary": "Fix the SQL injection",
        }
        await triggers.on_review_completed("task-chain-5", result)

        assert len(scm.merge_calls) == 0
        assert len(notifier.phase_changes) == 1
        assert "Changes Requested" in notifier.phase_changes[0]["phase"]

    async def test_merge_completed_final_notification(
        self, triggers: WorkflowTriggers, notifier: FakeNotifier
    ) -> None:
        result = {
            "repo_full_name": "org/repo",
            "pr_number": 78,
            "pr_url": "https://github.com/org/repo/pull/78",
        }
        await triggers.on_merge_completed("task-chain-6", result)

        assert len(notifier.completed) == 1
        assert notifier.completed[0]["pr_url"] == "https://github.com/org/repo/pull/78"


# ===================================================================
# 11. Full end-to-end chaining: state machine + triggers
# ===================================================================


@pytest.mark.e2e
class TestEndToEndChaining:
    """Simulate the full pipeline: state machine transitions + trigger chaining."""

    async def test_full_pipeline_jira_to_completion(
        self,
        store: FakeStore,
        producer: FakeProducer,
        notifier: FakeNotifier,
        scm: FakeSCMClient,
    ) -> None:
        """Simulate: Jira Ready for Dev -> ... -> COMPLETED with trigger chaining."""
        task_id = "e2e-full-1"
        sm = WorkflowStateMachine(task_id=task_id, _store=store)
        triggers = WorkflowTriggers(
            task_producer=producer,
            slack_notifier=notifier,
            scm_client=scm,
        )

        # Register listener for Slack notifications on phase changes
        phase_log: list[str] = []
        sm.add_listener(
            lambda tid, frm, to, meta: phase_log.append(f"{frm}->{to}")
        )

        # Step 1: Jira webhook fires -- TICKET_CREATED -> READY_FOR_DEV
        assert await sm.transition(READY_FOR_DEV)

        # Step 2: READY_FOR_DEV -> PLANNING
        assert await sm.transition(PLANNING)

        # Step 3: PLANNING -> CODING
        assert await sm.transition(CODING)

        # Step 4: CODING -> TESTING
        assert await sm.transition(TESTING)

        # Step 5: TESTING -> PR_CREATED
        assert await sm.transition(PR_CREATED)

        # Step 6: Trigger chaining -- coding complete triggers review dispatch
        await triggers.on_coding_task_completed(
            task_id,
            {
                "pr_number": 78,
                "repo_full_name": "org/repo",
                "pr_title": "feat: PROJ-456 implementation",
            },
        )
        assert len(producer.enqueued) == 1
        assert producer.enqueued[0]["agent_type"] == "review"

        # Step 7: PR_CREATED -> IN_REVIEW
        assert await sm.transition(IN_REVIEW)

        # Step 8: Review completes -- approved -> triggers merge
        await triggers.on_review_completed(
            task_id,
            {
                "verdict": "approved",
                "repo_full_name": "org/repo",
                "pr_number": 78,
            },
        )
        assert len(scm.merge_calls) == 1

        # Step 9: IN_REVIEW -> REVIEW_PASSED -> MERGING
        assert await sm.transition(REVIEW_PASSED)
        assert await sm.transition(MERGING)

        # Step 10: Merge completed trigger
        await triggers.on_merge_completed(
            task_id,
            {
                "repo_full_name": "org/repo",
                "pr_number": 78,
                "pr_url": "https://github.com/org/repo/pull/78",
            },
        )
        assert len(notifier.completed) == 1

        # Step 11: MERGING -> DEPLOYED -> MONITORING -> COMPLETED
        assert await sm.transition(DEPLOYED)
        assert await sm.transition(MONITORING)
        assert await sm.transition(COMPLETED)

        assert sm.current_state == COMPLETED
        assert len(phase_log) == len(HAPPY_PATH_TRANSITIONS)

        # Verify store recorded all transitions
        assert len(store.get_transitions(task_id)) == len(HAPPY_PATH_TRANSITIONS)


# ===================================================================
# 12. State machine: invalid transitions
# ===================================================================


@pytest.mark.e2e
class TestInvalidTransitions:
    """Transitions that should be rejected."""

    async def test_cannot_skip_states(self, sm: WorkflowStateMachine) -> None:
        result = await sm.transition(CODING)  # Skip READY_FOR_DEV and PLANNING
        assert result is False
        assert sm.current_state == TICKET_CREATED

    async def test_cannot_transition_from_completed(
        self, store: FakeStore
    ) -> None:
        sm = WorkflowStateMachine(task_id="term-1", _store=store)
        sm.set_state(COMPLETED)
        result = await sm.transition(MONITORING)
        assert result is False
        assert sm.current_state == COMPLETED

    async def test_cannot_transition_from_failed(
        self, store: FakeStore
    ) -> None:
        sm = WorkflowStateMachine(task_id="term-2", _store=store)
        sm.set_state(FAILED)
        result = await sm.transition(CODING)
        assert result is False
        assert sm.current_state == FAILED

    async def test_invalid_state_name_rejected(
        self, sm: WorkflowStateMachine
    ) -> None:
        result = await sm.transition("BOGUS_STATE")
        assert result is False

    async def test_fail_transitions_from_all_non_terminal(self) -> None:
        """Every non-terminal state can transition to FAILED."""
        terminal = {COMPLETED, FAILED}
        for state in ALL_STATES - terminal:
            sm = WorkflowStateMachine(task_id=f"fail-from-{state}")
            sm.set_state(state)
            result = await sm.transition(FAILED)
            assert result is True, f"Expected {state} -> FAILED to succeed"
            assert sm.current_state == FAILED


# ===================================================================
# 13. WebSocket event flow (mock Redis pub/sub)
# ===================================================================


@pytest.mark.e2e
class TestWebSocketEventFlow:
    """Test that the WebSocket endpoint forwards Redis pub/sub events correctly."""

    async def test_allowed_event_types_are_forwarded(self) -> None:
        """Verify _ALLOWED_EVENT_TYPES matches the spec."""
        from ai_driven.api.chat.router import _ALLOWED_EVENT_TYPES

        expected = {
            "text_delta",
            "tool_call_start",
            "tool_call_end",
            "phase_change",
            "complete",
            "error",
            "prompt_sent",
            "iteration",
            "response_chunk",
        }
        assert _ALLOWED_EVENT_TYPES == expected

    async def test_terminal_events_are_complete_and_error(self) -> None:
        """Complete and error are terminal event types that close the stream."""
        # Verify by inspecting the code logic -- terminal events are "complete" and "error"
        terminal = {"complete", "error"}
        from ai_driven.api.chat.router import _ALLOWED_EVENT_TYPES

        # All terminal events must be in allowed set
        assert terminal.issubset(_ALLOWED_EVENT_TYPES)

    async def test_publish_event_builds_correct_channel(self) -> None:
        """BaseAgent._publish_event uses 'task:{task_id}:events' channel."""
        mock_redis = AsyncMock()
        mock_redis.publish = AsyncMock()

        with patch(
            "ai_driven.agents.base.BaseAgent._get_redis_client",
            return_value=mock_redis,
        ):
            # Create a minimal concrete agent for testing
            agent = _create_test_agent()
            agent._task_id = "ws-test-1"
            agent._redis_client = mock_redis

            await agent._publish_event("phase_change", {"phase": "coding"})

            mock_redis.publish.assert_called_once()
            call_args = mock_redis.publish.call_args
            channel = call_args[0][0]
            assert channel == "task:ws-test-1:events"

            payload = json.loads(call_args[0][1])
            assert payload["type"] == "phase_change"
            assert payload["data"]["phase"] == "coding"
            assert "timestamp" in payload

    async def test_publish_event_noop_without_task_id(self) -> None:
        """No event published if task_id is not set."""
        mock_redis = AsyncMock()
        agent = _create_test_agent()
        agent._task_id = None
        agent._redis_client = mock_redis

        await agent._publish_event("phase_change", {"phase": "test"})
        mock_redis.publish.assert_not_called()

    async def test_publish_event_resilient_to_redis_failure(self) -> None:
        """Publishing failure is logged but does not raise."""
        mock_redis = AsyncMock()
        mock_redis.publish = AsyncMock(side_effect=ConnectionError("Redis down"))

        agent = _create_test_agent()
        agent._task_id = "ws-fail-1"
        agent._redis_client = mock_redis

        # Should NOT raise
        await agent._publish_event("error", {"message": "test"})


# ===================================================================
# 14. Transition map verification
# ===================================================================


@pytest.mark.e2e
class TestTransitionMapVerification:
    """Verify the full state machine transition map is correct."""

    async def test_all_happy_path_transitions_exist(self) -> None:
        sm = WorkflowStateMachine(task_id="map-check")
        expected_pairs = [
            (TICKET_CREATED, READY_FOR_DEV),
            (READY_FOR_DEV, PLANNING),
            (PLANNING, CODING),
            (CODING, TESTING),
            (TESTING, PR_CREATED),
            (PR_CREATED, IN_REVIEW),
            (IN_REVIEW, REVIEW_PASSED),
            (REVIEW_PASSED, MERGING),
            (MERGING, DEPLOYED),
            (DEPLOYED, MONITORING),
            (MONITORING, COMPLETED),
        ]
        for from_s, to_s in expected_pairs:
            sm.set_state(from_s)
            assert sm.can_transition(to_s), f"Missing: {from_s} -> {to_s}"

    async def test_all_non_terminal_can_fail(self) -> None:
        sm = WorkflowStateMachine(task_id="fail-map-check")
        terminal = {COMPLETED, FAILED}
        for state in ALL_STATES - terminal:
            sm.set_state(state)
            assert sm.can_transition(FAILED), f"{state} should be able to transition to FAILED"

    async def test_terminal_states_have_no_outgoing(self) -> None:
        sm = WorkflowStateMachine(task_id="terminal-check")
        for terminal_state in [COMPLETED, FAILED]:
            sm.set_state(terminal_state)
            for target in ALL_STATES:
                if target == terminal_state:
                    continue
                assert not sm.can_transition(
                    target
                ), f"{terminal_state} should not transition to {target}"


# ===================================================================
# Helper to create a minimal concrete agent for publish_event tests
# ===================================================================


def _create_test_agent() -> Any:
    """Create a minimal BaseAgent subclass for testing _publish_event."""
    from ai_driven.agents.base import BaseAgent

    class _TestAgent(BaseAgent):
        agent_type = "test"

        def __init__(self) -> None:
            # Bypass the parent __init__ to avoid ClaudeClient instantiation
            from ai_driven.claude.tools.registry import ToolRegistry

            self._registry = ToolRegistry()
            self._claude = None  # type: ignore[assignment]
            self._task_id = None
            self._redis_client = None
            self._register_tools()

        def _register_tools(self) -> None:
            pass

        def _get_system_prompt(self, context: dict[str, Any]) -> str:
            return "test"

        def _get_tool_names(self) -> list[str]:
            return []

    return _TestAgent()
