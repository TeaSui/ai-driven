"""Refactored-helper tests — T25 commit 1 pulled ``_is_stuck`` and
``_parse_iso_ts`` from override.py into ``_overrides_helpers``. These
tests pin the public behavior so future cleanups can't silently shift
it.

T24's test_admin_override_endpoint.py already covers the end-to-end
override path that uses these helpers indirectly; this module
exercises them in isolation.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from ai_driven.api.admin._overrides_helpers import (
    STUCK_THRESHOLD_MINUTES,
    is_stuck,
    parse_iso_ts,
)


def test_parse_iso_ts_accepts_iso_with_offset():
    result = parse_iso_ts("2026-05-01T12:00:00+00:00")
    assert result == datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)


def test_parse_iso_ts_accepts_z_suffix():
    result = parse_iso_ts("2026-05-01T12:00:00Z")
    assert result == datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)


def test_parse_iso_ts_rejects_empty_and_bogus_and_wrong_type():
    assert parse_iso_ts(None) is None
    assert parse_iso_ts("") is None
    assert parse_iso_ts("not-a-date") is None
    assert parse_iso_ts(12345) is None
    assert parse_iso_ts({"x": 1}) is None


def test_is_stuck_uses_primary_ts_when_set():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    # 45 min old primary → stuck
    assert is_stuck(now - timedelta(minutes=45), None, now) is True
    # 5 min old primary → not stuck
    assert is_stuck(now - timedelta(minutes=5), None, now) is False


def test_is_stuck_falls_back_when_primary_is_none():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    assert is_stuck(None, now - timedelta(minutes=45), now) is True
    assert is_stuck(None, now - timedelta(minutes=5), now) is False


def test_is_stuck_returns_false_when_both_none():
    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    assert is_stuck(None, None, now) is False


def test_is_stuck_threshold_matches_published_constant():
    # The threshold is load-bearing — dashboards, reapers, and the
    # override endpoint all depend on it. Lock the value down here so
    # any future change is deliberate + reviewed.
    assert STUCK_THRESHOLD_MINUTES == 30

    now = datetime(2026, 5, 1, 12, 0, 0, tzinfo=timezone.utc)
    # Exactly at the threshold — NOT stuck (strict <, not <=).
    boundary = now - timedelta(minutes=STUCK_THRESHOLD_MINUTES)
    assert is_stuck(boundary, None, now) is False
    # One second past → stuck.
    assert is_stuck(
        boundary - timedelta(seconds=1), None, now,
    ) is True
