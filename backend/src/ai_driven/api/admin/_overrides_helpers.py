"""Shared helpers for T24 override endpoint + T25 GET artifacts endpoint.

The T24 POST /override-block endpoint and the T25 GET /tasks/{id}/artifacts
endpoint both need to know whether a task is "stuck long enough" to be
eligible for a ``reap`` action. Before T25 these helpers lived inline in
``override.py``; extracting them here lets both endpoints share one
threshold constant + one datetime parser without T25 having to re-import
private names from the POST module.

This is a pure refactor — behavior is identical. T24's extensive test
suite (see tests/unit/test_admin_override_endpoint.py) must remain
green after this move.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Any

# ---------------------------------------------------------------------------
# Stuck threshold — shared with override.py + override_rules.py.
#
# The override endpoint refuses to 'reap' a task that hasn't been stuck
# long enough. Same 30-min threshold the background reapers use, kept
# as a single constant so dashboard / runbook / reaper / endpoint /
# UI hint never drift apart.
# ---------------------------------------------------------------------------
STUCK_THRESHOLD_MINUTES = 30


def _stuck_delta() -> timedelta:
    return timedelta(minutes=STUCK_THRESHOLD_MINUTES)


def parse_iso_ts(value: Any) -> datetime | None:
    """Parse a stringified ISO-8601 timestamp from output_data. Returns
    None for absent/invalid values so callers can treat "never set" and
    "malformed" the same way."""
    if not value or not isinstance(value, str):
        return None
    try:
        # datetime.fromisoformat accepts '2026-05-01T12:00:00+00:00' but
        # not the trailing 'Z' some producers emit. Normalize first.
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None


def is_stuck(
    ts: datetime | None, fallback: datetime | None, now: datetime
) -> bool:
    """True if either ``ts`` is older than the threshold, or (ts is None
    and ``fallback`` is older than the threshold). Mirrors the stuck
    definition in brief §reapers."""
    threshold = now - _stuck_delta()
    if ts is not None:
        return ts < threshold
    if fallback is not None:
        return fallback < threshold
    return False
