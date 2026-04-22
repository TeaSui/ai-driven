"""Unified Claude client with tool use loop and conversation management."""

from __future__ import annotations

import json
import logging
import time
from typing import Any, AsyncIterator, Awaitable, Callable

from ai_driven.claude.provider import (
    ClaudeProvider,
    ContentBlock,
    MessageResponse,
    ToolUseBlock,
    create_provider,
)
from ai_driven.claude.tools.registry import ToolRegistry
from ai_driven.config import get_settings
from ai_driven.observability.trace_context import new_span

logger = logging.getLogger(__name__)

# Type alias for the streaming text chunk callback.
# Called with (delta_text: str, iteration: int).
OnTextChunk = Callable[[str, int], Awaitable[None] | None]


class ClaudeClient:
    """High-level Claude client that handles multi-turn tool use loops."""

    def __init__(
        self,
        provider: ClaudeProvider | None = None,
        tool_registry: ToolRegistry | None = None,
    ) -> None:
        self._provider = provider or create_provider()
        self._tool_registry = tool_registry if tool_registry is not None else ToolRegistry()

    @property
    def tool_registry(self) -> ToolRegistry:
        return self._tool_registry

    async def chat(
        self,
        messages: list[dict[str, Any]],
        *,
        system: str | None = None,
        model: str | None = None,
        max_tokens: int | None = None,
        temperature: float = 1.0,
        tools: list[str] | None = None,
        thinking: dict[str, Any] | None = None,
        stop_sequences: list[str] | None = None,
    ) -> MessageResponse:
        """Single-turn message — no tool loop."""
        settings = get_settings()
        tool_schemas = self._tool_registry.get_schemas(tools) if tools else None

        return await self._provider.create_message(
            model=model or settings.claude_default_model,
            max_tokens=max_tokens or settings.claude_max_tokens,
            messages=messages,
            system=system,
            tools=tool_schemas,
            temperature=temperature,
            thinking=thinking,
            stop_sequences=stop_sequences,
        )

    async def run_agent_loop(
        self,
        messages: list[dict[str, Any]],
        *,
        system: str | None = None,
        model: str | None = None,
        max_tokens: int | None = None,
        temperature: float = 1.0,
        tools: list[str] | None = None,
        thinking: dict[str, Any] | None = None,
        max_iterations: int | None = None,
        on_tool_call: Callable[[str, dict], None] | None = None,
        on_response: Callable[[MessageResponse], None] | None = None,
        on_tool_result: Callable[[str, float, bool], Any] | None = None,
        on_text_chunk: OnTextChunk | None = None,
        stop_sequences: list[str] | None = None,
        should_continue: Callable[[], Awaitable[bool]] | None = None,
    ) -> MessageResponse:
        """Multi-turn tool use loop.

        Sends messages to Claude, executes tool calls, and loops until Claude
        stops requesting tools or max_iterations is reached.

        When *on_text_chunk* is provided, each iteration uses streaming
        internally so callers receive incremental text deltas in real time.
        The final ``MessageResponse`` is still returned as a complete object.
        """
        settings = get_settings()
        max_iter = max_iterations or settings.claude_max_tool_iterations
        tool_schemas = self._tool_registry.get_schemas(tools) if tools else None
        model = model or settings.claude_default_model
        max_tokens = max_tokens or settings.claude_max_tokens

        for iteration in range(max_iter):
            iter_num = iteration + 1
            logger.info("agent_loop_iteration", extra={"iteration": iter_num, "max_iterations": max_iter, "model": model})

            # Check if the caller wants us to stop (e.g. pause/stop control)
            if should_continue is not None and not await should_continue():
                logger.info("Agent loop halted by should_continue callback at iteration %d", iter_num)
                if iteration > 0:
                    return response  # type: ignore[possibly-undefined]
                from ai_driven.claude.provider import TokenUsage
                return MessageResponse(
                    content=[], stop_reason="stopped",
                    usage=TokenUsage(), model=model, raw_content=[],
                )

            if on_text_chunk is not None:
                response = await self._stream_iteration(
                    messages,
                    system=system,
                    model=model,
                    max_tokens=max_tokens,
                    tools=tool_schemas,
                    temperature=temperature,
                    thinking=thinking,
                    stop_sequences=stop_sequences,
                    on_text_chunk=on_text_chunk,
                    iteration=iter_num,
                )
            else:
                response = await self._provider.create_message(
                    model=model,
                    max_tokens=max_tokens,
                    messages=messages,
                    system=system,
                    tools=tool_schemas,
                    temperature=temperature,
                    thinking=thinking,
                    stop_sequences=stop_sequences,
                )

            if on_response:
                on_response(response)

            if not response.has_tool_use:
                logger.info("agent_loop_complete", extra={"iterations": iter_num, "stop_reason": response.stop_reason})
                return response

            # Append assistant message with full content (preserves tool_use blocks)
            messages.append({"role": "assistant", "content": response.raw_content})

            # Execute all tool calls and collect results
            tool_results = await self._execute_tool_calls(
                response.tool_use_blocks, on_tool_call, on_tool_result
            )

            # Append tool results as user message
            messages.append({"role": "user", "content": tool_results})

        logger.warning("agent_loop_max_iterations", extra={"max_iterations": max_iter})
        return response

    async def _stream_iteration(
        self,
        messages: list[dict[str, Any]],
        *,
        system: str | None,
        model: str,
        max_tokens: int,
        tools: list[dict[str, Any]] | None,
        temperature: float,
        thinking: dict[str, Any] | None,
        stop_sequences: list[str] | None,
        on_text_chunk: OnTextChunk,
        iteration: int,
    ) -> MessageResponse:
        """Run a single iteration using streaming, firing *on_text_chunk* for each text delta.

        Falls back to non-streaming if the provider's stream raises.
        The provider yields a ``_final_message`` event at the end containing
        the complete ``MessageResponse``, so no second API call is needed.
        """
        try:
            final_response: MessageResponse | None = None

            async for chunk in self._provider.create_message_stream(
                model=model,
                max_tokens=max_tokens,
                messages=messages,
                system=system,
                tools=tools,
                temperature=temperature,
            ):
                chunk_type = chunk.get("type", "")

                if chunk_type == "_final_message":
                    final_response = chunk["data"]
                    continue

                # Anthropic SDK: events have a "data" attr that is the raw event object
                data = chunk.get("data")

                # Anthropic provider: data is an SDK event object
                if data is not None and hasattr(data, "type"):
                    event_name = getattr(data, "type", "")
                    if event_name == "content_block_delta":
                        delta = getattr(data, "delta", None)
                        if delta and getattr(delta, "type", None) == "text_delta":
                            text = getattr(delta, "text", "")
                            if text:
                                result = on_text_chunk(text, iteration)
                                if result is not None:
                                    await result

                # Bedrock provider: chunks are plain dicts
                elif isinstance(chunk, dict) and chunk_type == "content_block_delta":
                    delta = chunk.get("delta", {})
                    if delta.get("type") == "text_delta":
                        text = delta.get("text", "")
                        if text:
                            result = on_text_chunk(text, iteration)
                            if result is not None:
                                await result

                # MockProvider: message_stop with MessageResponse data
                elif chunk_type == "message_stop" and isinstance(data, MessageResponse):
                    final_response = data

            if final_response is not None:
                return final_response

            # Should not reach here, but fall back to non-streaming
            logger.warning("Stream did not yield _final_message, falling back")
            return await self._provider.create_message(
                model=model, max_tokens=max_tokens, messages=messages,
                system=system, tools=tools, temperature=temperature,
                thinking=thinking, stop_sequences=stop_sequences,
            )
        except Exception as e:
            logger.warning("Streaming iteration failed (%s), falling back to non-streaming", e)
            return await self._provider.create_message(
                model=model, max_tokens=max_tokens, messages=messages,
                system=system, tools=tools, temperature=temperature,
                thinking=thinking, stop_sequences=stop_sequences,
            )

    async def run_agent_loop_streaming(
        self,
        messages: list[dict[str, Any]],
        *,
        system: str | None = None,
        model: str | None = None,
        max_tokens: int | None = None,
        temperature: float = 1.0,
        tools: list[str] | None = None,
        max_iterations: int | None = None,
    ) -> AsyncIterator[dict[str, Any]]:
        """Streaming agent loop — yields events for real-time UI updates."""
        settings = get_settings()
        max_iter = max_iterations or settings.claude_max_tool_iterations
        tool_schemas = self._tool_registry.get_schemas(tools) if tools else None
        model = model or settings.claude_default_model
        max_tokens = max_tokens or settings.claude_max_tokens

        for iteration in range(max_iter):
            yield {"type": "iteration_start", "iteration": iteration + 1}

            response = await self._provider.create_message(
                model=model,
                max_tokens=max_tokens,
                messages=messages,
                system=system,
                tools=tool_schemas,
                temperature=temperature,
            )

            yield {"type": "message", "response": response}

            if not response.has_tool_use:
                yield {"type": "complete", "iterations": iteration + 1}
                return

            messages.append({"role": "assistant", "content": response.raw_content})

            for tool_block in response.tool_use_blocks:
                yield {"type": "tool_call", "tool": tool_block.name, "input": tool_block.input}
                start = time.monotonic()
                result = await self._tool_registry.execute(tool_block.name, tool_block.input)
                duration = time.monotonic() - start
                yield {
                    "type": "tool_result",
                    "tool": tool_block.name,
                    "duration_ms": int(duration * 1000),
                    "success": not result.get("is_error", False),
                }

            tool_results = await self._execute_tool_calls(response.tool_use_blocks)
            messages.append({"role": "user", "content": tool_results})

    async def _execute_tool_calls(
        self,
        tool_blocks: list[ToolUseBlock],
        on_tool_call: Callable[[str, dict], None] | None = None,
        on_tool_result: Callable[[str, float, bool], Any] | None = None,
    ) -> list[dict[str, Any]]:
        """Execute tool calls and return tool_result blocks."""
        results: list[dict[str, Any]] = []

        for block in tool_blocks:
            if on_tool_call:
                on_tool_call(block.name, block.input)

            start = time.monotonic()

            with new_span(f"tool:{block.name}") as span_id:
                try:
                    logger.info("tool_exec_start", extra={"tool": block.name, "tool_use_id": block.id})
                    result = await self._tool_registry.execute(block.name, block.input)
                    duration = time.monotonic() - start
                    logger.info("tool_exec_end", extra={"tool": block.name, "duration_ms": int(duration * 1000), "success": True})

                    result_str = json.dumps(result) if isinstance(result, dict) else str(result)
                    if on_tool_result:
                        coro = on_tool_result(block.name, duration, True, result_str)
                        if coro is not None:
                            await coro

                    results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": result_str,
                    })
                except Exception as e:
                    duration = time.monotonic() - start
                    logger.error("tool_exec_failed", extra={"tool": block.name, "duration_ms": int(duration * 1000), "error": str(e)})

                    error_str = f"Error executing {block.name}: {str(e)}"
                    if on_tool_result:
                        coro = on_tool_result(block.name, duration, False, error_str)
                        if coro is not None:
                            await coro

                    results.append({
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": error_str,
                        "is_error": True,
                    })

        return results
