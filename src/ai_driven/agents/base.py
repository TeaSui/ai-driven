"""Base agent class with common functionality for all AI agents."""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from abc import ABC, abstractmethod
from collections.abc import Callable
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

# Cost math dispatches through billing.tracker._MODEL_PRICING so Haiku /
# Sonnet / Opus each bill at their actual rate and Bedrock-prefixed IDs
# share the same table. Previously this module hardcoded Sonnet-direct
# rates, which (a) over-charged Haiku calls ~8x, (b) under-charged Opus
# calls ~5x, and (c) ignored the Bedrock regional uplift applied to every
# non-us-east-1 account. See KAN-12 post-mortem (2026-04-30).


def _bedrock_region_multiplier() -> float:
    """Return the regional Bedrock price uplift for the current account.

    us-east-1 is at parity with Anthropic Direct; every other AWS region
    carries a regional markup on Bedrock. We apply the configured
    multiplier to the base rate when the active provider is Bedrock and
    the region is NOT us-east-1. Direct Anthropic calls ignore this.
    """
    from ai_driven.config import get_settings  # local: avoid cycles
    settings = get_settings()
    if getattr(settings, "claude_provider", None) != "bedrock" and \
       str(getattr(settings, "claude_provider", "")).lower() != "bedrock":
        return 1.0
    region = getattr(settings, "aws_region", "") or ""
    if region == "us-east-1" or region == "":
        return 1.0
    return float(getattr(settings, "bedrock_price_multiplier", 1.0))


def _iteration_cost_usd(usage: Any, model: str | None = None) -> float:
    """Compute the USD cost of a single Claude response's usage block.

    Dispatches through billing.tracker.get_model_pricing so each model
    bills at its published rate (Haiku / Sonnet / Opus all differ). When
    the active provider is Bedrock and the region is non-us-east-1, the
    configured regional multiplier is applied to all four tiers.

    `model` is optional for backward compat — when omitted or None we
    fall back to settings.claude_default_model so existing callers don't
    break. Callers that know the model they used (agent loop, consumer)
    should pass it for accurate per-call math.
    """
    from ai_driven.billing.tracker import get_model_pricing  # local: avoid cycles
    from ai_driven.config import get_settings  # local: avoid cycles

    resolved_model = model or get_settings().claude_default_model
    rates = get_model_pricing(resolved_model)
    multiplier = _bedrock_region_multiplier()

    # Rates are USD per 1K tokens — convert to per-token and apply multiplier.
    input_per_tok       = (rates["input"]       / 1000.0) * multiplier
    output_per_tok      = (rates["output"]      / 1000.0) * multiplier
    cache_write_per_tok = (rates["cache_write"] / 1000.0) * multiplier
    cache_read_per_tok  = (rates["cache_read"]  / 1000.0) * multiplier

    return (
        usage.input_tokens  * input_per_tok
        + getattr(usage, "cache_creation_input_tokens", 0) * cache_write_per_tok
        + getattr(usage, "cache_read_input_tokens", 0)     * cache_read_per_tok
        + usage.output_tokens * output_per_tok
    )


class BaseAgent(ABC):
    """Base class for all AI agents."""

    agent_type: str = "base"

    # Hard cap on JSON-repair rounds — matches ~/.claude/rules/review-loop-limits.md.
    # When a specialist or the reviewer emits malformed structured JSON, we
    # re-prompt up to this many times, then escalate. Silent truncation is
    # exactly the failure mode review-loop-limits.md warns against.
    MAX_JSON_REPAIR_ROUNDS = 2

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
        # Running dollar cost of this task's Claude calls. Incremented on
        # every iteration; read by _should_continue to enforce the per-task
        # budget ceiling (AI_DRIVEN_CLAUDE_MAX_COST_PER_TASK_USD).
        running_cost_usd = 0.0
        budget_exceeded = False

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
            """Sync callback invoked between iterations in the agent loop.

            The cost accumulator MUST update synchronously here — _should_continue
            reads it to gate the next iteration, and the async event-publishing
            branch below doesn't block, so any cost update scheduled via
            loop.create_task won't be visible to the gate before the next call.
            """
            nonlocal running_cost_usd
            running_cost_usd += _iteration_cost_usd(
                response.usage,
                model=model or settings.claude_default_model,
            )

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
            """Gate for the agent loop: pause/stop control flags AND budget ceiling."""
            nonlocal budget_exceeded

            # Per-task dollar budget kill-switch. Checked FIRST so a runaway
            # loop (like KAN-12's 51-iteration / $6.25 incident) halts before
            # the next Claude call fires instead of after. Matches the post-
            # KAN-12 cost-control plan — see docs/plans/2026-04-29-*.md Red-team
            # revisions + the claude_max_cost_per_task_usd setting.
            if running_cost_usd >= settings.claude_max_cost_per_task_usd:
                budget_exceeded = True
                await self._publish_event("complete", {
                    "stopped": True,
                    "reason": "budget_exceeded",
                    "cost_usd": round(running_cost_usd, 4),
                    "ceiling_usd": settings.claude_max_cost_per_task_usd,
                })
                return False

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

            # A run that stopped mid-workflow is NOT a success. Two modes:
            #   * stop_reason="max_iterations" — the agent-loop budget ran
            #     out before the model chose to stop.
            #   * stop_reason="max_tokens" — the model's final response got
            #     truncated at the output-token cap mid-sentence, which
            #     typically means the DELIVER step (github.create_pr / PR
            #     body composition) never fired.
            # Both leave the branch pushed but no PR opened. Mark as failed
            # so the UI surfaces the problem instead of a misleading
            # "completed" badge with no PR URL.
            bad_stop = getattr(response, "stop_reason", None) in (
                "max_iterations", "max_tokens",
            )
            iterations_exhausted = bad_stop  # kept for the AgentResult message below
            # Budget-exceeded is also a failure mode: the loop halted
            # mid-workflow to stop runaway cost. Mark the result as failed
            # so the UI surfaces "$X.XX / $Y.YY budget ceiling hit" rather
            # than a misleading completed badge.
            run_success = not bad_stop and not budget_exceeded

            duration = time.monotonic() - start_time
            emit_agent_metrics(
                self.agent_type, duration, tool_call_count, iteration_count,
                success=run_success,
            )
            logger.info(
                "agent_complete" if run_success else "agent_iterations_exhausted",
                extra={
                    "success": run_success,
                    "iterations": iteration_count,
                    "tool_calls": tool_call_count,
                    "duration_seconds": round(duration, 2),
                    "total_input_tokens": conversation.total_input_tokens,
                    "total_output_tokens": conversation.total_output_tokens,
                    "stop_reason": getattr(response, "stop_reason", None),
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

            if budget_exceeded:
                error_msg = (
                    f"Agent halted at ${running_cost_usd:.2f} cost ceiling "
                    f"(AI_DRIVEN_CLAUDE_MAX_COST_PER_TASK_USD="
                    f"${settings.claude_max_cost_per_task_usd:.2f}) after "
                    f"{iteration_count} iterations. The task scope was too "
                    f"wide for a single run; split the ticket or raise the "
                    f"ceiling after investigating."
                )
            elif iterations_exhausted:
                error_msg = (
                    f"Agent stopped after {iteration_count} iterations without "
                    f"finishing (max_iterations={max_iterations or 'default'}). "
                    f"Last step may have been a mid-workflow file write — check "
                    f"the branch on the SCM side and consider raising "
                    f"AI_DRIVEN_CLAUDE_MAX_TOOL_ITERATIONS or re-running."
                )
            else:
                error_msg = None

            return AgentResult(
                success=run_success,
                output=response.text,
                conversation=conversation,
                tool_calls=tool_call_count,
                iterations=iteration_count,
                duration_seconds=duration,
                error=error_msg,
                cost_usd=round(running_cost_usd, 4),
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
                cost_usd=round(running_cost_usd, 4),
                duration_seconds=duration,
            )

    async def _repair_or_escalate_json(
        self,
        result: Any,
        *,
        validator: Callable[[str], bool],
        schema_reminder: str,
        system_prompt: str,
        model: str,
        task_id: str | None,
        event_prefix: str = "agent",
    ) -> None:
        """Bounded JSON-repair loop usable by any agent emitting structured JSON.

        Mutates ``result.output`` in place when repair succeeds. After
        ``MAX_JSON_REPAIR_ROUNDS`` (2) failed attempts, sets
        ``result.metadata['escalated'] = True`` and publishes an escalation
        event. Callers decide what to do with an escalated result; this helper
        never raises, never silently truncates, and NEVER updates task status
        (the reviewer's caller handles that itself — the helper stays
        side-effect-free so specialists running in shadow mode don't touch
        tasks.status).
        """
        from ai_driven.config import get_settings  # local: avoid cycles

        output_text = getattr(result, "output", "") or ""
        if validator(output_text):
            return

        settings = get_settings()
        for attempt in range(1, self.MAX_JSON_REPAIR_ROUNDS + 1):
            logger.warning(
                "%s: structured JSON output unparseable; repair attempt %d/%d",
                self.agent_type, attempt, self.MAX_JSON_REPAIR_ROUNDS,
            )
            await self._publish_event(
                f"{event_prefix}_output_repair",
                {"attempt": attempt, "max_attempts": self.MAX_JSON_REPAIR_ROUNDS},
            )
            try:
                repair = await self._claude.chat(
                    messages=[
                        {"role": "user", "content": schema_reminder},
                        {"role": "user", "content": (
                            "Here is your previous reply for reference; "
                            "transform it into the required JSON without losing "
                            "any details:\n\n"
                            # 6000-char cap keeps the repair prompt bounded.
                            # If legitimate outputs routinely exceed this,
                            # escalation is the correct response — raising the
                            # cap here would just burn tokens papering over
                            # output sizes that won't fit the schema anyway.
                            + (output_text[:6000] or "<previous output empty>")
                        )},
                        {"role": "assistant", "content": "```json\n{"},
                    ],
                    system=system_prompt,
                    model=model,
                    max_tokens=settings.claude_max_tokens,
                    temperature=0.0,
                    stop_sequences=["```"],
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning("%s: repair call failed: %s", self.agent_type, exc)
                continue

            repaired_text = "```json\n{" + (repair.text or "")
            if validator(repaired_text):
                result.output = repaired_text
                return
            output_text = repaired_text

        # Two rounds exhausted — escalate via metadata + event only.
        # Do NOT call _update_task_status — 'needs_human_review' is not in
        # the tasks.status CHECK constraint, and specialists running in
        # shadow mode must stay side-effect-free.
        if not hasattr(result, "metadata") or result.metadata is None:
            result.metadata = {}
        result.metadata["escalated"] = True
        result.metadata["escalation_reason"] = (
            f"{self.agent_type} JSON output invalid after "
            f"{self.MAX_JSON_REPAIR_ROUNDS} repair rounds"
        )
        await self._publish_event(
            f"{event_prefix}_escalation",
            {"reason": "invalid_structured_output",
             "rounds_attempted": self.MAX_JSON_REPAIR_ROUNDS},
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
        cost_usd: float = 0.0,
    ) -> None:
        self.success = success
        self.output = output
        self.conversation = conversation
        self.tool_calls = tool_calls
        self.iterations = iterations
        self.duration_seconds = duration_seconds
        self.error = error
        self.metadata = metadata or {}
        # Cache-aware dollar cost accumulated across all Claude calls in
        # this run. Populated by BaseAgent.run via the per-iteration
        # _iteration_cost_usd helper; falls back to 0.0 for callers that
        # construct AgentResult directly (unit tests).
        self.cost_usd = cost_usd

    @property
    def total_tokens(self) -> int:
        return self.conversation.total_input_tokens + self.conversation.total_output_tokens
