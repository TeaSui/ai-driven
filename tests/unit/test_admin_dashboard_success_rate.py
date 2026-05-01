"""Unit test pinning the dashboard success-rate denominator semantics.

T21 adds ``status='blocked'`` as a terminal state. The dashboard's success
rate is::

    success_rate = completed / (completed + failed)

``blocked`` is correctly excluded from both sides — an upstream specialist
block is not a coding-agent failure, and shouldn't drag the numerator
down. This test pins that decision so a future writer who "helpfully"
lumps blocked into failed doesn't silently move the gauge.

See pre-baked red-team finding #5 in the T21 task brief.
"""
from __future__ import annotations

import ast
import pathlib

import pytest


ROUTER_PATH = (
    pathlib.Path(__file__).resolve().parents[2]
    / "src" / "ai_driven" / "api" / "admin" / "router.py"
)


def _status_equality_checks(source: str) -> list[str]:
    """Return every RHS string of a ``TaskModel.status == 'X'`` comparison."""
    tree = ast.parse(source)
    hits: list[str] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Compare):
            continue
        # Look for: TaskModel.status == <Constant str>
        left = node.left
        if (
            isinstance(left, ast.Attribute)
            and left.attr == "status"
            and isinstance(left.value, ast.Name)
            and left.value.id == "TaskModel"
            and len(node.ops) == 1
            and isinstance(node.ops[0], ast.Eq)
            and isinstance(node.comparators[0], ast.Constant)
            and isinstance(node.comparators[0].value, str)
        ):
            hits.append(node.comparators[0].value)
    return hits


def test_dashboard_success_rate_denominator_excludes_blocked() -> None:
    """completed/(completed+failed) — 'blocked' must NOT appear as a terminal filter.

    If this ever fails, the writer has either:
      a) Added a branch that counts 'blocked' into completed/failed (wrong —
         changes the gauge silently), or
      b) Removed the exact-equality pattern entirely (requires revisiting
         this test to match the new access pattern).

    Either way the change needs explicit human review.
    """
    source = ROUTER_PATH.read_text()
    checks = _status_equality_checks(source)

    # The dashboard summary block uses exactly these two terminal filters:
    assert "completed" in checks, (
        "Expected TaskModel.status == 'completed' in admin router "
        "(dashboard success-rate numerator)."
    )
    assert "failed" in checks, (
        "Expected TaskModel.status == 'failed' in admin router "
        "(dashboard success-rate denominator)."
    )
    # Blocked must NOT be treated as a terminal status in the success-rate
    # math — it's a distinct triage bucket.
    assert "blocked" not in checks, (
        "Regression: admin router is now treating 'blocked' as a terminal "
        "status equivalent to completed/failed. This changes the success-"
        "rate denominator. See T21 red-team finding #5 — blocks must be "
        "excluded from both numerator and denominator."
    )


def test_blocked_status_declared_in_migration() -> None:
    """Belt-and-braces: T21's migration adds 'blocked' to ck_tasks_status."""
    mig_path = (
        pathlib.Path(__file__).resolve().parents[2]
        / "alembic" / "versions" / "c3d4e5f6a7b8_add_blocked_status.py"
    )
    assert mig_path.exists(), "T21 migration file missing"
    src = mig_path.read_text()
    # The enum tuple for T21 must list 'blocked' but NOT 'qa_failed'.
    # Adding unused enum values now creates coupling with T22.
    ns: dict = {}
    exec(compile(src, str(mig_path), "exec"), ns)
    t21_values = ns["_VALID_STATUSES_T21"]
    assert "blocked" in t21_values
    assert "qa_failed" not in t21_values, (
        "T21's migration must NOT declare 'qa_failed' in the enum — that's T22's scope."
    )
