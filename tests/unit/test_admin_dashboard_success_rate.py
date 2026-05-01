"""Unit test pinning the dashboard success-rate bucket semantics.

Evolution:

* T21 added ``status='blocked'`` as a terminal state. Originally the
  dashboard used ``TaskModel.status == 'completed'`` / ``=='failed'``
  exact-equality filters and the pin-test asserted that pattern to
  prevent a future writer from silently lumping blocked into failed.

* T23 (THIS) adds ``status='qa_failed'`` for live-mode post-coding QA
  blocks and ``status='awaiting_qa'`` as an in-flight state. The
  admin router migrated to shared ``SUCCESS_STATUSES`` /
  ``FAILURE_STATUSES`` / ``IN_FLIGHT_STATUSES`` constants so the
  buckets are declared in one place. The pin-test now asserts the
  membership of those constants, which is the equivalent invariant.

The invariant that MUST hold across refactors:

    success_rate = success_count / (success_count + failure_count)

where:
    SUCCESS_STATUSES  == {"completed"}
    FAILURE_STATUSES  >= {"failed", "blocked", "qa_failed"}
    IN_FLIGHT_STATUSES >= {"awaiting_qa"}   # NOT counted as terminal

``awaiting_qa`` must NEVER leak into either bucket — that would move
the gauge the moment a task enters QA review rather than when the
verdict resolves.
"""
from __future__ import annotations

from ai_driven.api.admin.router import (
    FAILURE_STATUSES,
    IN_FLIGHT_STATUSES,
    SUCCESS_STATUSES,
    TERMINAL_STATUSES,
)


def test_success_statuses_is_exactly_completed() -> None:
    """Success rate numerator is ONLY fully-completed tasks.

    If a future writer widens this (e.g., counting "pass_with_notes"
    QA verdicts as a separate success status, or merging qa_failed
    in as a "soft success"), the gauge would jump silently. Keep
    it a one-element set so the math is obvious.
    """
    assert SUCCESS_STATUSES == frozenset({"completed"}), (
        "SUCCESS_STATUSES must contain exactly 'completed'. Widening "
        "this set silently changes the dashboard success-rate gauge."
    )


def test_failure_statuses_includes_t21_and_t23_buckets() -> None:
    """FAILURE_STATUSES must bucket every real-failure terminal state.

    Missing entries mean failed tasks silently drop out of the
    success-rate denominator (making the rate look artificially
    high). Since T23 live-mode adds ``qa_failed``, any regression
    that forgets to add it would halve the visible failure rate
    the moment cutover flips.
    """
    assert "failed" in FAILURE_STATUSES, "legacy failed missing"
    assert "blocked" in FAILURE_STATUSES, "T21 blocked missing"
    assert "qa_failed" in FAILURE_STATUSES, (
        "T23 qa_failed missing from failure bucket — live-mode "
        "post-coding blocks would silently vanish from metrics."
    )


def test_awaiting_qa_is_in_flight_not_terminal() -> None:
    """awaiting_qa must NEVER be treated as terminal.

    A task in QA review has no verdict yet — counting it as either
    success or failure moves the gauge prematurely. The moment the
    verdict resolves, the status flips to completed or qa_failed
    and the task enters the correct bucket.
    """
    assert "awaiting_qa" in IN_FLIGHT_STATUSES, (
        "awaiting_qa missing from IN_FLIGHT_STATUSES — would break "
        "active-agents dashboard display."
    )
    assert "awaiting_qa" not in TERMINAL_STATUSES, (
        "awaiting_qa leaked into TERMINAL_STATUSES — would move "
        "success-rate gauge prematurely."
    )
    assert "awaiting_qa" not in SUCCESS_STATUSES
    assert "awaiting_qa" not in FAILURE_STATUSES


def test_terminal_is_union_of_success_and_failure() -> None:
    """TERMINAL_STATUSES = SUCCESS_STATUSES | FAILURE_STATUSES (no gaps)."""
    assert TERMINAL_STATUSES == SUCCESS_STATUSES | FAILURE_STATUSES


def test_success_failure_are_disjoint() -> None:
    """A status cannot be both success and failure."""
    assert SUCCESS_STATUSES.isdisjoint(FAILURE_STATUSES), (
        "SUCCESS and FAILURE buckets overlap — success-rate math "
        "would double-count."
    )


def test_in_flight_disjoint_from_terminal() -> None:
    """In-flight and terminal buckets must not overlap."""
    assert IN_FLIGHT_STATUSES.isdisjoint(TERMINAL_STATUSES), (
        "IN_FLIGHT and TERMINAL buckets overlap — a task in QA "
        "review would be counted both as active and resolved."
    )


def test_blocked_status_declared_in_migration() -> None:
    """Belt-and-braces: T21's migration adds 'blocked' to ck_tasks_status."""
    import pathlib

    mig_path = (
        pathlib.Path(__file__).resolve().parents[2]
        / "alembic" / "versions" / "c3d4e5f6a7b8_add_blocked_status.py"
    )
    assert mig_path.exists(), "T21 migration file missing"
    src = mig_path.read_text()
    ns: dict = {}
    exec(compile(src, str(mig_path), "exec"), ns)
    t21_values = ns["_VALID_STATUSES_T21"]
    assert "blocked" in t21_values
    assert "qa_failed" not in t21_values, (
        "T21's migration must NOT declare 'qa_failed' in the enum — that's T22's scope."
    )
