"""Claude provider abstraction."""

from __future__ import annotations

import logging
import time
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

from ai_driven.config import ClaudeProviderType, get_settings

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

    async def create_message_stream(self, **kwargs: Any) -> AsyncIterator[dict[str, Any]]:  # type: ignore[misc,override]
        async for event in self._primary.create_message_stream(**kwargs):  # type: ignore[attr-defined]
            yield event



def create_provider(
    provider_type: ClaudeProviderType | None = None,
    resilient: bool = True,
) -> ClaudeProvider:
    """Factory: always returns a LangChainProvider.

    Always creates a real provider — if credentials are missing, the first
    API call will raise. We do not synthesize mock responses; a visible
    failure is always preferable to a silent one.
    """
    from ai_driven.claude.langchain_provider import LangChainProvider
    settings = get_settings()
    if provider_type is None:
        provider_type = settings.claude_provider
    provider: ClaudeProvider = LangChainProvider(provider_type)
    if resilient:
        return ResilientProvider(provider)
    return provider
