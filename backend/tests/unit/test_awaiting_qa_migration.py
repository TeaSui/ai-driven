"""Belt-and-braces: T22's migration adds 'awaiting_qa' to ck_tasks_status.

Mirrors tests/unit/test_admin_dashboard_success_rate.py::
test_blocked_status_declared_in_migration so status-enum regressions are
caught at unit level without spinning up Alembic against a real DB.
"""
from __future__ import annotations

import pathlib


def test_awaiting_qa_status_declared_in_migration() -> None:
    mig_path = (
        pathlib.Path(__file__).resolve().parents[2]
        / "alembic" / "versions"
        / "d4e5f6a7b8c9_add_awaiting_qa_status.py"
    )
    assert mig_path.exists(), "T22 migration file missing"
    src = mig_path.read_text()
    ns: dict = {}
    exec(compile(src, str(mig_path), "exec"), ns)
    t22_values = ns["_VALID_STATUSES_T22"]
    assert "awaiting_qa" in t22_values
    # qa_failed is T23's scope — must NOT appear here. Adding unused
    # enum values now creates coupling with T23.
    assert "qa_failed" not in t22_values, (
        "T22's migration must NOT declare 'qa_failed' — that's T23's scope."
    )
    # T21's additions must be preserved.
    assert "blocked" in t22_values
    # down_revision MUST point at T21's blocked-status migration so the
    # dependency chain stays linear.
    assert ns["down_revision"] == "c3d4e5f6a7b8"
