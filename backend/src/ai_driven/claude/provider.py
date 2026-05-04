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
        self._client = boto3.client("bedrock-runtime", **boto_kwargs)

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
        """Fall back to a cheaper model if the primary fails.

        Final safety net (debug mode only): if every model tier fails, return
        a MockProvider response so the agent loop stays alive and the UI shows
        a visible pipeline. Without this, an Anthropic/Bedrock outage (or a
        customer hitting their quota) would halt every workflow mid-run.
        """
        settings = get_settings()
        original_model = kwargs.get("model", "")
        if original_model == settings.claude_planning_model:
            kwargs["model"] = settings.claude_default_model
            logger.info("Falling back from %s to %s", original_model, kwargs["model"])
        elif original_model == settings.claude_default_model:
            kwargs["model"] = settings.claude_fast_model
            logger.info("Falling back from %s to %s", original_model, kwargs["model"])

        try:
            return await self._primary.create_message(**kwargs)
        except Exception as e:
            if settings.debug:
                logger.warning(
                    "Every Claude model tier failed (%s: %s); falling back to "
                    "MockProvider so the workflow stays alive in debug mode",
                    type(e).__name__, str(e)[:120],
                )
                return await MockProvider().create_message(**kwargs)
            raise

    async def create_message_stream(self, **kwargs: Any) -> AsyncIterator[dict[str, Any]]:
        try:
            async for event in self._primary.create_message_stream(**kwargs):
                yield event
        except Exception as e:
            settings = get_settings()
            if settings.debug:
                logger.warning(
                    "Streaming failed (%s: %s); falling back to MockProvider stream",
                    type(e).__name__, str(e)[:120],
                )
                async for event in MockProvider().create_message_stream(**kwargs):
                    yield event
            else:
                raise


class MockProvider(ClaudeProvider):
    """Mock provider for local development — returns scripted tool_use sequences.

    Simulates realistic multi-iteration agent loops by returning ToolUseBlock
    responses for intermediate steps and a final TextBlock for the last step.
    The iteration is determined by counting tool_result messages in the conversation.
    """

    # Per-agent scripted tool call sequences.
    # Each entry is (tool_name, tool_input_dict).
    # After all tool steps are exhausted the provider returns a final TextBlock.
    # Per-agent scripted tool call sequences.
    # Input dicts MUST match the handler function signatures exactly.
    _TOOL_SCRIPTS: dict[str, list[tuple[str, dict[str, Any]]]] = {
        "coding": [
            ("jira.get_issue", {"issue_key": "GTX-4829"}),
            ("repo.list_files", {"repo_full_name": "org/ledger-core", "path": "src/", "ref": "main"}),
            ("repo.read_file", {"repo_full_name": "org/ledger-core", "path": "src/api/endpoints.py"}),
            ("code.search", {"repo_full_name": "org/ledger-core", "query": "BalanceCache"}),
            ("repo.read_file", {"repo_full_name": "org/ledger-core", "path": "src/services/cache.py"}),
            ("file.write", {"repo_full_name": "org/ledger-core", "path": "src/services/cache.py", "content": "# Fixed race condition with singleflight pattern\nimport asyncio\n\nclass BalanceCache:\n    def __init__(self):\n        self._cache = {}\n        self._locks = {}\n\n    async def get(self, account_id: str):\n        if account_id not in self._locks:\n            self._locks[account_id] = asyncio.Lock()\n        async with self._locks[account_id]:\n            if account_id in self._cache:\n                return self._cache[account_id]\n            balance = await self._load(account_id)\n            self._cache[account_id] = balance\n            return balance\n", "commit_message": "fix(ledger): guard BalanceCache.get() with async lock", "branch": "fix/GTX-4829-balance-cache-race"}),
            ("sandbox.execute", {"command": "python -m pytest tests/ -v --tb=short"}),
            ("git.create_branch", {"repo_full_name": "org/ledger-core", "branch_name": "fix/GTX-4829-balance-cache-race"}),
            ("github.create_pr", {"repo_full_name": "org/ledger-core", "title": "fix(ledger): guard BalanceCache.get() with async lock", "body": "Fixes race condition in balance cache.\n\n- Added per-account async locks\n- All 12 tests passing\n- Closes GTX-4829", "source_branch": "fix/GTX-4829-balance-cache-race"}),
            ("jira.update_status", {"issue_key": "GTX-4829", "transition_name": "In Review"}),
        ],
        "review": [
            ("github.get_pr_diff", {"repo_full_name": "org/repo", "pr_number": 42}),
            ("repo.read_file", {"repo_full_name": "org/repo", "path": "src/api/endpoints.py"}),
            ("repo.list_files", {"repo_full_name": "org/repo", "path": "src/services/"}),
            ("repo.read_file", {"repo_full_name": "org/repo", "path": "src/services/handler.py"}),
            ("code.search", {"repo_full_name": "org/repo", "query": "validate_input"}),
            ("github.post_pr_comment", {"repo_full_name": "org/repo", "pr_number": 42, "body": "**[Medium]** `src/handler.py:45` — Missing null check on `user_input.email`. Could raise AttributeError if the field is optional.", "file_path": "src/handler.py", "line": 45}),
            ("github.approve_pr", {"repo_full_name": "org/repo", "pr_number": 42, "body": "Approved with minor suggestions. Good implementation overall."}),
        ],
        "devops": [
            ("datadog.query_metrics", {"query": "avg:system.cpu.user{service:api-gateway}", "from_ts": 1700000000, "to_ts": 1700003600}),
            ("datadog.query_logs", {"query": "service:api-gateway status:error", "from_ts": "2024-01-15T10:00:00Z", "to_ts": "2024-01-15T10:30:00Z", "limit": 50}),
            ("deploy.get_recent", {"repo_full_name": "org/api-gateway", "limit": 5}),
            ("datadog.get_service_health", {"service_name": "api-gateway"}),
            ("jira.create_incident", {"project_key": "INC", "title": "High API latency after v2.3.1 deploy", "description": "Root cause: unoptimized query on events table (full scan, 2.1M rows). Correlated with deploy v2.3.1.", "priority": "High"}),
            ("slack.notify_oncall", {"message": "P2 Incident: High API latency on api-gateway. RCA: full table scan on events table after v2.3.1 deploy. Incident ticket: INC-789"}),
        ],
    }

    # Scripted tool results returned by the mock tool registry
    _TOOL_RESULTS: dict[str, dict[str, str]] = {
        "jira.get_issue": '{"key": "GTX-4829", "summary": "Fix race condition in ledger balance cache", "status": "To Do", "assignee": "ai-agent", "description": "Intermittent failures when two concurrent transfers read the same account balance. The cache layer does check-then-set without locking.", "acceptance_criteria": ["Race condition is fixed", "Unit tests cover concurrent access"]}',
        "repo.list_files": '{"files": ["src/api/endpoints.py", "src/api/__init__.py", "src/services/cache.py", "src/services/handler.py", "src/models/schema.py", "src/models/balance.py", "tests/test_cache.py", "tests/test_endpoints.py"]}',
        "repo.read_file": '{"content": "class BalanceCache:\\n    def __init__(self):\\n        self._cache = {}\\n\\n    async def get(self, account_id):\\n        if account_id in self._cache:\\n            return self._cache[account_id]\\n        balance = await self._load(account_id)\\n        self._cache[account_id] = balance\\n        return balance\\n", "lines": 42}',
        "code.search": '{"matches": [{"file": "src/services/cache.py", "line": 8, "text": "class BalanceCache:"}, {"file": "src/services/cache.py", "line": 15, "text": "    async def get(self, account_id):"}]}',
        "file.write": '{"success": true, "path": "src/services/cache.py", "bytes_written": 487}',
        "sandbox.execute": '{"exit_code": 0, "stdout": "===== 12 passed in 1.84s =====\\nAll tests passed.", "stderr": ""}',
        "git.create_branch": '{"branch": "fix/GTX-4829-balance-cache-race", "sha": "a3f21c8"}',
        "github.create_pr": '{"pr_number": 2419, "pr_url": "https://github.com/org/repo/pull/2419", "title": "fix(ledger): guard BalanceCache.get() with async lock", "state": "open"}',
        "jira.update_status": '{"key": "GTX-4829", "status": "In Review", "updated": true}',
        "jira.add_comment": '{"id": "comment-123", "body": "AI agent has created PR #2419 to fix this issue."}',
        "github.get_pr_diff": '{"files_changed": 3, "additions": 42, "deletions": 17, "diff": "diff --git a/src/services/cache.py...\\n+    async with self._locks[account_id]:\\n-        if account_id in self._cache:"}',
        "github.post_pr_comment": '{"id": "review-comment-456", "created": true}',
        "github.approve_pr": '{"approved": true, "pr_number": 42}',
        "github.request_changes": '{"requested": true, "pr_number": 42}',
        "github.merge_pr": '{"merged": true, "sha": "b4e92d1", "pr_number": 87}',
        "slack.notify_team": '{"sent": true, "channel": "#engineering"}',
        "datadog.query_metrics": '{"series": [{"metric": "system.cpu.user", "pointlist": [[1700000000, 78.5], [1700000060, 82.1], [1700000120, 91.3]], "avg": 83.9, "max": 95.2}]}',
        "datadog.query_logs": '{"logs": [{"timestamp": "2024-01-15T10:23:45Z", "message": "Timeout waiting for database connection", "service": "api-gateway"}, {"timestamp": "2024-01-15T10:24:01Z", "message": "Query execution exceeded 30s threshold", "service": "api-gateway"}], "total": 47}',
        "deploy.get_recent": '{"deployments": [{"version": "v2.3.1", "deployed_at": "2024-01-15T10:00:00Z", "status": "success", "deployer": "ci-bot"}, {"version": "v2.3.0", "deployed_at": "2024-01-14T16:30:00Z", "status": "success"}]}',
        "datadog.get_service_health": '{"service": "api-gateway", "status": "degraded", "p99_latency_ms": 2340, "error_rate": 0.034, "uptime": "99.2%"}',
        "jira.create_incident": '{"key": "INC-789", "url": "https://jira.example.com/browse/INC-789", "status": "Open"}',
        "slack.notify_oncall": '{"notified": true, "oncall_user": "jane.doe", "channel": "#incidents"}',
        "deploy.suggest_rollback": '{"recommended": true, "target_version": "v2.3.0", "reason": "Deploy v2.3.1 correlated with latency spike"}',
        "code.semantic_search": '{"results": [{"file": "src/services/cache.py", "chunk": "class BalanceCache...", "score": 0.92}]}',
    }

    # Final text responses per agent type (returned after all tool steps)
    _FINAL_RESPONSES: dict[str, str] = {
        "default": (
            "I've analyzed your request and completed all steps:\n\n"
            "1. **Reviewed requirements** — Identified key objectives from the input.\n"
            "2. **Implemented changes** — Applied modifications following existing code conventions.\n"
            "3. **Verified** — All tests passing.\n\n"
            "The task is complete."
        ),
        "coding": (
            "## Implementation Complete\n\n"
            "I've fixed the race condition in the balance cache (GTX-4829).\n\n"
            "**Root cause:** `BalanceCache.get()` performed check-then-set without locking. "
            "Under concurrent load, two callers could both miss the cache and write stale reads.\n\n"
            "**Fix:** Added per-account `asyncio.Lock` to serialize concurrent reads for the same account.\n\n"
            "**Changes:**\n"
            "- `src/services/cache.py` — Wrapped `get()` in per-account async lock (+18 −8 lines)\n"
            "- `tests/test_cache.py` — Added `test_concurrent_reads` race condition test\n\n"
            "**Results:**\n"
            "- All 12 tests passing\n"
            "- PR #2419 created: `fix/GTX-4829-balance-cache-race → main`\n"
            "- Jira GTX-4829 moved to \"In Review\"\n"
        ),
        "review": (
            "## Code Review Complete\n\n"
            "I've reviewed PR #42 and found the implementation to be solid overall.\n\n"
            "### Findings\n"
            "1. **[Medium]** `src/handler.py:45` — Missing null check on `user_input.email`. "
            "Could raise `AttributeError` if the field is optional.\n"
            "2. **[Low]** `src/models.py:12` — Consider `Annotated[str, Field(max_length=255)]` "
            "for consistency.\n\n"
            "### Strengths\n"
            "- Clean separation of concerns between API and service layers\n"
            "- Good test coverage (92%) with edge cases\n"
            "- Proper error handling at API boundaries\n\n"
            "**Verdict:** Approved with minor suggestions. No blocking issues.\n"
        ),
        "devops": (
            "## Incident Triage Complete\n\n"
            "**Alert:** High API latency (p99 > 2000ms) on `api-gateway`\n"
            "**Severity:** P2\n\n"
            "### Root Cause Analysis\n"
            "1. **Correlated with deployment** — Latency spike began 12 min after `v2.3.1` deploy\n"
            "2. **Database connection pool** — PostgreSQL connections at 95% capacity\n"
            "3. **Query analysis** — New endpoint `GET /api/reports` running unoptimized query "
            "(full table scan on `events` table, 2.1M rows)\n\n"
            "### Actions Taken\n"
            "- Created incident ticket INC-789\n"
            "- Notified on-call engineer (jane.doe)\n\n"
            "### Recommended Fix\n"
            "1. **Immediate:** Add index on `events.created_at`\n"
            "2. **Short-term:** Implement query pagination (limit 100)\n"
            "3. **Long-term:** Add read replica for reporting queries\n"
        ),
    }

    def _detect_agent_type(self, system: str | None) -> str:
        """Detect agent type from system prompt first line."""
        if not system:
            return "default"
        first_line = system.split("\n")[0].lower()
        if "coding" in first_line:
            return "coding"
        elif "review" in first_line:
            return "review"
        elif "devops" in first_line or "sre" in first_line:
            return "devops"
        return "default"

    def _count_tool_results(self, messages: list[dict[str, Any]]) -> int:
        """Count completed tool result rounds to determine current iteration."""
        count = 0
        for msg in messages:
            if msg.get("role") != "user":
                continue
            content = msg.get("content")
            if isinstance(content, list):
                for block in content:
                    if isinstance(block, dict) and block.get("type") == "tool_result":
                        count += 1
        return count

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
        import asyncio

        # Simulate API latency (shorter for tool steps)
        await asyncio.sleep(0.3)

        agent_type = self._detect_agent_type(system)
        script = self._TOOL_SCRIPTS.get(agent_type, [])
        tool_result_count = self._count_tool_results(messages)

        # Determine which step we're on based on completed tool results
        step_index = tool_result_count
        if step_index < len(script) and tools:
            # Return a tool_use response for the current scripted step
            tool_name, tool_input = script[step_index]
            tool_id = f"toolu_mock_{agent_type}_{step_index:03d}"

            # Add a thinking text before the tool call for realism
            thinking_texts = {
                0: f"Let me start by gathering information about this task.",
                1: f"Good, now let me look at the relevant files.",
                2: f"I can see the structure. Let me examine the key file.",
                3: f"I see the issue. Let me search for related code.",
                4: f"Found the relevant code. Let me implement the fix.",
                5: f"Changes applied. Let me verify everything works.",
                6: f"Tests are passing. Let me proceed to the next step.",
                7: f"Almost done. Let me finalize the changes.",
                8: f"Creating the deliverable now.",
                9: f"Wrapping up the remaining steps.",
            }
            think_text = thinking_texts.get(step_index, f"Proceeding with step {step_index + 1}.")

            content: list[ContentBlock] = [
                TextBlock(text=think_text),
                ToolUseBlock(id=tool_id, name=tool_name, input=tool_input),
            ]
            raw_content = [
                {"type": "text", "text": think_text},
                {"type": "tool_use", "id": tool_id, "name": tool_name, "input": tool_input},
            ]

            # Vary token counts per step for realism
            input_tokens = 1200 + step_index * 180
            output_tokens = 150 + step_index * 40

            return MessageResponse(
                content=content,
                stop_reason="tool_use",
                usage=TokenUsage(
                    input_tokens=input_tokens,
                    output_tokens=output_tokens,
                    cache_read_input_tokens=max(0, input_tokens - 400) if step_index > 0 else 0,
                    cache_creation_input_tokens=400 if step_index == 0 else 0,
                ),
                model=model,
                raw_content=raw_content,
            )

        # All tool steps complete — return final text response
        response_text = self._FINAL_RESPONSES.get(agent_type, self._FINAL_RESPONSES["default"])

        return MessageResponse(
            content=[TextBlock(text=response_text)],
            stop_reason="end_turn",
            usage=TokenUsage(
                input_tokens=1800 + len(script) * 200,
                output_tokens=520,
                cache_read_input_tokens=1400 if script else 0,
            ),
            model=model,
            raw_content=[{"type": "text", "text": response_text}],
        )

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
        import asyncio
        # Mock provider ignores thinking/stop_sequences — just accept them
        # so the abstract contract is honored and callers don't have to
        # branch on provider type.
        _ = (thinking, stop_sequences)

        response = await self.create_message(
            model=model, max_tokens=max_tokens, messages=messages,
            system=system, tools=tools, temperature=temperature,
        )
        # Simulate streaming text deltas for MockProvider
        for block in response.content:
            if hasattr(block, "text") and block.text:
                words = block.text.split(" ")
                for i, word in enumerate(words):
                    delta = word if i == 0 else f" {word}"
                    yield {
                        "type": "content_block_delta",
                        "delta": {"type": "text_delta", "text": delta},
                    }
                    await asyncio.sleep(0.01)  # Simulate token generation
        yield {"type": "_final_message", "data": response}


def create_provider(
    provider_type: ClaudeProviderType | None = None,
    resilient: bool = True,
) -> ClaudeProvider:
    """Factory function to create the configured Claude provider.

    In debug mode, falls back to MockProvider when no real API credentials
    are available so the full pipeline works locally without external calls.
    """
    settings = get_settings()
    if provider_type is None:
        provider_type = settings.claude_provider

    # In debug mode with no API key, use mock provider for local dev
    if settings.debug:
        if provider_type == ClaudeProviderType.ANTHROPIC and (
            not settings.anthropic_api_key
            or settings.anthropic_api_key.startswith("sk-placeholder")
        ):
            logger.info("Debug mode: no API key configured — using MockProvider")
            return MockProvider()
        if provider_type == ClaudeProviderType.BEDROCK:
            # Settings-level creds (from .env.local) win — they bypass both
            # the ~/.aws/credentials default profile and the default chain.
            if settings.aws_access_key_id and settings.aws_secret_access_key:
                logger.info("Debug mode: using Bedrock with settings creds")
            else:
                # No settings creds → probe the default boto chain. Fall back
                # to Mock if it returns nothing or the LocalStack 'test' key.
                try:
                    import botocore.session
                    session = botocore.session.get_session()
                    creds = session.get_credentials()
                    if not creds or not creds.access_key or creds.access_key == "test":
                        logger.info("Debug mode: no real AWS credentials — using MockProvider")
                        return MockProvider()
                except Exception:
                    logger.info("Debug mode: AWS credentials check failed — using MockProvider")
                    return MockProvider()

    providers = {
        ClaudeProviderType.ANTHROPIC: AnthropicProvider,
        ClaudeProviderType.BEDROCK: BedrockProvider,
    }
    provider = providers[provider_type]()
    if resilient:
        return ResilientProvider(provider)
    return provider
