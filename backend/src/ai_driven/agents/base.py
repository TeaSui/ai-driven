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
from ai_driven.claude.thinking import resolve_thinking_config
from ai_driven.claude.tools.registry import ToolRegistry
from ai_driven.config import get_settings
from ai_driven.observability.metrics import emit_agent_metrics
from ai_driven.observability.trace_context import bind_task, new_span

logger = logging.getLogger(__name__)


class AwaitingHumanInput(BaseException):
    """Raised by ``human.ask_clarification`` when the caller opted into
    async-continuation (HITL) mode.

    Subclasses :class:`BaseException`, NOT :class:`Exception`, so it
    propagates through the Claude tool-exec ``except Exception`` guard
    in ``claude/client.py::_execute_tool_calls`` and unwinds all the
    way up to the SQS wrapper. The wrapper persists the specialist
    conversation + pending questions to ``task_specialist_state`` and
    ACKs the SQS message — no worker stays blocked.

    Attributes:
        questions: list of ``{id, question, options, why}`` dicts.
        round_id: UUID string tying this pending round to a later
                  ``/respond`` POST.
        messages: the Claude conversation so far (user + assistant +
                  tool_use turns up to this point). Needed so the
                  resumed specialist can append the answer as a
                  ``tool_result`` block and continue without replay.
        tool_use_id: the ``tool_use_id`` of the ask_clarification call
                     being paused on; required to build the matching
                     ``tool_result`` on resume.
    """

    def __init__(
        self,
        *,
        questions: list[dict[str, Any]],
        round_id: str,
        messages: list[dict[str, Any]],
        tool_use_id: str,
    ) -> None:
        super().__init__(f"awaiting human input ({len(questions)} questions)")
        self.questions = questions
        self.round_id = round_id
        self.messages = messages
        self.tool_use_id = tool_use_id


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

    # HITL mode flag. Defaults to "poll" — legacy inline polling that
    # blocks the worker until the user answers. SpecialistBase flips
    # this to "async" when a specialist opts into HITL; in that mode
    # the tool raises :class:`AwaitingHumanInput` instead of polling.
    # The SQS wrapper catches the exception, persists state, and ACKs.
    _hitl_mode: str = "poll"

    def _register_ask_human(self) -> None:
        """Register the universal human-in-the-loop clarification tool.

        Two modes are supported:

        * ``_hitl_mode == "poll"`` (default): the tool long-polls Redis
          for an answer and returns it inline. The caller (typically a
          CodingAgent running inside its own SQS message) is happy to
          wait because it owns the message.
        * ``_hitl_mode == "async"``: the tool validates its inputs,
          persists minimal state, and raises :class:`AwaitingHumanInput`
          so the caller's outer SQS wrapper can snapshot the full
          specialist conversation and ACK the message. Used by
          specialists whose long-polling would starve the queue.

        Humans respond via POST /api/chat/tasks/{id}/respond. Use
        sparingly — prefer best-guess defaults when reasonable.
        """
        @self._registry.tool(
            name="human.ask_clarification",
            description=(
                "Ask the human operator one or more questions in ONE call and "
                "wait for answers. Use ONLY when the Jira ticket is genuinely "
                "ambiguous AND a wrong guess would waste significant effort. "
                "Emit ALL clarifications in a single call via the `questions` "
                "array — do not make multiple sequential ask_clarification "
                "calls. The legacy `question` string field is still accepted "
                "for one-off single-question prompts."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "questions": {
                        "type": "array",
                        "description": (
                            "Batch list of questions to ask at once. Prefer "
                            "this over the scalar `question` field."
                        ),
                        "items": {
                            "type": "object",
                            "properties": {
                                "id": {
                                    "type": "string",
                                    "description": "Stable short id (e.g. 'auth_mechanism').",
                                },
                                "question": {"type": "string"},
                                "options": {
                                    "type": "array",
                                    "items": {"type": "string"},
                                },
                                "why": {
                                    "type": "string",
                                    "description": "One sentence on why this blocks progress.",
                                },
                            },
                            "required": ["id", "question"],
                        },
                    },
                    "question": {
                        "type": "string",
                        "description": "Legacy single-question field (prefer `questions`).",
                    },
                    "context": {
                        "type": "string",
                        "description": "One-sentence context on why you're stuck.",
                    },
                    "options": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "Options for the legacy single-question form.",
                    },
                    "timeout_seconds": {
                        "type": "integer",
                        "description": "Polling-mode timeout (ignored in async HITL mode).",
                        "default": 600,
                    },
                },
            },
        )
        async def ask_human(
            questions: list[dict[str, Any]] | None = None,
            question: str = "",
            context: str = "",
            options: list[str] | None = None,
            timeout_seconds: int = 600,
        ) -> dict[str, Any]:
            import asyncio
            import json
            import uuid as _uuid

            # Normalize to batch shape. A caller using the legacy single-
            # question form becomes a one-element batch with id="q1".
            batch: list[dict[str, Any]] = []
            if questions:
                for q in questions:
                    if not isinstance(q, dict):
                        continue
                    qid = str(q.get("id") or f"q{len(batch)+1}")
                    qtext = str(q.get("question") or "").strip()
                    if not qtext:
                        continue
                    batch.append({
                        "id": qid,
                        "question": qtext,
                        "options": list(q.get("options") or []),
                        "why": str(q.get("why") or "").strip(),
                    })
            elif question:
                batch.append({
                    "id": "q1",
                    "question": question,
                    "options": list(options or []),
                    "why": "",
                })

            if not batch:
                return {
                    "answered": False,
                    "reason": "ask_clarification called with no questions",
                    "suggestion": "proceed with best-guess default",
                }

            # ── Async HITL path ──────────────────────────────────────
            # Caller opted into async-continuation. Raise so the outer
            # SQS wrapper can snapshot conversation state and ACK. The
            # wrapper provides `messages` + `tool_use_id` — unavailable
            # from inside the tool, so the wrapper attaches them when
            # it catches the exception (see SpecialistBase).
            if self._hitl_mode == "async":
                round_id = _uuid.uuid4().hex[:12]
                # messages/tool_use_id are filled in by the outer tool
                # loop in SpecialistBase when it re-raises after the
                # single-step Claude turn; the tool itself can't see
                # either. We raise with placeholders; the caller sets
                # the real values before re-raising.
                raise AwaitingHumanInput(
                    questions=batch,
                    round_id=round_id,
                    messages=[],
                    tool_use_id="",
                )

            # ── Legacy polling path ──────────────────────────────────
            client = await self._get_redis_client()
            if client is None or not self._task_id:
                # No Redis / no task context: can't block. Return a sentinel
                # so the model falls back to its best guess.
                return {
                    "answered": False,
                    "reason": "human clarification unavailable (no session)",
                    "suggestion": "proceed with best-guess default",
                }
            # Legacy mode only honors the first question in the batch.
            question = batch[0]["question"]
            options = batch[0]["options"]
            context = context or batch[0].get("why", "")

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
        initial_cost: float = 0.0,
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
        initial_cost:
            Pre-seed the running-cost accumulator so the per-task
            ``claude_max_cost_per_task_usd`` ceiling applies to the
            COMBINED orchestration run (classifier + specialists +
            coding/review), not per-specialist. When ``initial_cost`` is
            already at-or-above the ceiling the method short-circuits
            BEFORE calling the Claude provider — otherwise the first
            iteration would fire and waste money before ``_should_continue``
            (only consulted between iterations) ever got a chance to gate.
            See docs/specs/2026-04-30-orchestrator-revision.md §2.
        """
        self._task_id = task_id
        if task_id:
            bind_task(task_id, agent_type=self.agent_type)
        settings = get_settings()
        conversation = Conversation()
        conversation.system_prompt = self._get_system_prompt(task_input)

        user_message = self._build_initial_message(task_input)
        conversation.add_user_message(user_message)

        # Pre-iteration budget short-circuit. The agent-loop _should_continue
        # gate is only consulted BETWEEN iterations (see
        # src/ai_driven/claude/client.py lines 107-116); if the orchestrator
        # pre-seeds initial_cost at-or-above the ceiling, the first
        # create_message call would still fire and burn cost before the gate
        # had a chance to run. Fail fast with zero Claude calls and stay
        # shadow-mode-safe (no event publish, no status write) so downstream
        # observers only see real spend.
        if initial_cost >= settings.claude_max_cost_per_task_usd:
            return AgentResult(
                success=False,
                output="",
                conversation=conversation,
                tool_calls=0,
                iterations=0,
                duration_seconds=0.0,
                error=(
                    f"Agent refused to start: pre-seeded cost "
                    f"${initial_cost:.2f} already at or above the "
                    f"${settings.claude_max_cost_per_task_usd:.2f} per-task "
                    f"ceiling (budget_exceeded_on_entry)."
                ),
                metadata={"reason": "budget_exceeded_on_entry"},
                cost_usd=round(initial_cost, 4),
            )

        thinking = None
        if use_thinking:
            target_model = model or settings.claude_default_model
            thinking = resolve_thinking_config(target_model, budget_tokens=16000)

        start_time = time.monotonic()
        tool_call_count = 0
        iteration_count = 0
        # Running dollar cost of this task's Claude calls. Seeded from
        # initial_cost so orchestrator-dispatched specialists inherit the
        # running orchestration tally; incremented on every iteration; read
        # by _should_continue to enforce the per-task budget ceiling
        # (AI_DRIVEN_CLAUDE_MAX_COST_PER_TASK_USD).
        running_cost_usd = initial_cost
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
            stop_reason_final = getattr(response, "stop_reason", None)
            bad_stop = stop_reason_final in ("max_iterations", "max_tokens")
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
            elif stop_reason_final == "max_tokens":
                # The model's final response got truncated at the output-token
                # cap mid-turn — typically a batched file-write or PR body
                # that didn't fit in one response. Iterations weren't the
                # bottleneck here; raising the tool-loop cap won't help.
                error_msg = (
                    f"Agent output truncated at the {settings.claude_max_tokens}-"
                    f"token response cap on iteration {iteration_count} "
                    f"(stop_reason=max_tokens). The model tried to emit more "
                    f"text in a single turn than the cap allows — likely a "
                    f"large batched file write or a long PR body. Raise "
                    f"AI_DRIVEN_CLAUDE_MAX_TOKENS (Sonnet 4.6/4.7 support up "
                    f"to 64k) or prompt the agent to split the turn."
                )
            elif stop_reason_final == "max_iterations":
                error_msg = (
                    f"Agent stopped after {iteration_count} iterations without "
                    f"finishing (max_iterations={max_iterations or 'default'}, "
                    f"stop_reason=max_iterations). Last step may have been a "
                    f"mid-workflow file write — check the branch on the SCM "
                    f"side and consider raising AI_DRIVEN_CLAUDE_MAX_TOOL_"
                    f"ITERATIONS or re-running."
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
            # Provider-aware: Bedrock rejects assistant-message prefill. See
            # the same branch in specialists/base.py::run_specialist.
            provider_is_bedrock = settings.claude_provider == "bedrock"
            if provider_is_bedrock:
                repair_messages = [
                    {"role": "user", "content": schema_reminder},
                    {"role": "user", "content": (
                        "Here is your previous reply for reference; "
                        "transform it into the required JSON without losing "
                        "any details. Respond with ONLY a single ```json "
                        "fenced code block:\n\n"
                        + (output_text[:6000] or "<previous output empty>")
                    )},
                ]
                repair_stops = None
            else:
                repair_messages = [
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
                ]
                repair_stops = ["```"]

            try:
                repair = await self._claude.chat(
                    messages=repair_messages,
                    system=system_prompt,
                    model=model,
                    max_tokens=settings.claude_max_tokens,
                    temperature=0.0,
                    stop_sequences=repair_stops,
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning("%s: repair call failed: %s", self.agent_type, exc)
                continue

            # Anthropic-Direct path stripped the prefill fence prefix; Bedrock
            # returns the full response inline. Reconstruct accordingly.
            if provider_is_bedrock:
                repaired_text = repair.text or ""
            else:
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
