"""Unit tests: admin router success-rate math buckets T23 statuses correctly.

Distinct from ``test_admin_dashboard_success_rate.py`` (which pins the
bucket-constant invariants). This file exercises the *arithmetic* that
consumes those buckets — given a mix of task statuses, does the
dashboard return the right gauge?

Red-team-motivated: the silent failure mode is a visible-but-wrong
gauge. E.g. if ``qa_failed`` were missed from the failure bucket,
a dashboard showing 100% success would mask a T26 live-mode
regression where every task blocks.

The insights endpoint's success-rate math is pure list-comprehension
over a pre-fetched list of tasks (see
``src/ai_driven/api/admin/router.py`` around line 1914-1948), so we
can unit-test it without spinning up a DB by importing the
membership-set constants directly.
"""
from __future__ import annotations

from types import SimpleNamespace

from ai_driven.api.admin.router import (
    FAILURE_STATUSES,
    IN_FLIGHT_STATUSES,
    SUCCESS_STATUSES,
    TERMINAL_STATUSES,
)


def _task(status: str) -> SimpleNamespace:
    return SimpleNamespace(status=status)


def _success_rate(tasks: list[SimpleNamespace]) -> float:
    """Mirror of the admin-router insights success-rate math.

    Kept in lock-step with router lines ~1914-1948. If the router
    drifts away from this shape, update this helper AND the router
    in the same commit.
    """
    completed = [t for t in tasks if t.status in SUCCESS_STATUSES]
    failed = [t for t in tasks if t.status in FAILURE_STATUSES]
    total_terminal = len(completed) + len(failed)
    if total_terminal == 0:
        return 0.0
    return len(completed) / total_terminal * 100


# ---------------------------------------------------------------------------
# Success-rate arithmetic over mixed-status task lists
# ---------------------------------------------------------------------------


def test_qa_failed_lands_in_failure_bucket():
    """A pipeline with 2 completed, 2 qa_failed = 50% success rate."""
    tasks = [_task("completed"), _task("completed"),
             _task("qa_failed"), _task("qa_failed")]
    assert _success_rate(tasks) == 50.0


def test_blocked_lands_in_failure_bucket():
    """T21 blocked continues to count as failure (unchanged by T23)."""
    tasks = [_task("completed"), _task("blocked")]
    assert _success_rate(tasks) == 50.0


def test_awaiting_qa_excluded_from_both_buckets():
    """awaiting_qa is in-flight; it must not move the gauge.

    Without this rule, the moment a task enters QA review the gauge
    would dip (treated as failure) or spike (treated as success).
    Both are wrong — the gauge should only change when the verdict
    resolves.
    """
    # 1 completed + 1 awaiting_qa should be 100% (only the completed
    # task has a verdict; the awaiting one doesn't count).
    tasks = [_task("completed"), _task("awaiting_qa")]
    assert _success_rate(tasks) == 100.0

    # 1 failed + 1 awaiting_qa should be 0%.
    tasks = [_task("failed"), _task("awaiting_qa")]
    assert _success_rate(tasks) == 0.0


def test_all_failure_buckets_mixed():
    """Every failure status (failed, blocked, qa_failed) counts equally.

    Without this, a T23 live-mode cutover that triples the qa_failed
    count but leaves ``failed`` flat would look like success held
    steady — the opposite of the truth.
    """
    # 1 success, 3 distinct failures.
    tasks = [_task("completed"),
             _task("failed"), _task("blocked"), _task("qa_failed")]
    assert _success_rate(tasks) == 25.0


def test_empty_and_all_inflight_returns_zero():
    """No terminal tasks → 0% (avoid div-by-zero, match router
    behaviour at line 1948)."""
    assert _success_rate([]) == 0.0
    assert _success_rate([_task("queued"), _task("running"),
                          _task("awaiting_qa")]) == 0.0


def test_all_success_returns_100():
    tasks = [_task("completed"), _task("completed"), _task("completed")]
    assert _success_rate(tasks) == 100.0


def test_all_failure_returns_0():
    tasks = [_task("failed"), _task("blocked"), _task("qa_failed")]
    assert _success_rate(tasks) == 0.0


# ---------------------------------------------------------------------------
# Bucket-membership shape guards (complement test_admin_dashboard_success_rate)
# ---------------------------------------------------------------------------


def test_terminal_covers_exactly_success_union_failure():
    assert TERMINAL_STATUSES == SUCCESS_STATUSES | FAILURE_STATUSES


def test_in_flight_disjoint_from_terminal():
    assert IN_FLIGHT_STATUSES.isdisjoint(TERMINAL_STATUSES)


def test_every_current_valid_status_is_classified():
    """Every real status in ck_tasks_status must be in some bucket.

    If a future migration adds a new status (e.g., 'cancelled',
    'needs_retry') and forgets to classify it, tasks in that status
    would silently vanish from the dashboard. This test makes that
    omission loud.
    """
    # Current valid statuses per ck_tasks_status (T22 + T23):
    known_statuses = {
        "queued", "running", "completed", "failed",
        "blocked", "awaiting_qa", "qa_failed",
    }
    all_classified = TERMINAL_STATUSES | IN_FLIGHT_STATUSES
    unclassified = known_statuses - all_classified
    assert unclassified == set(), (
        f"Statuses {unclassified} are not in any bucket. Either add "
        f"them to TERMINAL_STATUSES (if terminal) or IN_FLIGHT_STATUSES "
        f"(if not), or — if deliberately excluded — update this test "
        f"with the reason."
    )
