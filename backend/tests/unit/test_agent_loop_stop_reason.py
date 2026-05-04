"""Agent-loop behavior per stop_reason.

Skill reference: ~/.claude/skills/building-with-the-claude-api/references/basics.md
— 'Handling stop_reason' table. These tests mirror that table row-by-row.
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from ai_driven.claude.client import ClaudeClient
from ai_driven.claude.errors import ClaudeRefusalError
from ai_driven.claude.provider import MessageResponse, TextBlock, TokenUsage


def _response(*, stop_reason: str, text: str = "done") -> MessageResponse:
    return MessageResponse(
        content=[TextBlock(text=text)],
        stop_reason=stop_reason,
        usage=TokenUsage(input_tokens=10, output_tokens=5),
        model="claude-sonnet-4-6",
        raw_content=[],
    )


def _make_loop(provider_response: MessageResponse) -> ClaudeClient:
    """Minimal loop wired to a mock provider that returns a single fixed response."""
    provider = AsyncMock()
    provider.create_message = AsyncMock(return_value=provider_response)
    # ToolRegistry.get_schemas is sync; use MagicMock so it's not awaited.
    tool_registry = MagicMock()
    tool_registry.get_schemas = MagicMock(return_value=None)
    return ClaudeClient(provider=provider, tool_registry=tool_registry)


@pytest.mark.asyncio
async def test_end_turn_returns_response_normally() -> None:
    """end_turn is the happy path — response comes back unchanged."""
    loop = _make_loop(_response(stop_reason="end_turn", text="hello"))
    response = await loop.run_agent_loop(
        [{"role": "user", "content": "hi"}],
        model="claude-sonnet-4-6",
    )
    assert response.stop_reason == "end_turn"
    assert response.text == "hello"


@pytest.mark.asyncio
async def test_refusal_raises_claude_refusal_error() -> None:
    """refusal is a typed exception — callers MUST handle it, not retry blindly."""
    loop = _make_loop(_response(stop_reason="refusal"))
    with pytest.raises(ClaudeRefusalError) as exc:
        await loop.run_agent_loop(
            [{"role": "user", "content": "banned topic"}],
            model="claude-sonnet-4-6",
        )
    assert exc.value.refused_iteration >= 1
    assert exc.value.model == "claude-sonnet-4-6"


@pytest.mark.asyncio
async def test_max_tokens_preserves_stop_reason_on_returned_response() -> None:
    """max_tokens returns the (possibly-truncated) response; caller branches on stop_reason.

    We don't auto-retry here — that's a caller policy decision. The loop just makes the
    signal visible by leaving stop_reason='max_tokens' on the returned MessageResponse
    (and NOT overwriting it to 'max_iterations' or similar).
    """
    loop = _make_loop(_response(stop_reason="max_tokens", text="partial..."))
    response = await loop.run_agent_loop(
        [{"role": "user", "content": "long"}],
        model="claude-sonnet-4-6",
    )
    assert response.stop_reason == "max_tokens"
    assert response.text == "partial..."


@pytest.mark.asyncio
async def test_model_context_window_exceeded_passes_through() -> None:
    """Context-window exceeded is returned as-is so callers can chunk input / summarize."""
    loop = _make_loop(_response(stop_reason="model_context_window_exceeded"))
    response = await loop.run_agent_loop(
        [{"role": "user", "content": "huge"}],
        model="claude-sonnet-4-6",
    )
    assert response.stop_reason == "model_context_window_exceeded"
