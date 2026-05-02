"""Unit tests for _with_cache_control_messages (H3 cache breakpoint fix).

The function is the cache-control gate for the conversation list. Post-KAN-12
it pins the breakpoint on the FIRST user message instead of the last so an
agent tool loop maintains a stable cache prefix across iterations. These
tests codify that behavior.
"""
from __future__ import annotations

from ai_driven.claude.provider import _with_cache_control_messages

_BIG_TEXT = "x" * 5000  # > 1024 tokens @ ~4 chars/token


def test_breakpoint_goes_on_first_user_message_not_last() -> None:
    """Stable-prefix invariant — the breakpoint lives on messages[0] (user)."""
    msgs = [
        {"role": "user", "content": _BIG_TEXT},
        {"role": "assistant", "content": "ok, working"},
        {"role": "user", "content": "tool_result: success"},
        {"role": "assistant", "content": "ok, working"},
        {"role": "user", "content": "tool_result: done"},
    ]
    out = _with_cache_control_messages(msgs)
    # First user message should now carry the breakpoint.
    first = out[0]
    assert isinstance(first["content"], list)
    assert first["content"][-1].get("cache_control") == {"type": "ephemeral"}
    # Subsequent user messages must NOT have a breakpoint (tail is not cached).
    for m in out[1:]:
        if m.get("role") != "user":
            continue
        content = m.get("content")
        if isinstance(content, list):
            for block in content:
                assert "cache_control" not in block, (
                    "tail user messages must stay un-cached so the cache "
                    "hit-rate stays > 0 across iterations"
                )


def test_no_breakpoint_when_first_message_too_short() -> None:
    """Below the 1024-token minimum the cache breakpoint is a no-op."""
    msgs = [
        {"role": "user", "content": "short"},
        {"role": "assistant", "content": "ok"},
        {"role": "user", "content": "also short"},
    ]
    out = _with_cache_control_messages(msgs)
    # No restructuring to list-of-blocks — content is still a plain string.
    assert out[0]["content"] == "short"


def test_empty_messages_returns_as_is() -> None:
    assert _with_cache_control_messages([]) == []


def test_no_user_messages_returns_as_is() -> None:
    """Defensive: assistant-only conversation (pathological) is passed through."""
    msgs = [{"role": "assistant", "content": _BIG_TEXT}]
    assert _with_cache_control_messages(msgs) == msgs


def test_list_content_first_message_cache_goes_on_last_block() -> None:
    """When the first user message is already a list-of-blocks, the breakpoint
    goes on the LAST block of that list (per Anthropic caching semantics —
    content up-to-and-including the marked block is cached)."""
    msgs = [
        {"role": "user", "content": [
            {"type": "text", "text": _BIG_TEXT},
            {"type": "text", "text": "extra context"},
        ]},
        {"role": "assistant", "content": "ok"},
        {"role": "user", "content": "tool_result"},
    ]
    out = _with_cache_control_messages(msgs)
    first_blocks = out[0]["content"]
    assert first_blocks[-1].get("cache_control") == {"type": "ephemeral"}
    assert "cache_control" not in first_blocks[0]


def test_stable_prefix_across_simulated_iterations() -> None:
    """Agent-loop simulation: adding more tool_result turns must not move
    the cache breakpoint — that's the whole point of the fix."""
    base = [
        {"role": "user", "content": _BIG_TEXT},
        {"role": "assistant", "content": "ok"},
    ]
    turn1 = base + [{"role": "user", "content": "tool_result 1"}]
    turn2 = turn1 + [{"role": "assistant", "content": "ok"},
                     {"role": "user", "content": "tool_result 2"}]
    turn3 = turn2 + [{"role": "assistant", "content": "ok"},
                     {"role": "user", "content": "tool_result 3"}]

    out1 = _with_cache_control_messages(turn1)
    out2 = _with_cache_control_messages(turn2)
    out3 = _with_cache_control_messages(turn3)

    # First user message carries the breakpoint in all three turns
    # and its content is byte-identical — required for cache hits.
    for out in (out1, out2, out3):
        assert out[0]["content"][-1].get("cache_control") == {"type": "ephemeral"}
        assert out[0]["content"][-1]["text"] == _BIG_TEXT
