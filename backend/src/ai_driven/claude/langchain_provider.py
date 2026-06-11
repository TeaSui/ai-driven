"""backend/src/ai_driven/claude/langchain_provider.py

LangChain-backed implementation of the ClaudeProvider interface —
Phase 1 of docs/design/langgraph-migration.md. Selected by
settings.claude_provider_impl == "langchain"; the legacy providers in
provider.py remain the default. Behavior contract: byte-equivalent
requests and equivalent MessageResponse objects vs. the legacy
implementation, verified by the Phase 1 shadow-parity gate.
"""

from __future__ import annotations

import logging
from typing import Any, AsyncIterator

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

        # Bedrock
        from botocore.config import Config
        from langchain_aws import ChatBedrockConverse

        from ai_driven.claude.provider import BedrockProvider
        settings = get_settings()
        kwargs = {
            "model": BedrockProvider._resolve_model_id(model),
            "max_tokens": max_tokens,
            "temperature": temperature,
            "region_name": settings.aws_region,
            # Same timeout posture as the legacy provider:
            # 300s read for long agentic iterations, fast connect failure.
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
                from ai_driven.claude.provider import BedrockProvider
                tools = BedrockProvider._sanitize_tools_for_bedrock(tools)
            chat = chat.bind_tools(_with_cache_control_tools(tools))

        raw_messages = messages
        if self._provider_type == ClaudeProviderType.BEDROCK:
            from ai_driven.claude.provider import BedrockProvider
            raw_messages = BedrockProvider._sanitize_messages_for_bedrock(messages)

        lc_messages = to_langchain_messages(
            _with_cache_control_messages(raw_messages)
        )
        if system:
            sys_content = _with_cache_control_system(system)
            lc_messages.insert(0, SystemMessage(content=sys_content))

        reply = await chat.ainvoke(lc_messages)
        result = parse_ai_message(reply, requested_model=model)

        if self._provider_type == ClaudeProviderType.BEDROCK:
            from ai_driven.claude.provider import BedrockProvider as _BP
            for block in result.tool_use_blocks:
                block.name = _BP._decode_tool_name(block.name)
            for raw in result.raw_content:
                if isinstance(raw, dict) and raw.get("type") == "tool_use" and "name" in raw:
                    raw["name"] = _BP._decode_tool_name(raw["name"])

        emit_claude_metrics(result.usage, model,
                            f"langchain-{self._provider_type.value}")
        return result

    async def create_message_stream(
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
        """Streaming: emit Bedrock-style dict chunks for text deltas plus the
        _final_message sentinel — the exact contract client.py's stream
        iteration consumes."""
        chat = self._build_chat_model(
            model=model, max_tokens=max_tokens, temperature=temperature,
            thinking=thinking, stop_sequences=stop_sequences,
        )
        if tools:
            if self._provider_type == ClaudeProviderType.BEDROCK:
                from ai_driven.claude.provider import BedrockProvider
                tools = BedrockProvider._sanitize_tools_for_bedrock(tools)
            chat = chat.bind_tools(_with_cache_control_tools(tools))

        raw_messages = messages
        if self._provider_type == ClaudeProviderType.BEDROCK:
            from ai_driven.claude.provider import BedrockProvider
            raw_messages = BedrockProvider._sanitize_messages_for_bedrock(messages)

        lc_messages = to_langchain_messages(
            _with_cache_control_messages(raw_messages)
        )
        if system:
            lc_messages.insert(0, SystemMessage(
                content=_with_cache_control_system(system)))

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
                from ai_driven.claude.provider import BedrockProvider as _BP
                for block in result.tool_use_blocks:
                    block.name = _BP._decode_tool_name(block.name)
            emit_claude_metrics(result.usage, model,
                                f"langchain-{self._provider_type.value}")
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
