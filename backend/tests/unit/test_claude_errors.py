"""Tests for Claude-specific exceptions."""
from __future__ import annotations

import pytest


def test_claude_refusal_error_preserves_context() -> None:
    """ClaudeRefusalError carries the refused response + message count for debugging."""
    from ai_driven.claude.errors import ClaudeRefusalError

    err = ClaudeRefusalError(
        message="Streaming safety classifier blocked output",
        model="claude-sonnet-4-6",
        refused_iteration=3,
        conversation_length=7,
    )

    assert "safety classifier" in str(err)
    assert err.model == "claude-sonnet-4-6"
    assert err.refused_iteration == 3
    assert err.conversation_length == 7


def test_claude_refusal_error_is_runtime_error_subclass() -> None:
    """ClaudeRefusalError is a RuntimeError so naive `except Exception:` catches it."""
    from ai_driven.claude.errors import ClaudeRefusalError

    assert issubclass(ClaudeRefusalError, RuntimeError)


def test_claude_refusal_error_str_is_human_readable() -> None:
    """The str() form mentions the iteration so log readers can locate the failing turn."""
    from ai_driven.claude.errors import ClaudeRefusalError

    err = ClaudeRefusalError(
        message="blocked",
        model="claude-opus-4-7",
        refused_iteration=5,
        conversation_length=11,
    )

    rendered = str(err)
    assert "iteration 5" in rendered
    assert "claude-opus-4-7" in rendered
