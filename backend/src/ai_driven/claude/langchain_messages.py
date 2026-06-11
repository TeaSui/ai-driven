"""backend/src/ai_driven/claude/langchain_messages.py

Anthropic-format message dicts → langchain_core messages.

Strategy: PASS CONTENT BLOCKS THROUGH VERBATIM. langchain-anthropic
forwards dict content blocks (text / tool_use / tool_result / thinking)
to the API unchanged, which preserves thinking-block signatures — the
load-bearing requirement from docs/design/langgraph-migration.md §5
Phase 1. We deliberately do NOT convert tool_result turns into
ToolMessage objects: a verbatim HumanMessage carrying tool_result
blocks is the representation closest to the wire format we replay today
via raw_content (client.py:164).
"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage


def to_langchain_messages(messages: list[dict[str, Any]]) -> list[BaseMessage]:
    out: list[BaseMessage] = []
    for m in messages:
        role = m.get("role")
        content = m.get("content")
        if role == "assistant":
            out.append(AIMessage(content=content))
        elif role == "user":
            out.append(HumanMessage(content=content))
        else:
            raise ValueError(f"unsupported role for Anthropic conversation: {role!r}")
    return out
