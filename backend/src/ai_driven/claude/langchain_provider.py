"""backend/src/ai_driven/claude/langchain_provider.py

LangChain-backed Claude provider — the sole implementation behind the
ClaudeProvider interface. Handles both Anthropic direct and Bedrock
backends via ChatAnthropic / ChatBedrockConverse, selected by
settings.claude_provider (ClaudeProviderType).
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from typing import Any

from langchain_core.messages import SystemMessage

from ai_driven.claude.langchain_messages import to_langchain_messages
from ai_driven.claude.langchain_response import parse_ai_message
from ai_driven.claude.provider import (
    ClaudeProvider,
    MessageResponse,
    _with_cache_control_messages,
    _with_cache_control_system,
    _with_cache_control_tools,
)
from ai_driven.config import ClaudeProviderType, get_settings
from ai_driven.observability.langsmith_tracing import traced_llm
from ai_driven.observability.metrics import emit_claude_metrics

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Bedrock helpers (tool-name encoding, message sanitisation, model ID mapping)
# ---------------------------------------------------------------------------

_BEDROCK_NAME_SEP = "__"


def _encode_bedrock_tool_name(name: str) -> str:
    return name.replace(".", _BEDROCK_NAME_SEP)


def _decode_bedrock_tool_name(name: str) -> str:
    return name.replace(_BEDROCK_NAME_SEP, ".")


def _sanitize_tools_for_bedrock(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out = []
    for t in tools:
        new = dict(t)
        if "name" in new:
            new["name"] = _encode_bedrock_tool_name(new["name"])
        out.append(new)
    return out


def _sanitize_messages_for_bedrock(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Re-encode dotted tool_use / tool_result block names in message history."""
    out = []
    for m in messages:
        new_m = dict(m)
        content = new_m.get("content")
        if isinstance(content, list):
            new_content = []
            for block in content:
                if (isinstance(block, dict) and block.get("type") == "tool_use"
                        and "name" in block):
                    nb = dict(block)
                    nb["name"] = _encode_bedrock_tool_name(block["name"])
                    new_content.append(nb)
                else:
                    new_content.append(block)
            new_m["content"] = new_content
        out.append(new_m)
    return out


def _resolve_bedrock_model_id(model: str) -> str:
    """Map short Claude model names to Bedrock cross-region inference profile IDs."""
    if "." in model:
        return model
    model_map = {
        "claude-opus-4-7": "global.anthropic.claude-opus-4-7",
        "claude-opus-4-6": "global.anthropic.claude-opus-4-6-v1",
        "claude-sonnet-4-6": "global.anthropic.claude-sonnet-4-6",
        "claude-haiku-4-5": "global.anthropic.claude-haiku-4-5-20251001-v1:0",
    }
    return model_map.get(model, model)


# ---------------------------------------------------------------------------
# LangChainProvider
# ---------------------------------------------------------------------------


class LangChainProvider(ClaudeProvider):
    """ChatAnthropic / ChatBedrockConverse behind the ClaudeProvider interface."""

    def __init__(self, provider_type: ClaudeProviderType) -> None:
        self._provider_type = provider_type

    def _build_chat_model(self, *, model: str, max_tokens: int,
                          temperature: float,
                          thinking: dict[str, Any] | None,
                          stop_sequences: list[str] | None) -> Any:
        if self._provider_type == ClaudeProviderType.ANTHROPIC:
            from langchain_anthropic import ChatAnthropic
            settings = get_settings()
            kwargs: dict[str, Any] = {
                "model": model,
                "max_tokens": max_tokens,
                "temperature": temperature,
                "api_key": settings.anthropic_api_key,
            }
            if thinking:
                kwargs["thinking"] = thinking
            if stop_sequences:
                kwargs["stop_sequences"] = stop_sequences
            return ChatAnthropic(**kwargs)

        from botocore.config import Config
        from langchain_aws import ChatBedrockConverse
        settings = get_settings()
        kwargs = {
            "model": _resolve_bedrock_model_id(model),
            "max_tokens": max_tokens,
            "temperature": temperature,
            "region_name": settings.aws_region,
            "config": Config(read_timeout=300, connect_timeout=10,
                             retries={"max_attempts": 2, "mode": "standard"}),
        }
        if settings.aws_access_key_id and settings.aws_secret_access_key:
            kwargs["aws_access_key_id"] = settings.aws_access_key_id
            kwargs["aws_secret_access_key"] = settings.aws_secret_access_key
            if settings.aws_session_token:
                kwargs["aws_session_token"] = settings.aws_session_token
        if thinking:
            kwargs["additional_model_request_fields"] = {"thinking": thinking}
        if stop_sequences:
            kwargs["stop_sequences"] = stop_sequences
        return ChatBedrockConverse(**kwargs)

    @traced_llm(name="langchain.create_message")
    async def create_message(
        self,
        *,
        model: str,
        max_tokens: int,
        messages: list[dict[str, Any]],
        system: str | None = None,
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 1.0,
        thinking: dict[str, Any] | None = None,
        stop_sequences: list[str] | None = None,
    ) -> MessageResponse:
        chat = self._build_chat_model(
            model=model, max_tokens=max_tokens, temperature=temperature,
            thinking=thinking, stop_sequences=stop_sequences,
        )
        if tools:
            if self._provider_type == ClaudeProviderType.BEDROCK:
                tools = _sanitize_tools_for_bedrock(tools)
            chat = chat.bind_tools(_with_cache_control_tools(tools))

        raw_messages = messages
        if self._provider_type == ClaudeProviderType.BEDROCK:
            raw_messages = _sanitize_messages_for_bedrock(messages)

        lc_messages = to_langchain_messages(_with_cache_control_messages(raw_messages))
        if system:
            sys_content = _with_cache_control_system(system)
            lc_messages.insert(0, SystemMessage(content=sys_content))  # type: ignore[arg-type]

        reply = await chat.ainvoke(lc_messages)
        result = parse_ai_message(reply, requested_model=model)

        if self._provider_type == ClaudeProviderType.BEDROCK:
            for block in result.tool_use_blocks:
                block.name = _decode_bedrock_tool_name(block.name)
            for raw in result.raw_content:
                if isinstance(raw, dict) and raw.get("type") == "tool_use" and "name" in raw:
                    raw["name"] = _decode_bedrock_tool_name(raw["name"])

        emit_claude_metrics(result.usage, model, f"langchain-{self._provider_type.value}")
        return result

    async def create_message_stream(  # type: ignore[misc,override]
        self,
        *,
        model: str,
        max_tokens: int,
        messages: list[dict[str, Any]],
        system: str | None = None,
        tools: list[dict[str, Any]] | None = None,
        temperature: float = 1.0,
        thinking: dict[str, Any] | None = None,
        stop_sequences: list[str] | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """Emit text-delta chunks plus _final_message sentinel for client.py."""
        chat = self._build_chat_model(
            model=model, max_tokens=max_tokens, temperature=temperature,
            thinking=thinking, stop_sequences=stop_sequences,
        )
        if tools:
            if self._provider_type == ClaudeProviderType.BEDROCK:
                tools = _sanitize_tools_for_bedrock(tools)
            chat = chat.bind_tools(_with_cache_control_tools(tools))

        raw_messages = messages
        if self._provider_type == ClaudeProviderType.BEDROCK:
            raw_messages = _sanitize_messages_for_bedrock(messages)

        lc_messages = to_langchain_messages(_with_cache_control_messages(raw_messages))
        if system:
            lc_messages.insert(0, SystemMessage(
                content=_with_cache_control_system(system)))  # type: ignore[arg-type]

        final_chunk = None
        async for chunk in chat.astream(lc_messages):
            final_chunk = chunk if final_chunk is None else final_chunk + chunk
            text = _chunk_text(chunk)
            if text:
                yield {"type": "content_block_delta",
                       "delta": {"type": "text_delta", "text": text}}

        if final_chunk is not None:
            from langchain_core.messages import AIMessage
            final_msg = AIMessage(
                content=final_chunk.content,
                response_metadata=getattr(final_chunk, "response_metadata", {}) or {},
                usage_metadata=getattr(final_chunk, "usage_metadata", None),
            )
            result = parse_ai_message(final_msg, requested_model=model)
            if self._provider_type == ClaudeProviderType.BEDROCK:
                for block in result.tool_use_blocks:
                    block.name = _decode_bedrock_tool_name(block.name)
            emit_claude_metrics(result.usage, model, f"langchain-{self._provider_type.value}")
            yield {"type": "_final_message", "data": result}


def _chunk_text(chunk: Any) -> str:
    """Extract text delta from an AIMessageChunk."""
    content = getattr(chunk, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(
            b.get("text", "") for b in content
            if isinstance(b, dict) and b.get("type") == "text"
        )
    return ""
