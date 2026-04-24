"""Base agent class with common functionality for all AI agents."""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any

from ai_driven.claude.client import ClaudeClient
from ai_driven.claude.conversation import Conversation
from ai_driven.claude.provider import MessageResponse
from ai_driven.claude.tools.registry import ToolRegistry
from ai_driven.config import get_settings
from ai_driven.observability.metrics import emit_agent_metrics
from ai_driven.observability.trace_context import bind_task, new_span

logger = logging.getLogger(__name__)

# Streaming buffer settings
_STREAM_FLUSH_INTERVAL = 0.2  # seconds
_STREAM_FLUSH_SIZE = 500  # chars


class BaseAgent(ABC):
    """Base class for all AI agents."""

    agent_type: str = "base"

    def __init__(
        self,
        claude_client: ClaudeClient | None = None,
        tool_registry: ToolRegistry | None = None,
    ) -> None:
        self._registry = tool_registry or ToolRegistry()
        self._claude = claude_client or ClaudeClient(tool_registry=self._registry)
        self._task_id: str | None = None
        self._redis_client: Any | None = None
        self._register_tools()
        self._register_ask_human()

    def _register_ask_human(self) -> None:
        """Register the universal human-in-the-loop clarification tool.

        The agent invokes this when it's genuinely uncertain and a human
        decision would save iterations/cost. The tool:
          1. publishes a `human_input_required` event with the question,
          2. marks the task `awaiting_input` via the control key,
          3. long-polls Redis for `task:{id}:human_answer`,
          4. returns the answer so the agent loop continues.

        Humans respond via POST /api/chat/tasks/{id}/respond or via the UI's
        clarification dialog. Use sparingly — prefer acting on best-guess
        when a reasonable default exists.
        """
        @self._registry.tool(
            name="human.ask_clarification",
            description=(
                "Ask the human operator a question and wait for their answer. "
                "Use ONLY when the Jira ticket or context is genuinely ambiguous "
                "AND a wrong guess would waste significant effort (e.g. breaking API "
                "changes, schema choices, undocumented product decisions). Do NOT "
                "use for clarifying easy defaults — pick a sensible default and "
                "note the assumption in your Jira comment instead."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "question": {
                        "type": "string",
                        "description": "The specific question to ask the human, in plain English.",
                    },
                    "context": {
                        "type": "string",
                        "description": "One-sentence context on why you're stuck and what you've tried.",
                    },
                    "options": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Optional list of concrete choices (will render as buttons).",
                    },
                    "timeout_seconds": {
                        "type": "integer",
                        "description": "How long to wait for an answer before giving up (default 600 = 10 min).",
                        "default": 600,
                    },
                },
                "required": ["question"],
            },
        )
        async def ask_human(
            question: str,
            context: str = "",
            options: list[str] | None = None,
            timeout_seconds: int = 600,
        ) -> dict[str, Any]:
            import asyncio
            import json

            client = await self._get_redis_client()
            if client is None or not self._task_id:
                # No Redis / no task context: can't block. Return a sentinel
                # so the model falls back to its best guess.
                return {
                    "answered": False,
                    "reason": "human clarification unavailable (no session)",
                    "suggestion": "proceed with best-guess default",
                }

            # Stamp a marker so the UI knows to show the dialog.
            await client.set(f"task:{self._task_id}:control", "awaiting_input", ex=timeout_seconds)
            await client.set(
                f"task:{self._task_id}:pending_question",
                json.dumps({"question": question, "context": context, "options": options or []}),
                ex=timeout_seconds,
            )
            # Reflect on the task row so list endpoints show the state.
            try:
                import uuid as _uuid
                from ai_driven.db.postgres.repository import TaskRepository
                from ai_driven.db.postgres.session import async_session_factory
                async with async_session_factory() as session:
                    repo = TaskRepository(session)
                    await repo.update_status(_uuid.UUID(self._task_id), "awaiting_input")
                    await session.commit()
            except Exception:
                logger.debug("could not persist awaiting_input status (non-fatal)")

            # Publish the event so UI can render the dialog in real time.
            await self._publish_event(
                "human_input_required",
                {"question": question, "context": context, "options": options or [], "timeout": timeout_seconds},
            )

            # Long-poll for the answer.
            answer_key = f"task:{self._task_id}:human_answer"
            deadline = asyncio.get_event_loop().time() + timeout_seconds
            while asyncio.get_event_loop().time() < deadline:
                answer = await client.get(answer_key)
                if answer:
                    await client.delete(answer_key)
                    await client.delete(f"task:{self._task_id}:pending_question")
                    return {"answered": True, "answer": answer}
                await asyncio.sleep(1.5)

            # Timed out — clear state and let the agent proceed on best guess.
            await client.delete(f"task:{self._task_id}:pending_question")
            await client.delete(f"task:{self._task_id}:control")
            return {
                "answered": False,
                "reason": "no response within timeout",
                "suggestion": "proceed with best-guess default",
            }

    @abstractmethod
    def _register_tools(self) -> None:
        """Register agent-specific tools. Called during __init__."""
        ...

    async def _get_redis_client(self) -> Any:
        """Lazily initialize and return an async Redis client.

        Returns None if Redis is unavailable so callers can degrade gracefully.
        """
        if self._redis_client is not None:
            return self._redis_client
        try:
            import redis.asyncio as aioredis

            settings = get_settings()
            self._redis_client = aioredis.from_url(
                settings.redis_url, decode_responses=True
            )
            return self._redis_client
        except Exception:
            logger.debug("Redis unavailable — event publishing disabled")
            return None

    async def _publish_event(self, event_type: str, data: dict[str, Any]) -> None:
        """Publish an agent event to Redis pub/sub.

        Events are published to the channel ``task:{task_id}:events``.
        Publishing is best-effort: failures are logged but never raised so
        they do not interrupt the agent loop.
        """
        if not self._task_id:
            return

        try:
            client = await self._get_redis_client()
            if client is None:
                return

            event = {
                "type": event_type,
                "data": data,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
            payload = json.dumps(event)
            channel = f"task:{self._task_id}:events"
            # Publish for live subscribers and store for later retrieval
            await client.publish(channel, payload)
            history_key = f"task:{self._task_id}:event_log"
            await client.rpush(history_key, payload)
            await client.expire(history_key, 86400)  # 24h TTL
        except Exception:
            logger.debug(
                "Failed to publish event %s for task %s",
                event_type,
                self._task_id,
            )

    async def _check_control_flag(self) -> str | None:
        """Check if the task has been paused or stopped via Redis.

        Returns ``'paused'``, ``'stopped'``, or ``None`` (continue running).
        Failures are swallowed so a Redis outage never blocks the agent.
        """
        if not self._task_id:
            return None
        try:
            client = await self._get_redis_client()
            if client is None:
                return None
            return await client.get(f"task:{self._task_id}:control")
        except Exception:
            return None

    async def _update_task_status(self, status: str) -> None:
        """Update the task status in PostgreSQL."""
        if not self._task_id:
            return
        try:
            from ai_driven.db.postgres.repository import TaskRepository
            from ai_driven.db.postgres.session import async_session_factory

            async with async_session_factory() as session:
                task_repo = TaskRepository(session)
                await task_repo.update_status(uuid.UUID(self._task_id), status)
                await session.commit()
        except Exception:
            logger.debug(
                "Failed to update task status to %s for task %s",
                status,
                self._task_id,
            )

    @abstractmethod
    def _get_system_prompt(self, context: dict[str, Any]) -> str:
        """Return the system prompt for this agent type."""
        ...

    @abstractmethod
    def _get_tool_names(self) -> list[str]:
        """Return the list of tool names this agent uses."""
        ...

    async def run(
        self,
        task_input: dict[str, Any],
        *,
        task_id: str | None = None,
        model: str | None = None,
        max_iterations: int | None = None,
        use_thinking: bool = False,
        stop_sequences: list[str] | None = None,
    ) -> AgentResult:
        """Execute the agent's main workflow.

        Parameters
        ----------
        task_input:
            The user-provided input data for the agent.
        task_id:
            Optional task identifier.  When provided, agent events are
            published to the Redis channel ``task:{task_id}:events`` so
            that WebSocket consumers can stream progress in real time.
        model:
            Override the default Claude model.
        max_iterations:
            Cap on the number of agent loop iterations.
        use_thinking:
            Enable extended thinking for the model.
        stop_sequences:
            Optional list of sequences that will cause the model to stop
            generating. Passed through to the provider on every iteration.
        """
        self._task_id = task_id
        if task_id:
            bind_task(task_id, agent_type=self.agent_type)
        settings = get_settings()
        conversation = Conversation()
        conversation.system_prompt = self._get_system_prompt(task_input)

        user_message = self._build_initial_message(task_input)
        conversation.add_user_message(user_message)

        thinking = None
        if use_thinking:
            thinking = {"type": "enabled", "budget_tokens": 16000}

        start_time = time.monotonic()
        tool_call_count = 0
        iteration_count = 0

        # Publish phase_change when the agent starts
        await self._publish_event("phase_change", {"phase": "started", "agent_type": self.agent_type})
        await self._update_task_status("coding")

        # Publish the prompt being sent so task detail can show it
        await self._publish_event("prompt_sent", {
            "system_prompt": conversation.system_prompt[:5000],
            "user_message": user_message[:3000],
            "model": model or settings.claude_default_model,
        })

        async def on_tool_call_async(name: str, input_data: dict) -> None:
            nonlocal tool_call_count
            tool_call_count += 1
            logger.info(
                "tool_call_start",
                extra={"tool": name, "call_number": tool_call_count, "iteration": iteration_count + 1},
            )
            # Serialize full input. file.write payloads can be large (full file
            # contents); truncating them breaks JSON parsing downstream and the
            # UI code pane can't render the real file. Cap only at 64k as a
            # safety net to prevent a single pathological event from exploding
            # the Redis list.
            input_json = json.dumps(input_data) if input_data else ""
            if len(input_json) > 65536:
                input_json = input_json[:65536]
            await self._publish_event("tool_call_start", {
                "tool": name,
                "call_number": tool_call_count,
                "input_data": input_json,
            })
            # Transition to testing when sandbox/test commands are run
            if name in ("sandbox.execute", "shell.run", "run_tests"):
                await self._update_task_status("testing")

        def on_tool_call(name: str, input_data: dict) -> None:
            import asyncio
            try:
                loop = asyncio.get_running_loop()
                loop.create_task(on_tool_call_async(name, input_data))
            except RuntimeError:
                pass

        async def on_response_async(response: MessageResponse) -> None:
            nonlocal iteration_count
            iteration_count += 1
            conversation.total_input_tokens += response.usage.input_tokens
            conversation.total_output_tokens += response.usage.output_tokens
            logger.info(
                "agent_iteration",
                extra={
                    "iteration": iteration_count,
                    "input_tokens": response.usage.input_tokens,
                    "output_tokens": response.usage.output_tokens,
                    "has_tool_use": response.has_tool_use,
                    "stop_reason": response.stop_reason,
                    "cache_read_tokens": response.usage.cache_read_input_tokens,
                },
            )
            await self._publish_event("iteration", {
                "iteration": iteration_count,
                "input_tokens": response.usage.input_tokens,
                "output_tokens": response.usage.output_tokens,
                "total_input_tokens": conversation.total_input_tokens,
                "total_output_tokens": conversation.total_output_tokens,
                "has_tool_use": response.has_tool_use,
                "stop_reason": response.stop_reason,
                "cache_read_tokens": response.usage.cache_read_input_tokens,
                "cache_creation_tokens": response.usage.cache_creation_input_tokens,
            })
            # Stream response text chunks for real-time visibility
            if response.text:
                await self._publish_event("response_chunk", {
                    "content": response.text,
                    "iteration": iteration_count,
                })

        def on_response(response: MessageResponse) -> None:
            import asyncio
            try:
                loop = asyncio.get_running_loop()
                loop.create_task(on_response_async(response))
            except RuntimeError:
                nonlocal iteration_count
                iteration_count += 1
                conversation.total_input_tokens += response.usage.input_tokens
                conversation.total_output_tokens += response.usage.output_tokens

        # Collect tool call history for evaluation context
        tool_call_history: list[str] = []

        async def on_tool_result(name: str, duration: float, success: bool, result_data: str = "") -> None:
            # Same reasoning as on_tool_call_async: keep the full result JSON
            # (with a 64k safety cap) so the UI can render real shell stdout,
            # PR metadata, read_file contents, etc. Truncating at 2k was
            # corrupting JSON and the code pane showed empty contents.
            result_json = result_data if result_data else ""
            if len(result_json) > 65536:
                result_json = result_json[:65536]
            await self._publish_event("tool_call_end", {
                "tool": name,
                "duration_ms": int(duration * 1000),
                "success": success,
                "result_data": result_json,
            })
            # Track tool results for evaluation
            status = "OK" if success else "FAILED"
            tool_call_history.append(f"  {name}: {status} ({int(duration*1000)}ms) → {result_data[:200]}")
            # Transition to pr_created when PR is created
            if success and name in ("github.create_pr", "create_pr", "github.createPR"):
                await self._update_task_status("pr_created")

        # Buffered streaming: accumulate text deltas and flush periodically
        _stream_buffer: list[str] = []
        _stream_last_flush = time.monotonic()

        async def _flush_stream_buffer(iteration_num: int) -> None:
            nonlocal _stream_last_flush
            if not _stream_buffer:
                return
            text = "".join(_stream_buffer)
            _stream_buffer.clear()
            _stream_last_flush = time.monotonic()
            await self._publish_event("streaming_chunk", {
                "content": text,
                "iteration": iteration_num,
            })

        async def on_text_chunk_async(delta: str, iteration_num: int) -> None:
            _stream_buffer.append(delta)
            elapsed = time.monotonic() - _stream_last_flush
            buffer_size = sum(len(s) for s in _stream_buffer)
            if elapsed >= _STREAM_FLUSH_INTERVAL or buffer_size >= _STREAM_FLUSH_SIZE:
                await _flush_stream_buffer(iteration_num)

        async def _should_continue() -> bool:
            """Callback for the agent loop to check pause/stop control flags."""
            flag = await self._check_control_flag()
            if flag == "stopped":
                await self._publish_event("complete", {"stopped": True, "message": "Task stopped by user"})
                return False
            if flag == "paused":
                await self._publish_event("phase_change", {"phase": "paused"})
                while True:
                    await asyncio.sleep(2)
                    flag = await self._check_control_flag()
                    if flag != "paused":
                        if flag == "stopped":
                            await self._publish_event("complete", {"stopped": True, "message": "Task stopped by user"})
                            return False
                        await self._publish_event("phase_change", {"phase": "resumed"})
                        break
            return True

        try:
            response = await self._claude.run_agent_loop(
                conversation.messages,
                system=conversation.system_prompt,
                model=model or settings.claude_default_model,
                tools=self._get_tool_names(),
                thinking=thinking,
                max_iterations=max_iterations,
                on_tool_call=on_tool_call,
                on_response=on_response,
                on_tool_result=on_tool_result,
                on_text_chunk=on_text_chunk_async,
                stop_sequences=stop_sequences,
                should_continue=_should_continue,
            )

            # Flush any remaining buffered text
            await _flush_stream_buffer(iteration_count)

            duration = time.monotonic() - start_time
            emit_agent_metrics(
                self.agent_type, duration, tool_call_count, iteration_count, success=True,
            )
            logger.info(
                "agent_complete",
                extra={
                    "success": True,
                    "iterations": iteration_count,
                    "tool_calls": tool_call_count,
                    "duration_seconds": round(duration, 2),
                    "total_input_tokens": conversation.total_input_tokens,
                    "total_output_tokens": conversation.total_output_tokens,
                },
            )

            # Stream the final text to WebSocket clients
            if response.text:
                await self._publish_event("text_delta", {"content": response.text})

            # Publish completion event
            await self._publish_event("complete", {
                "output_length": len(response.text),
                "tool_calls": tool_call_count,
                "iterations": iteration_count,
                "duration_seconds": round(duration, 2),
            })

            # Fire-and-forget post-task evaluation
            if self._task_id and response.text:
                import asyncio
                # Enrich the output with tool call evidence so the grader
                # can see what actually happened (code written, PRs created, etc.)
                eval_output = response.text
                if tool_call_history:
                    evidence = "\n".join(tool_call_history)
                    eval_output = (
                        f"{response.text}\n\n"
                        f"--- Tool Execution Evidence ({tool_call_count} calls, "
                        f"{iteration_count} iterations, {round(duration, 1)}s) ---\n"
                        f"{evidence}"
                    )
                asyncio.create_task(self._run_evaluation(
                    self._task_id, user_message, eval_output, self.agent_type,
                ))

            return AgentResult(
                success=True,
                output=response.text,
                conversation=conversation,
                tool_calls=tool_call_count,
                iterations=iteration_count,
                duration_seconds=duration,
            )

        except Exception as e:
            duration = time.monotonic() - start_time
            emit_agent_metrics(
                self.agent_type, duration, tool_call_count, iteration_count, success=False,
            )
            logger.error(
                "agent_failed",
                extra={
                    "error": str(e),
                    "iterations": iteration_count,
                    "tool_calls": tool_call_count,
                    "duration_seconds": round(duration, 2),
                },
                exc_info=True,
            )

            # Publish error event with user-friendly message
            error_msg = str(e)
            if "AccessDeniedException" in error_msg:
                user_msg = "AI service access denied. Please check your AWS Bedrock permissions."
            elif "ExpiredTokenException" in error_msg:
                user_msg = "AWS credentials have expired. Please refresh your session."
            elif "Could not resolve authentication" in error_msg:
                user_msg = "AI service not configured. Please set your API key or AWS credentials."
            else:
                user_msg = "Agent encountered an error. Please try again."
            await self._publish_event("error", {"message": user_msg})

            return AgentResult(
                success=False,
                output="",
                error=str(e),
                conversation=conversation,
                tool_calls=tool_call_count,
                iterations=iteration_count,
                duration_seconds=duration,
            )

    def _build_initial_message(self, task_input: dict[str, Any]) -> str:
        """Build the initial user message from task input. Override in subclasses."""
        return str(task_input.get("message", task_input))

    @staticmethod
    async def _run_evaluation(task_id: str, task_input: str, agent_output: str, agent_type: str = "coding") -> None:
        """Run post-task evaluation in background."""
        try:
            from ai_driven.evaluation.runner import run_post_task_evaluation
            await run_post_task_evaluation(task_id, task_input, agent_output, agent_type=agent_type)
        except Exception:
            logger.debug("Post-task evaluation skipped or failed for %s", task_id)


class AgentResult:
    """Result of an agent execution."""

    def __init__(
        self,
        success: bool,
        output: str,
        conversation: Conversation,
        tool_calls: int = 0,
        iterations: int = 0,
        duration_seconds: float = 0.0,
        error: str | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> None:
        self.success = success
        self.output = output
        self.conversation = conversation
        self.tool_calls = tool_calls
        self.iterations = iterations
        self.duration_seconds = duration_seconds
        self.error = error
        self.metadata = metadata or {}

    @property
    def total_tokens(self) -> int:
        return self.conversation.total_input_tokens + self.conversation.total_output_tokens
