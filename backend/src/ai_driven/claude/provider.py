"""Claude provider abstraction — Anthropic direct and AWS Bedrock."""

from __future__ import annotations

import json
import logging
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, AsyncIterator

import anthropic
import boto3

from ai_driven.config import ClaudeProviderType, get_settings
from ai_driven.observability.langsmith_tracing import traced_llm
from ai_driven.observability.metrics import emit_claude_metrics

logger = logging.getLogger(__name__)


@dataclass
class TokenUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    cache_creation_input_tokens: int = 0
    cache_read_input_tokens: int = 0

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens


@dataclass
class ToolUseBlock:
    id: str
    name: str
    input: dict[str, Any]
    type: str = "tool_use"


@dataclass
class TextBlock:
    text: str
    type: str = "text"


@dataclass
class ThinkingBlock:
    thinking: str
    type: str = "thinking"


ContentBlock = ToolUseBlock | TextBlock | ThinkingBlock


@dataclass
class MessageResponse:
    content: list[ContentBlock]
    stop_reason: str
    usage: TokenUsage
    model: str
    raw_content: list[Any] = field(default_factory=list)

    @property
    def text(self) -> str:
        texts = [b.text for b in self.content if isinstance(b, TextBlock)]
        return "\n".join(texts)

    @property
    def tool_use_blocks(self) -> list[ToolUseBlock]:
        return [b for b in self.content if isinstance(b, ToolUseBlock)]

    @property
    def has_tool_use(self) -> bool:
        return self.stop_reason == "tool_use"


class ClaudeProvider(ABC):
    """Abstract base for Claude API providers."""

    @abstractmethod
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
        ...

    @abstractmethod
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
        ...


# Minimum token count for cache_control to be effective (Anthropic requirement)
_CACHE_MIN_TOKENS = 1024
# Approximate chars-per-token ratio for estimating token count
_CHARS_PER_TOKEN = 4


def _with_cache_control_system(system: str) -> list[dict[str, Any]] | str:
    """Add cache_control breakpoint to system prompt if large enough."""
    if len(system) < _CACHE_MIN_TOKENS * _CHARS_PER_TOKEN:
        return system
    return [
        {
            "type": "text",
            "text": system,
            "cache_control": {"type": "ephemeral"},
        }
    ]


def _with_cache_control_tools(tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Add cache_control breakpoint to the last tool schema if the set is large enough."""
    import json as _json

    total_chars = sum(len(_json.dumps(t)) for t in tools)
    if total_chars < _CACHE_MIN_TOKENS * _CHARS_PER_TOKEN or not tools:
        return tools
    # Add cache_control to the last tool (covers the entire tool list)
    tools = [dict(t) for t in tools]  # shallow copy
    tools[-1] = {**tools[-1], "cache_control": {"type": "ephemeral"}}
    return tools


def _with_cache_control_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Pin a cache_control breakpoint on the FIRST user message.

    Why the first message, not the last:
    In an agent tool loop, the conversation grows by one tool_result turn
    per iteration. Marking the *last* user message (the newest tool_result)
    as the cache breakpoint means every iteration has a different tail, so
    every request is a cache miss that pays the 1.25× cache-write rate —
    the opposite of what we want.

    Pinning on the *first* user message (the initial task prompt, which
    never changes after turn 1) gives a stable prefix. From iteration 2
    onward every request hits the 0.1× cache-read rate on the initial
    prompt + system + tools, which cuts input-side cost by roughly 10×
    on long coding runs.

    See KAN-12 cost post-mortem (2026-04-30) for the incident that
    motivated this change.
    """
    if not messages:
        return messages

    # Find the FIRST user message.
    first_user_idx = next(
        (i for i, m in enumerate(messages) if m.get("role") == "user"),
        None,
    )
    if first_user_idx is None:
        return messages

    msg = messages[first_user_idx]
    content = msg.get("content")

    if isinstance(content, str):
        if len(content) < _CACHE_MIN_TOKENS * _CHARS_PER_TOKEN:
            return messages
        messages = list(messages)  # shallow copy
        messages[first_user_idx] = {
            **msg,
            "content": [
                {"type": "text", "text": content, "cache_control": {"type": "ephemeral"}},
            ],
        }
    elif isinstance(content, list) and content:
        total_chars = sum(len(str(b)) for b in content)
        if total_chars < _CACHE_MIN_TOKENS * _CHARS_PER_TOKEN:
            return messages
        messages = list(messages)
        new_content = list(content)
        last_block = dict(new_content[-1])
        last_block["cache_control"] = {"type": "ephemeral"}
        new_content[-1] = last_block
        messages[first_user_idx] = {**msg, "content": new_content}

    return messages


class AnthropicProvider(ClaudeProvider):
    """Direct Anthropic API provider."""

    def __init__(self) -> None:
        settings = get_settings()
        self._client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)

    @traced_llm(name="anthropic.create_message")
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
        params: dict[str, Any] = {
            "model": model,
            "max_tokens": max_tokens,
            "messages": _with_cache_control_messages(messages),
            "temperature": temperature,
        }
        if system:
            params["system"] = _with_cache_control_system(system)
        if tools:
            params["tools"] = _with_cache_control_tools(tools)
        if thinking:
            params["thinking"] = thinking
        if stop_sequences:
            params["stop_sequences"] = stop_sequences

        response = await self._client.messages.create(**params)
        result = self._parse_response(response)

        # Log cache savings
        if result.usage.cache_read_input_tokens > 0:
            logger.info(
                "Prompt cache hit: %d tokens read from cache (saved ~%d input tokens)",
                result.usage.cache_read_input_tokens,
                result.usage.cache_read_input_tokens,
            )

        emit_claude_metrics(result.usage, model, "anthropic")
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
        params: dict[str, Any] = {
            "model": model,
            "max_tokens": max_tokens,
            "messages": messages,
            "temperature": temperature,
        }
        if system:
            params["system"] = system
        if tools:
            params["tools"] = tools
        if thinking:
            params["thinking"] = thinking
        if stop_sequences:
            params["stop_sequences"] = stop_sequences

        async with self._client.messages.stream(**params) as stream:
            async for event in stream:
                yield {"type": event.type, "data": event}
            # Yield the final parsed message so callers can avoid a second API call
            final = stream.get_final_message()
            yield {"type": "_final_message", "data": self._parse_response(final)}

    @staticmethod
    def _parse_response(response: Any) -> MessageResponse:
        content: list[ContentBlock] = []
        for block in response.content:
            if block.type == "text":
                content.append(TextBlock(text=block.text))
            elif block.type == "tool_use":
                content.append(
                    ToolUseBlock(id=block.id, name=block.name, input=block.input)
                )
            elif block.type == "thinking":
                content.append(ThinkingBlock(thinking=block.thinking))

        usage = TokenUsage(
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
            cache_creation_input_tokens=getattr(response.usage, "cache_creation_input_tokens", 0) or 0,
            cache_read_input_tokens=getattr(response.usage, "cache_read_input_tokens", 0) or 0,
        )

        return MessageResponse(
            content=content,
            stop_reason=response.stop_reason,
            usage=usage,
            model=response.model,
            raw_content=response.content,
        )


class BedrockProvider(ClaudeProvider):
    """AWS Bedrock provider using the Invoke API.

    Uses ``invoke_model`` / ``invoke_model_with_response_stream`` which
    require IAM actions ``bedrock:InvokeModel`` and
    ``bedrock:InvokeModelWithResponseStream``.

    The payload uses the Anthropic Messages API format (``anthropic_version``).
    """

    # Bedrock tool names must match ^[a-zA-Z0-9_-]{1,64}$ — no dots allowed.
    # Our tool registry uses dotted names (jira.get_issue, github.create_pr);
    # translate transparently so the same schemas work for both providers.
    _BEDROCK_NAME_SEP = "__"  # unambiguous reverse mapping

    @classmethod
    def _encode_tool_name(cls, name: str) -> str:
        return name.replace(".", cls._BEDROCK_NAME_SEP)

    @classmethod
    def _decode_tool_name(cls, name: str) -> str:
        return name.replace(cls._BEDROCK_NAME_SEP, ".")

    @classmethod
    def _sanitize_tools_for_bedrock(cls, tools: list[dict[str, Any]]) -> list[dict[str, Any]]:
        out = []
        for t in tools:
            new = dict(t)
            if "name" in new:
                new["name"] = cls._encode_tool_name(new["name"])
            out.append(new)
        return out

    @classmethod
    def _sanitize_messages_for_bedrock(cls, messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Re-encode any tool_use / tool_result block names in message history."""
        out = []
        for m in messages:
            new_m = dict(m)
            content = new_m.get("content")
            if isinstance(content, list):
                new_content = []
                for block in content:
                    if isinstance(block, dict) and block.get("type") == "tool_use" and "name" in block:
                        nb = dict(block); nb["name"] = cls._encode_tool_name(block["name"]); new_content.append(nb)
                    else:
                        new_content.append(block)
                new_m["content"] = new_content
            out.append(new_m)
        return out

    def __init__(self) -> None:
        from botocore.config import Config

        settings = get_settings()
        # Prefer explicit settings creds when present so .env.local wins over
        # ~/.aws/credentials's [default] profile (which on some dev machines
        # has a LocalStack sentinel 'test' key that Bedrock will reject).
        boto_kwargs: dict[str, Any] = {"region_name": settings.aws_region}
        if settings.aws_access_key_id and settings.aws_secret_access_key:
            boto_kwargs["aws_access_key_id"] = settings.aws_access_key_id
            boto_kwargs["aws_secret_access_key"] = settings.aws_secret_access_key
            if settings.aws_session_token:
                boto_kwargs["aws_session_token"] = settings.aws_session_token

        # Bedrock's default read_timeout is 60s, which is tight for agentic
        # loops on Opus / Sonnet with large system prompts — a single
        # iteration can take 2–5 minutes of real model time. We bump the
        # read_timeout to 300s and keep a short connect_timeout so network
        # reachability problems still fail fast. Two retries cover transient
        # 5xx from Bedrock without silently masking a real outage.
        boto_kwargs["config"] = Config(
            read_timeout=300,
            connect_timeout=10,
            retries={"max_attempts": 2, "mode": "standard"},
        )
        self._client = boto3.client("bedrock-runtime", **boto_kwargs)

    @traced_llm(name="bedrock.create_message")
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
        model_id = self._resolve_model_id(model)

        body: dict[str, Any] = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": max_tokens,
            "messages": _with_cache_control_messages(self._sanitize_messages_for_bedrock(messages)),
            "temperature": temperature,
        }
        if system:
            body["system"] = _with_cache_control_system(system)
        if tools:
            body["tools"] = _with_cache_control_tools(self._sanitize_tools_for_bedrock(tools))
        if thinking:
            body["thinking"] = thinking
        if stop_sequences:
            body["stop_sequences"] = stop_sequences

        import asyncio
        loop = asyncio.get_event_loop()
        response = await loop.run_in_executor(
            None,
            lambda: self._client.invoke_model(
                modelId=model_id,
                contentType="application/json",
                accept="application/json",
                body=json.dumps(body),
            ),
        )

        response_body = json.loads(response["body"].read())
        # Decode tool names in response back to dotted form
        for block in response_body.get("content", []):
            if isinstance(block, dict) and block.get("type") == "tool_use" and "name" in block:
                block["name"] = self._decode_tool_name(block["name"])
        result = self._parse_invoke_response(response_body, model)

        # Log cache savings if present
        if result.usage.cache_read_input_tokens > 0:
            logger.info(
                "Bedrock cache hit: %d tokens read from cache",
                result.usage.cache_read_input_tokens,
            )

        emit_claude_metrics(result.usage, model, "bedrock")
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
        model_id = self._resolve_model_id(model)

        body: dict[str, Any] = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": max_tokens,
            "messages": self._sanitize_messages_for_bedrock(messages),
            "temperature": temperature,
        }
        if system:
            body["system"] = system
        if tools:
            body["tools"] = self._sanitize_tools_for_bedrock(tools)
        if thinking:
            body["thinking"] = thinking
        if stop_sequences:
            body["stop_sequences"] = stop_sequences

        import asyncio
        loop = asyncio.get_event_loop()
        response = await loop.run_in_executor(
            None,
            lambda: self._client.invoke_model_with_response_stream(
                modelId=model_id,
                contentType="application/json",
                accept="application/json",
                body=json.dumps(body),
            ),
        )

        accumulated_content: list[dict[str, Any]] = []
        final_usage: dict[str, Any] = {}
        final_stop_reason = "end_turn"
        # Track tool_use blocks by their content_block index so we can merge
        # input_json_delta streams into the correct block.
        tool_use_by_index: dict[int, dict[str, Any]] = {}
        partial_json_by_index: dict[int, str] = {}

        for event in response["body"]:
            chunk = json.loads(event["chunk"]["bytes"])
            idx = chunk.get("index")
            ctype = chunk.get("type")

            # Decode tool names in streaming tool_use starts
            if ctype == "content_block_start":
                block = chunk.get("content_block", {})
                if block.get("type") == "tool_use" and "name" in block:
                    block["name"] = self._decode_tool_name(block["name"])
                    tool_use_by_index[idx] = dict(block)
                    partial_json_by_index[idx] = ""
                    accumulated_content.append(tool_use_by_index[idx])
            yield chunk

            if ctype == "content_block_delta":
                delta = chunk.get("delta", {})
                dtype = delta.get("type")
                if dtype == "text_delta":
                    accumulated_content.append({"type": "text", "text": delta.get("text", "")})
                elif dtype == "input_json_delta" and idx in tool_use_by_index:
                    # Accumulate partial JSON; finalize at content_block_stop.
                    partial_json_by_index[idx] += delta.get("partial_json", "")
            elif ctype == "content_block_stop":
                if idx in tool_use_by_index:
                    raw = partial_json_by_index.get(idx, "")
                    try:
                        tool_use_by_index[idx]["input"] = json.loads(raw) if raw else {}
                    except Exception:
                        logger.warning(
                            "Bedrock streaming: failed to parse tool input JSON for block %d: %s",
                            idx, raw[:200] if raw else "(empty)",
                        )
                        tool_use_by_index[idx]["input"] = {}
            elif ctype == "message_delta":
                final_stop_reason = chunk.get("delta", {}).get("stop_reason", final_stop_reason)
                final_usage = chunk.get("usage", final_usage)

        # Build a synthetic final message for callers
        combined_text = "".join(
            b["text"] for b in accumulated_content if b.get("type") == "text"
        )
        tool_blocks = [b for b in accumulated_content if b.get("type") == "tool_use"]
        content_blocks = []
        if combined_text:
            content_blocks.append({"type": "text", "text": combined_text})
        content_blocks.extend(tool_blocks)

        final_body = {
            "content": content_blocks,
            "stop_reason": final_stop_reason,
            "usage": final_usage,
        }
        yield {"type": "_final_message", "data": self._parse_invoke_response(final_body, model)}

    @staticmethod
    def _resolve_model_id(model: str) -> str:
        """Map short model names to Bedrock model IDs.

        Uses cross-region inference profile IDs (`global.` prefix) by default
        so workflows run in any region that has Bedrock Anthropic access
        (ap-southeast-1, eu-central-1, etc). Plain regional IDs only support
        on-demand invocation in us-east-1 / us-west-2.

        Callers can override by passing a full Bedrock model ID directly —
        anything containing a dot is assumed already-resolved.
        """
        # Already a Bedrock ID (e.g. set via AI_DRIVEN_BEDROCK_MODEL_ID)
        if "." in model:
            return model
        model_map = {
            "claude-opus-4-7": "global.anthropic.claude-opus-4-7",
            "claude-opus-4-6": "global.anthropic.claude-opus-4-6-v1",
            "claude-sonnet-4-6": "global.anthropic.claude-sonnet-4-6",
            "claude-haiku-4-5": "global.anthropic.claude-haiku-4-5-20251001-v1:0",
        }
        return model_map.get(model, model)

    @staticmethod
    def _parse_invoke_response(body: dict[str, Any], model: str) -> MessageResponse:
        """Parse Invoke API response (Anthropic Messages format)."""
        content: list[ContentBlock] = []
        for block in body.get("content", []):
            if block["type"] == "text":
                content.append(TextBlock(text=block["text"]))
            elif block["type"] == "tool_use":
                content.append(
                    ToolUseBlock(id=block["id"], name=block["name"], input=block["input"])
                )
            elif block["type"] == "thinking":
                content.append(ThinkingBlock(thinking=block["thinking"]))

        usage_data = body.get("usage", {})
        usage = TokenUsage(
            input_tokens=usage_data.get("input_tokens", 0),
            output_tokens=usage_data.get("output_tokens", 0),
            cache_creation_input_tokens=usage_data.get("cache_creation_input_tokens", 0),
            cache_read_input_tokens=usage_data.get("cache_read_input_tokens", 0),
        )

        return MessageResponse(
            content=content,
            stop_reason=body.get("stop_reason", "end_turn"),
            usage=usage,
            model=model,
            raw_content=body.get("content", []),
        )


class CircuitBreaker:
    """Simple circuit breaker for Claude API calls."""

    def __init__(
        self,
        failure_threshold: int = 5,
        recovery_timeout: float = 60.0,
    ) -> None:
        self._failure_count = 0
        self._failure_threshold = failure_threshold
        self._recovery_timeout = recovery_timeout
        self._last_failure_time: float = 0.0
        self._state = "closed"  # closed, open, half_open

    @property
    def is_open(self) -> bool:
        if self._state == "open":
            if time.monotonic() - self._last_failure_time > self._recovery_timeout:
                self._state = "half_open"
                return False
            return True
        return False

    def record_success(self) -> None:
        self._failure_count = 0
        self._state = "closed"

    def record_failure(self) -> None:
        self._failure_count += 1
        self._last_failure_time = time.monotonic()
        if self._failure_count >= self._failure_threshold:
            self._state = "open"
            logger.warning(
                "Circuit breaker opened after %d failures", self._failure_count
            )


class ResilientProvider(ClaudeProvider):
    """Wraps a provider with circuit breaker and model fallback."""

    def __init__(self, primary: ClaudeProvider) -> None:
        self._primary = primary
        self._circuit = CircuitBreaker()

    async def create_message(self, **kwargs: Any) -> MessageResponse:
        if self._circuit.is_open:
            logger.warning("Circuit breaker open — attempting with fallback model")
            return await self._with_fallback(**kwargs)

        try:
            result = await self._primary.create_message(**kwargs)
            self._circuit.record_success()
            return result
        except Exception as e:
            self._circuit.record_failure()
            logger.error("Claude API error: %s — trying fallback", e)
            return await self._with_fallback(**kwargs)

    async def _with_fallback(self, **kwargs: Any) -> MessageResponse:
        """Fall back to a cheaper model tier if the primary model fails.

        If every tier fails, the exception propagates — no MockProvider
        safety net. Workflows that can't reach Claude MUST fail loudly so
        false-positive success states don't get persisted.
        """
        settings = get_settings()
        original_model = kwargs.get("model", "")
        if original_model == settings.claude_planning_model:
            kwargs["model"] = settings.claude_default_model
            logger.info("Falling back from %s to %s", original_model, kwargs["model"])
        elif original_model == settings.claude_default_model:
            kwargs["model"] = settings.claude_fast_model
            logger.info("Falling back from %s to %s", original_model, kwargs["model"])

        return await self._primary.create_message(**kwargs)

    async def create_message_stream(self, **kwargs: Any) -> AsyncIterator[dict[str, Any]]:
        async for event in self._primary.create_message_stream(**kwargs):
            yield event



def create_provider(
    provider_type: ClaudeProviderType | None = None,
    resilient: bool = True,
) -> ClaudeProvider:
    """Factory function to create the configured Claude provider.

    Always returns a real provider (Anthropic-direct or Bedrock). If the
    underlying credentials are missing or invalid, the FIRST real API call
    will raise — that is intentional. We do not synthesize mock responses
    in any environment. A visible failure is always preferable to a silent
    one, especially in debug runs where a false-positive pipeline can leak
    fictional data into Jira / GitHub.
    """
    settings = get_settings()
    if provider_type is None:
        provider_type = settings.claude_provider

    if settings.claude_provider_impl == "langchain":
        from ai_driven.claude.langchain_provider import LangChainProvider
        provider: ClaudeProvider = LangChainProvider(provider_type)
    else:
        providers = {
            ClaudeProviderType.ANTHROPIC: AnthropicProvider,
            ClaudeProviderType.BEDROCK: BedrockProvider,
        }
        provider = providers[provider_type]()

    if resilient:
        return ResilientProvider(provider)
    return provider
