"""Tests for prompt caching (Task 6.8)."""

from __future__ import annotations

import json

from ai_driven.claude.provider import (
    _with_cache_control_system,
    _with_cache_control_tools,
)


class TestSystemPromptCaching:
    def test_short_prompt_unchanged(self) -> None:
        short = "You are a helpful assistant."
        result = _with_cache_control_system(short)
        assert result == short

    def test_long_prompt_gets_cache_control(self) -> None:
        long_prompt = "x" * 5000  # Well above 1024 * 4 chars
        result = _with_cache_control_system(long_prompt)
        assert isinstance(result, list)
        assert len(result) == 1
        assert result[0]["type"] == "text"
        assert result[0]["text"] == long_prompt
        assert result[0]["cache_control"] == {"type": "ephemeral"}


class TestToolSchemaCaching:
    def test_few_tools_unchanged(self) -> None:
        tools = [{"name": "t1", "input_schema": {"type": "object"}}]
        result = _with_cache_control_tools(tools)
        assert result == tools

    def test_many_tools_get_cache_control(self) -> None:
        # Create tools whose JSON exceeds the threshold
        tools = [
            {"name": f"tool_{i}", "description": "A" * 500, "input_schema": {"type": "object"}}
            for i in range(20)
        ]
        result = _with_cache_control_tools(tools)
        # Last tool should have cache_control
        assert "cache_control" in result[-1]
        assert result[-1]["cache_control"] == {"type": "ephemeral"}
        # Other tools should NOT have it
        for t in result[:-1]:
            assert "cache_control" not in t

    def test_empty_tools_unchanged(self) -> None:
        result = _with_cache_control_tools([])
        assert result == []

    def test_does_not_mutate_original(self) -> None:
        tools = [
            {"name": f"tool_{i}", "description": "A" * 500, "input_schema": {"type": "object"}}
            for i in range(20)
        ]
        original_last = dict(tools[-1])
        _with_cache_control_tools(tools)
        assert "cache_control" not in tools[-1]  # Original unchanged
