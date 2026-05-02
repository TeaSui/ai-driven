"""T25 — Override-action policy (UI hint only).

Exposes :func:`available_actions` for the GET /tasks/{id}/artifacts
endpoint. Given a task row, returns the subset of T24 override actions
(``rerun_coding``, ``rerun_qa``, ``force_pass``, ``reap``) that are
currently offerable to the UI.

This module is intentionally **additive**. T24's POST /override-block
keeps its own inline ``_validate_action_for_status`` function — that
function is covered by 11 tests and is the authoritative gate. This
module is a *UI hint* so the Specialists tab knows which buttons to
render. If the UI and the POST validator ever disagree (e.g. race on
status change), the POST validator wins — it returns 400.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from ai_driven.api.admin._overrides_helpers import is_stuck, parse_iso_ts


# Map from current task status → the T24 actions that MIGHT be offered
# from that status. For ``reap`` statuses we still have to check the
# stuck threshold at call time — the table below is the superset.
OVERRIDEABLE_ACTIONS: dict[str, list[str]] = {
    "blocked": ["rerun_coding"],
    "qa_failed": ["rerun_qa", "force_pass"],
    "orchestrating": ["reap"],
    "awaiting_qa": ["reap"],
    "running": ["reap"],
}


def available_actions(task_row: Any, *, now: datetime | None = None) -> list[str]:
    """Return the T24 actions offerable RIGHT NOW for ``task_row``.

    ``task_row`` is expected to expose the same attributes a
    ``db.postgres.models.Task`` row does: ``status``, ``output_data``,
    ``last_heartbeat_at``, ``created_at``.

    ``now`` is injectable for deterministic testing; defaults to the
    current UTC time.

    Semantics mirror T24's inline ``_validate_action_for_status`` but
    only for the *eligibility* question — we never mutate state here.
    For the ``reap`` actions we enforce the same 30-min stuck threshold
    the POST endpoint + reapers use; if the task isn't stuck yet, the
    list does NOT contain ``reap``.
    """
    status = getattr(task_row, "status", None)
    if status not in OVERRIDEABLE_ACTIONS:
        return []

    if status in ("blocked", "qa_failed"):
        # No stuck-threshold gating — status alone is enough.
        return list(OVERRIDEABLE_ACTIONS[status])

    # reap statuses — only offerable once the task has been stuck past
    # the threshold. We use the same stuck signal the POST endpoint
    # uses so UI and server never disagree.
    current = now or datetime.now(timezone.utc)
    output_data = getattr(task_row, "output_data", None) or {}
    created_at = getattr(task_row, "created_at", None)
    last_heartbeat = getattr(task_row, "last_heartbeat_at", None)

    if status == "orchestrating":
        started = parse_iso_ts(output_data.get("started_at"))
        return ["reap"] if is_stuck(started, created_at, current) else []

    if status == "awaiting_qa":
        enq = parse_iso_ts(output_data.get("qa_enqueued_at"))
        return ["reap"] if is_stuck(enq, None, current) else []

    if status == "running":
        return (
            ["reap"]
            if is_stuck(last_heartbeat, created_at, current)
            else []
        )

    return []  # pragma: no cover — defensive
