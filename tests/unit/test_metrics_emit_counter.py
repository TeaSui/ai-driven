"""Tests for the generic emit_counter helper added in T22.

Covers:
  * emit_counter increments the statsd client by 1 with the supplied tags.
  * emit_counter swallows exceptions raised by the statsd client — an
    observability failure must NEVER crash the caller.
  * emit_counter is a no-op when DataDog is disabled (via _NoOpStatsd).
"""
from __future__ import annotations

from unittest.mock import MagicMock, patch

from ai_driven.observability import metrics as metrics_mod


def test_emit_counter_increments_with_tags() -> None:
    fake_statsd = MagicMock()
    with patch.object(metrics_mod, "_get_statsd", return_value=fake_statsd):
        metrics_mod.emit_counter("qa.post_coding.verdict.pass",
                                  tags=["role:qa", "mode:live"])
    fake_statsd.increment.assert_called_once_with(
        "qa.post_coding.verdict.pass", 1, tags=["role:qa", "mode:live"],
    )


def test_emit_counter_default_tags_is_empty_list() -> None:
    fake_statsd = MagicMock()
    with patch.object(metrics_mod, "_get_statsd", return_value=fake_statsd):
        metrics_mod.emit_counter("qa.post_coding.enqueue_failed")
    fake_statsd.increment.assert_called_once_with(
        "qa.post_coding.enqueue_failed", 1, tags=[],
    )


def test_emit_counter_swallows_exceptions() -> None:
    """Observability failure must never crash the caller."""
    fake_statsd = MagicMock()
    fake_statsd.increment.side_effect = RuntimeError("statsd down")
    with patch.object(metrics_mod, "_get_statsd", return_value=fake_statsd):
        # No raise — must return None silently.
        result = metrics_mod.emit_counter("qa.post_coding.schema_drift")
    assert result is None


def test_emit_counter_noop_statsd_does_not_raise() -> None:
    """Integration with _NoOpStatsd (DataDog disabled path)."""
    # Reset the cached statsd so _get_statsd picks up the current settings.
    metrics_mod._statsd = None
    # Default settings have datadog_enabled=False → _NoOpStatsd.
    metrics_mod.emit_counter("qa.post_coding.classifier_tier_missing")
    assert isinstance(metrics_mod._statsd, metrics_mod._NoOpStatsd)
