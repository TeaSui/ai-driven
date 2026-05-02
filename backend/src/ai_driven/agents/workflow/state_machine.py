"""Workflow state machine for task lifecycle tracking."""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable

from ai_driven.db.dynamo.models import TaskStateMachineStore

logger = logging.getLogger(__name__)

# --- States ---

TICKET_CREATED = "TICKET_CREATED"
READY_FOR_DEV = "READY_FOR_DEV"
PLANNING = "PLANNING"
CODING = "CODING"
TESTING = "TESTING"
PR_CREATED = "PR_CREATED"
IN_REVIEW = "IN_REVIEW"
REVIEW_PASSED = "REVIEW_PASSED"
MERGING = "MERGING"
DEPLOYED = "DEPLOYED"
MONITORING = "MONITORING"
COMPLETED = "COMPLETED"
FAILED = "FAILED"

ALL_STATES: frozenset[str] = frozenset({
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
    FAILED,
})

# States from which no forward transitions are allowed (terminal states).
_TERMINAL_STATES: frozenset[str] = frozenset({COMPLETED, FAILED})

# Ordered happy-path transitions.
_HAPPY_PATH: list[tuple[str, str]] = [
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

# Every non-terminal state can transition to FAILED.
_FAIL_TRANSITIONS: list[tuple[str, str]] = [
    (state, FAILED) for state in ALL_STATES if state not in _TERMINAL_STATES
]

TransitionGuard = Callable[[str, str, dict[str, Any] | None], bool]
TransitionListener = Callable[[str, str, str, dict[str, Any] | None], Any]


@dataclass
class StateTransition:
    """A valid state transition with an optional guard condition."""

    from_state: str
    to_state: str
    guard: TransitionGuard | None = None


def _build_transition_map(
    transitions: list[StateTransition],
) -> dict[str, dict[str, StateTransition]]:
    """Index transitions by (from_state, to_state) for O(1) lookup."""
    result: dict[str, dict[str, StateTransition]] = {}
    for t in transitions:
        result.setdefault(t.from_state, {})[t.to_state] = t
    return result


def _default_transitions() -> list[StateTransition]:
    """Build the full set of valid transitions (happy path + fail)."""
    transitions: list[StateTransition] = []
    for from_s, to_s in _HAPPY_PATH:
        transitions.append(StateTransition(from_state=from_s, to_state=to_s))
    for from_s, to_s in _FAIL_TRANSITIONS:
        transitions.append(StateTransition(from_state=from_s, to_state=to_s))
    return transitions


@dataclass
class WorkflowStateMachine:
    """Tracks task lifecycle through well-defined states.

    Persists transitions to DynamoDB via ``TaskStateMachineStore`` and
    notifies registered listeners on every successful transition.
    """

    task_id: str
    _store: TaskStateMachineStore | None = field(default=None, repr=False)
    _current_state: str = field(default=TICKET_CREATED, init=False)
    _listeners: list[TransitionListener] = field(default_factory=list, init=False, repr=False)
    _transition_map: dict[str, dict[str, StateTransition]] = field(
        default_factory=lambda: _build_transition_map(_default_transitions()),
        init=False,
        repr=False,
    )

    # --- public API -----------------------------------------------------------

    @property
    def current_state(self) -> str:
        return self._current_state

    def can_transition(self, to_state: str) -> bool:
        """Return True if a transition to *to_state* is structurally valid."""
        if to_state not in ALL_STATES:
            return False
        from_map = self._transition_map.get(self._current_state)
        if from_map is None:
            return False
        transition = from_map.get(to_state)
        if transition is None:
            return False
        if transition.guard is not None:
            try:
                return transition.guard(self._current_state, to_state, None)
            except Exception:
                logger.exception(
                    "Guard raised for %s -> %s on task %s",
                    self._current_state,
                    to_state,
                    self.task_id,
                )
                return False
        return True

    async def transition(self, to_state: str, metadata: dict[str, Any] | None = None) -> bool:
        """Attempt a state transition.  Returns True if successful."""
        if to_state not in ALL_STATES:
            logger.warning(
                "Invalid target state '%s' for task %s", to_state, self.task_id
            )
            return False

        from_map = self._transition_map.get(self._current_state)
        if from_map is None or to_state not in from_map:
            logger.warning(
                "No transition %s -> %s for task %s",
                self._current_state,
                to_state,
                self.task_id,
            )
            return False

        transition = from_map[to_state]

        # Evaluate guard
        if transition.guard is not None:
            try:
                allowed = transition.guard(self._current_state, to_state, metadata)
            except Exception:
                logger.exception(
                    "Guard raised for %s -> %s on task %s",
                    self._current_state,
                    to_state,
                    self.task_id,
                )
                return False
            if not allowed:
                logger.info(
                    "Guard rejected %s -> %s for task %s",
                    self._current_state,
                    to_state,
                    self.task_id,
                )
                return False

        previous = self._current_state
        self._current_state = to_state

        # Persist
        if self._store is not None:
            try:
                self._store.record_transition(
                    task_id=self.task_id,
                    from_state=previous,
                    to_state=to_state,
                    metadata=metadata,
                )
            except Exception:
                logger.exception(
                    "Failed to persist transition %s -> %s for task %s",
                    previous,
                    to_state,
                    self.task_id,
                )

        # Notify listeners
        for listener in self._listeners:
            try:
                listener(self.task_id, previous, to_state, metadata)
            except Exception:
                logger.exception(
                    "Listener error on %s -> %s for task %s",
                    previous,
                    to_state,
                    self.task_id,
                )

        logger.info(
            "Task %s transitioned %s -> %s", self.task_id, previous, to_state
        )
        return True

    def add_listener(self, listener: TransitionListener) -> None:
        """Register a callback invoked after every successful transition."""
        self._listeners.append(listener)

    def add_transition(self, transition: StateTransition) -> None:
        """Add a custom transition (e.g. for retry loops)."""
        self._transition_map.setdefault(transition.from_state, {})[
            transition.to_state
        ] = transition

    def set_state(self, state: str) -> None:
        """Force-set state without validation (used when restoring from store)."""
        if state not in ALL_STATES:
            raise ValueError(f"Unknown state: {state}")
        self._current_state = state
