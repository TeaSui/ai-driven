"""Lesson 61: ClaudeClient forces a synthesis turn when max_iterations is hit.

Without this, agents that exhaust their iteration budget return raw `tool_use`
blocks with no text — callers persist empty outputs and the operator has no
clue what happened. The forced no-tools turn guarantees a final string.
"""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock

import pytest

from ai_driven.claude.client import ClaudeClient
from ai_driven.claude.provider import (
    ClaudeProvider,
    MessageResponse,
    TextBlock,
    TokenUsage,
    ToolUseBlock,
)
from ai_driven.claude.tools.registry import ToolRegistry


def _tool_use_response(tool_id: str = "tu_1") -> MessageResponse:
    block = ToolUseBlock(id=tool_id, name="echo", input={"text": "hi"})
    return MessageResponse(
        content=[block],
        stop_reason="tool_use",
        usage=TokenUsage(input_tokens=10, output_tokens=5),
        model="test",
        raw_content=[{"type": "tool_use", "id": tool_id, "name": "echo", "input": {"text": "hi"}}],
    )


def _text_response(text: str) -> MessageResponse:
    block = TextBlock(text=text)
    return MessageResponse(
        content=[block],
        stop_reason="end_turn",
        usage=TokenUsage(input_tokens=20, output_tokens=8),
        model="test",
        raw_content=[{"type": "text", "text": text}],
    )


class _StubProvider(ClaudeProvider):
    """Returns a queued sequence of responses; records the kwargs of each call."""

    def __init__(self, responses: list[MessageResponse]) -> None:
        self._responses = responses
        self.calls: list[dict[str, Any]] = []

    async def create_message(self, **kwargs: Any) -> MessageResponse:
        self.calls.append(kwargs)
        if not self._responses:
            raise AssertionError("no more queued responses")
        return self._responses.pop(0)

    async def create_message_stream(self, **kwargs: Any):  # pragma: no cover
        yield {"type": "_final_message", "data": await self.create_message(**kwargs)}


def _registry_with_echo() -> ToolRegistry:
    reg = ToolRegistry()

    @reg.tool(
        name="echo",
        description="echo back the text",
        input_schema={"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
    )
    async def echo(text: str) -> dict[str, str]:
        return {"echoed": text}

    return reg


async def test_synthesis_turn_runs_when_budget_exhausted() -> None:
    """Loop exhausts max_iter while still requesting tools → one extra
    no-tools call MUST happen and its text must replace the response."""
    provider = _StubProvider(
        responses=[
            _tool_use_response("tu_1"),         # iteration 1 — wants tools
            _tool_use_response("tu_2"),         # iteration 2 — still wants tools
            _text_response("final synthesis"),  # forced synthesis turn
        ]
    )
    client = ClaudeClient(provider=provider, tool_registry=_registry_with_echo())

    result = await client.run_agent_loop(
        messages=[{"role": "user", "content": "do the thing"}],
        tools=["echo"],
        max_iterations=2,
    )

    assert result.stop_reason == "max_iterations"
    assert result.text == "final synthesis"
    # 2 loop iterations + 1 synthesis = 3 calls.
    assert len(provider.calls) == 3
    # The third call MUST disable tools — that's how we force text out.
    assert provider.calls[-1].get("tools") is None


async def test_no_synthesis_when_agent_finishes_naturally() -> None:
    """Iteration cap not hit → no extra call, original behaviour preserved."""
    provider = _StubProvider(
        responses=[_text_response("done")],
    )
    client = ClaudeClient(provider=provider, tool_registry=ToolRegistry())

    result = await client.run_agent_loop(
        messages=[{"role": "user", "content": "hi"}],
        tools=None,
        max_iterations=5,
    )

    assert result.stop_reason == "end_turn"
    assert result.text == "done"
    assert len(provider.calls) == 1


async def test_synthesis_failure_falls_back_to_tool_use_response() -> None:
    """If the synthesis call itself errors, we still return the prior response
    tagged max_iterations rather than raising."""

    class _PartiallyFailing(ClaudeProvider):
        def __init__(self) -> None:
            self.calls = 0

        async def create_message(self, **kwargs: Any) -> MessageResponse:
            self.calls += 1
            if self.calls == 1:
                return _tool_use_response("tu_only")
            raise RuntimeError("network blip")

        async def create_message_stream(self, **kwargs: Any):  # pragma: no cover
            yield {}

    provider = _PartiallyFailing()
    client = ClaudeClient(provider=provider, tool_registry=_registry_with_echo())

    result = await client.run_agent_loop(
        messages=[{"role": "user", "content": "x"}],
        tools=["echo"],
        max_iterations=1,
    )

    # Must NOT raise — caller still gets a response, tagged for audit.
    assert result.stop_reason == "max_iterations"
    assert provider.calls == 2  # the failed synthesis attempt counts
