"""backend/src/ai_driven/claude/langchain_response.py

AIMessage → MessageResponse. raw_content carries the verbatim Anthropic
content-block dicts so client.py:164's assistant-turn replay (and the
thinking-signature requirement) keeps working identically.
"""

from __future__ import annotations

from typing import Any

from langchain_core.messages import AIMessage

from ai_driven.claude.provider import (
    ContentBlock,
    MessageResponse,
    TextBlock,
    ThinkingBlock,
    TokenUsage,
    ToolUseBlock,
)


def parse_ai_message(msg: AIMessage, *, requested_model: str) -> MessageResponse:
    raw: list[dict[str, Any]]
    if isinstance(msg.content, str):
        raw = [{"type": "text", "text": msg.content}] if msg.content else []
    else:
        raw = [b for b in msg.content if isinstance(b, dict)]

    content: list[ContentBlock] = []
    for block in raw:
        btype = block.get("type")
        if btype == "text":
            content.append(TextBlock(text=block.get("text", "")))
        elif btype == "tool_use":
            content.append(ToolUseBlock(id=block["id"], name=block["name"],
                                        input=block.get("input", {})))
        elif btype == "thinking":
            content.append(ThinkingBlock(thinking=block.get("thinking", "")))
        # unknown block types stay in raw_content but are not surfaced

    usage_md = msg.usage_metadata or {}
    details = usage_md.get("input_token_details") or {}
    usage = TokenUsage(
        input_tokens=usage_md.get("input_tokens", 0),
        output_tokens=usage_md.get("output_tokens", 0),
        cache_creation_input_tokens=details.get("cache_creation", 0) or 0,
        cache_read_input_tokens=details.get("cache_read", 0) or 0,
    )

    meta = msg.response_metadata or {}
    return MessageResponse(
        content=content,
        stop_reason=meta.get("stop_reason", "end_turn"),
        usage=usage,
        model=meta.get("model") or meta.get("model_name") or requested_model,
        raw_content=raw,
    )
