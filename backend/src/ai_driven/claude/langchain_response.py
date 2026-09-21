"""backend/src/ai_driven/claude/langchain_response.py

AIMessage → MessageResponse. raw_content carries the verbatim Anthropic
content-block dicts so client.py:164's assistant-turn replay (and the
thinking-signature requirement) keeps working identically.
"""

from __future__ import annotations

import json
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


def _parse_tool_input(raw_input: str) -> dict[str, Any]:
    try:
        parsed = json.loads(raw_input) if raw_input else {}
    except (json.JSONDecodeError, TypeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def parse_ai_message(msg: AIMessage, *, requested_model: str) -> MessageResponse:
    raw: list[dict[str, Any]]
    if isinstance(msg.content, str):
        raw = [{"type": "text", "text": msg.content}] if msg.content else []
    else:
        raw = []
        for b in msg.content:
            if not isinstance(b, dict):
                continue
            if b.get("type") == "tool_use" and isinstance(b.get("input"), str):
                # ChatBedrockConverse's streamed AIMessageChunk accumulation
                # (via `+`) leaves content[].input as the raw accumulated
                # JSON string — tool_calls[].args gets re-parsed into a
                # dict, but content[].input does not. Anthropic direct and
                # Bedrock's non-streaming ainvoke() already hand back a
                # dict here, so this only fires on the Bedrock streaming
                # path.
                b = {**b, "input": _parse_tool_input(b["input"])}
            raw.append(b)

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

    usage_md: dict[str, Any] = dict(msg.usage_metadata or {})
    details = usage_md.get("input_token_details") or {}
    usage = TokenUsage(
        input_tokens=usage_md.get("input_tokens", 0),
        output_tokens=usage_md.get("output_tokens", 0),
        cache_creation_input_tokens=details.get("cache_creation", 0) or 0,
        cache_read_input_tokens=details.get("cache_read", 0) or 0,
    )

    meta = msg.response_metadata or {}
    # ChatAnthropic reports "stop_reason" (snake_case); ChatBedrockConverse
    # reports the same information under "stopReason" (AWS Bedrock Converse
    # API's native camelCase key). Without this fallback, every Bedrock
    # response silently defaulted to "end_turn" here regardless of the
    # actual stop reason — MessageResponse.has_tool_use (`stop_reason ==
    # "tool_use"`) was always False for Bedrock, so the agent tool loop
    # always treated a genuine tool_use response as "done" and returned
    # without ever executing the tool.
    stop_reason = meta.get("stop_reason") or meta.get("stopReason") or "end_turn"
    return MessageResponse(
        content=content,
        stop_reason=stop_reason,
        usage=usage,
        model=meta.get("model") or meta.get("model_name") or requested_model,
        raw_content=raw,
    )
