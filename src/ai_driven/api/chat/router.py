"""Chat API — REST + WebSocket for real-time agent interaction."""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import APIRouter, BackgroundTasks, Depends, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict

import redis.asyncio as aioredis

from ai_driven.auth.rate_limiter import RateLimiter
from ai_driven.config import get_settings
from ai_driven.dependencies import get_team_id, require_task_access
from ai_driven.observability.trace_context import bind_task, bind_team, clear_trace

logger = logging.getLogger(__name__)
router = APIRouter()

# Event types the WebSocket endpoint will forward to clients.
_ALLOWED_EVENT_TYPES = frozenset(
    {
        "text_delta", "tool_call_start", "tool_call_end", "phase_change",
        "complete", "error", "prompt_sent", "response_chunk", "iteration",
        "streaming_chunk", "rag_indexing_start", "rag_indexing_complete",
    }
)

# File-write tool names that produce "edit" step types in the UI.
_EDIT_TOOL_NAMES = frozenset({"file.write", "create_file", "fs.writeFile", "edit_file"})


def _translate_event(event: dict[str, Any]) -> dict[str, Any]:
    """Translate internal event types to prototype-compatible step types.

    Backend events -> Prototype step types:
    - streaming_chunk / text_delta / response_chunk -> think
    - tool_call_start + tool_call_end -> tool
    - tool_call_end where tool is a file-write tool -> edit

    The ``stepType`` field is *added* alongside existing fields so that
    downstream consumers that rely on the original ``type`` are unaffected.
    """
    etype = event.get("type", "")
    data = event.get("data", {})

    if etype in ("streaming_chunk", "text_delta", "response_chunk"):
        return {**event, "stepType": "think"}

    if etype == "tool_call_start":
        return {**event, "stepType": "tool"}

    if etype == "tool_call_end":
        tool_name = data.get("tool", "")
        step_type = "edit" if tool_name in _EDIT_TOOL_NAMES else "tool"
        return {**event, "stepType": step_type}

    # Other events (phase_change, complete, error, iteration, etc.) pass
    # through unchanged — no stepType is added.
    return event


def _to_camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(w.capitalize() for w in parts[1:])


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=_to_camel, populate_by_name=True)


class ChatMessage(CamelModel):
    message: str
    agent_type: str = "coding"  # coding | review | workflow | devops
    context: dict[str, Any] = {}


class ChatResponse(CamelModel):
    task_id: str
    session_id: str
    status: str
    message: str


async def _process_task_inline(task_id: str, agent_type: str, message: str, context: dict[str, Any], team_id: str = "") -> None:
    """Process a task inline in debug mode — no SQS worker needed.

    Creates the appropriate agent, runs it with the task_id so events
    are published to Redis, and updates the task status in PostgreSQL.
    """
    from ai_driven.agents.coding.agent import CodingAgent
    from ai_driven.agents.devops.agent import DevOpsAgent
    from ai_driven.agents.reviewer.agent import ReviewAgent
    from ai_driven.agents.workflow.agent import WorkflowAgent
    from ai_driven.db.postgres.repository import TaskRepository
    from ai_driven.db.postgres.session import async_session_factory

    # Bind trace context for inline processing (no SQS propagation in debug mode)
    bind_task(task_id, agent_type=agent_type)
    if team_id:
        bind_team(team_id)

    agent_map: dict[str, type] = {
        "coding": CodingAgent,
        "review": ReviewAgent,
        "workflow": WorkflowAgent,
        "devops": DevOpsAgent,
    }

    AgentClass = agent_map.get(agent_type)
    if not AgentClass:
        logger.error("Unknown agent type for inline processing: %s", agent_type)
        return

    try:
        # Update task status to running
        async with async_session_factory() as session:
            task_repo = TaskRepository(session)
            await task_repo.update_status(uuid.UUID(task_id), "planning")
            await session.commit()

        # Create Jira client if configured
        jira_client = None
        settings_inner = get_settings()
        if settings_inner.jira_base_url and settings_inner.jira_api_token:
            from ai_driven.jira.client import JiraClient
            jira_client = JiraClient()

        # Create SCM client based on the registered repo's scm_type (not the
        # first-non-empty credential). Without this, a Bitbucket-registered
        # repo would silently get GitHubClient whenever GITHUB_TOKEN is set.
        scm_client = None
        repo_full_name = context.get("repo_full_name", "")
        scm_type = "github"
        if repo_full_name:
            try:
                from ai_driven.db.postgres.models import Repository as RepoRow
                from ai_driven.db.postgres.session import async_session_factory
                from sqlalchemy import select as _select
                async with async_session_factory() as _s:
                    _res = await _s.execute(
                        _select(RepoRow.scm_type).where(
                            RepoRow.full_name == repo_full_name
                        )
                    )
                    scm_type = _res.scalar_one_or_none() or "github"
            except Exception:
                scm_type = "github"
        if scm_type == "bitbucket" and settings_inner.bitbucket_username:
            from ai_driven.scm.bitbucket_client import BitbucketClient
            scm_client = BitbucketClient(
                username=settings_inner.bitbucket_username,
                app_password=settings_inner.bitbucket_app_password,
            )
        elif settings_inner.github_token:
            from ai_driven.scm.github_client import GitHubClient
            scm_client = GitHubClient(token=settings_inner.github_token)

        kwargs: dict[str, Any] = {}
        if agent_type in ("coding", "devops"):
            kwargs["jira_client"] = jira_client
        if agent_type in ("coding", "review"):
            kwargs["scm_client"] = scm_client

        agent = AgentClass(**kwargs)
        task_input = {"message": message, **context}
        if repo_full_name:
            task_input["repo_full_name"] = repo_full_name
            task_input["scm_type"] = scm_type

        # Per-task timeout: 20 minutes hard cap
        task_timeout = getattr(settings_inner, "task_timeout_seconds", 1200)
        try:
            result = await asyncio.wait_for(
                agent.run(task_input=task_input, task_id=task_id),
                timeout=task_timeout,
            )
        except asyncio.TimeoutError:
            logger.error("Task %s timed out after %ds", task_id, task_timeout)
            async with async_session_factory() as session:
                task_repo = TaskRepository(session)
                await task_repo.update_status(
                    uuid.UUID(task_id), "failed",
                    error_message=f"Task timed out after {task_timeout // 60} minutes",
                )
                await session.commit()
            redis_client = aioredis.from_url(settings_inner.redis_url, decode_responses=True)
            await redis_client.publish(
                f"task:{task_id}:events",
                json.dumps({"type": "error", "data": {"message": f"Task timed out ({task_timeout // 60}min hard cap)", "timeout": True}}),
            )
            if team_id:
                await RateLimiter().decrement_concurrent(team_id)
            return

        # Update task status based on result
        # Estimate cost: ~$3/M input + ~$15/M output for Sonnet
        input_tokens = result.conversation.total_input_tokens
        output_tokens = result.conversation.total_output_tokens
        cost_usd = (input_tokens * 3.0 / 1_000_000) + (output_tokens * 15.0 / 1_000_000)

        async with async_session_factory() as session:
            task_repo = TaskRepository(session)
            status = "completed" if result.success else "failed"
            await task_repo.update_status(
                uuid.UUID(task_id),
                status,
                output_data={
                    "output": result.output[:5000],
                    "tool_calls": result.tool_calls,
                    "iterations": result.iterations,
                    "error": result.error,
                },
                total_tokens=result.total_tokens,
                total_cost_usd=round(cost_usd, 4),
                iterations=result.iterations,
            )
            await session.commit()

        logger.info(
            "inline_task_completed",
            extra={
                "status": status,
                "iterations": result.iterations,
                "tool_calls": result.tool_calls,
                "total_tokens": result.total_tokens,
                "cost_usd": round(cost_usd, 4),
            },
        )

        # Decrement concurrent count on task completion (success or agent failure)
        if team_id:
            await RateLimiter().decrement_concurrent(team_id)

    except Exception:
        logger.error("inline_task_processing_failed", exc_info=True)
        # Publish error event to Redis so WebSocket clients see the failure
        try:
            settings = get_settings()
            redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
            await redis_client.publish(
                f"task:{task_id}:events",
                json.dumps({"type": "error", "data": {"message": "Agent processing failed"}}),
            )
        except Exception:
            pass

        # Update task status to failed
        try:
            async with async_session_factory() as session:
                task_repo = TaskRepository(session)
                await task_repo.update_status(uuid.UUID(task_id), "failed")
                await session.commit()
        except Exception:
            pass

        # Decrement concurrent count on unhandled failure
        if team_id:
            try:
                await RateLimiter().decrement_concurrent(team_id)
            except Exception:
                pass
    finally:
        clear_trace()


@router.post("/send", response_model=ChatResponse)
async def send_message(
    request: Request, body: ChatMessage, team_id: str = Depends(get_team_id)
) -> ChatResponse | JSONResponse:
    """Send a message to an AI agent (async — returns task ID for polling)."""

    # Budget hard-stop at dispatch time (RULE-D5)
    try:
        from ai_driven.observability.cost_tracker import CostTracker
        tracker = CostTracker()
        budget = await tracker.check_budget(team_id)
        if budget.get("exceeded") or budget.get("usage_percent", 0) >= 100:
            return JSONResponse(
                status_code=429,
                content={"error": {"code": "BUDGET_EXCEEDED", "message": "Daily budget exceeded", "details": []}},
            )
    except Exception:
        logger.warning("Budget check failed — allowing request (fail open)", exc_info=True)

    # Rate limit: 10 requests/minute per team
    limiter = RateLimiter()
    rate_result = await limiter.check_rate_limit(team_id, "chat_send")
    if not rate_result.allowed:
        return JSONResponse(
            status_code=429,
            content={
                "error": {
                    "code": "RATE_LIMITED",
                    "message": "Rate limit exceeded (10 req/min)",
                    "details": {
                        "retry_after": rate_result.retry_after,
                    },
                }
            },
        )

    # Concurrent task limit per team
    concurrent_result = await limiter.check_concurrent(team_id)
    if not concurrent_result.allowed:
        return JSONResponse(
            status_code=429,
            content={
                "error": {
                    "code": "RATE_LIMITED",
                    "message": "Concurrent task limit reached",
                    "details": {
                        "retry_after": concurrent_result.retry_after,
                        "limit": concurrent_result.limit,
                    },
                }
            },
        )

    task_id = str(uuid.uuid4())

    # Bind task to trace context so all downstream logs correlate
    bind_task(task_id, agent_type=body.agent_type)

    logger.info("chat_message_received", extra={"agent_type": body.agent_type})

    # Persist task in PostgreSQL
    try:
        from ai_driven.db.postgres.session import async_session_factory
        from ai_driven.db.postgres.repository import TaskRepository

        async with async_session_factory() as session:
            task_repo = TaskRepository(session)
            jira_key = body.context.get("jira_key")

            # Resolve repo_id so the task links to a registered repository.
            # Without this, admin endpoints that key off Repository.scm_type
            # can't tell Bitbucket tasks from GitHub tasks and default to
            # GitHub (→ 404 on Bitbucket file/diff reads).
            resolved_repo_id: uuid.UUID | None = None
            repo_full_name_ctx = body.context.get("repo_full_name", "")
            if repo_full_name_ctx:
                from ai_driven.db.postgres.models import Repository as _RepoRow
                from sqlalchemy import select as _select
                _res = await session.execute(
                    _select(_RepoRow.id).where(
                        _RepoRow.full_name == repo_full_name_ctx,
                        _RepoRow.team_id == uuid.UUID(team_id),
                    )
                )
                resolved_repo_id = _res.scalar_one_or_none()

            task = await task_repo.create_task(
                task_id=task_id,
                agent_type=body.agent_type,
                title=body.message[:200],
                input_data={"message": body.message, **body.context},
                team_id=uuid.UUID(team_id),
                repo_id=resolved_repo_id,
            )
            if jira_key:
                task.jira_key = jira_key
            await session.commit()
        # Task created successfully — increment concurrent count
        await limiter.increment_concurrent(team_id)
    except Exception:
        logger.exception("Failed to persist task %s", task_id)

    settings = get_settings()

    # SHADOW: kick off the orchestrator for every coding task, but ONLY when
    # the SQS/producer path won't also do it. Without this guard, debug mode
    # and the inline-fallback branch (no queue URL configured) never reach
    # TaskProducer.enqueue_task, so the producer's shadow hook never fires
    # and we must invoke here. With real SQS configured, the producer
    # dispatches shadow itself — doing it again here would double-fire.
    producer_will_handle = (
        not settings.debug and bool(settings.sqs_task_queue_url)
    )
    if (
        body.agent_type == "coding"
        and settings.orchestrator_mode != "off"
        and not producer_will_handle
    ):
        try:
            from ai_driven.agents.orchestrator.agent import OrchestratorAgent
            from ai_driven.queue.producer import _run_shadow

            shadow_payload = {
                "task_id": task_id,
                "ticket": {
                    "key": body.context.get("jira_key", ""),
                    "summary": body.context.get("title") or body.message,
                    "description": body.message,
                    "labels": body.context.get("labels", []),
                    "acceptance_criteria": body.context.get(
                        "acceptance_criteria", []
                    ),
                },
                "repo": {
                    "full_name": body.context.get("repo_full_name", ""),
                    "scm_type": body.context.get("scm_type", "github"),
                    "default_branch": body.context.get("default_branch", "main"),
                },
            }
            asyncio.create_task(_run_shadow(OrchestratorAgent(), shadow_payload))
        except Exception:
            logger.exception(
                "shadow orchestrator dispatch failed in chat path (non-fatal)"
            )

    if settings.debug:
        # In debug mode, process inline — no SQS worker needed
        asyncio.create_task(_process_task_inline(task_id, body.agent_type, body.message, body.context, team_id=team_id))
    else:
        # Production: enqueue to SQS for async processing by ECS worker
        try:
            from ai_driven.queue.producer import TaskProducer

            producer = TaskProducer()
            await producer.enqueue_task(
                task_id=task_id,
                agent_type=body.agent_type,
                payload={"message": body.message, **body.context},
            )
        except Exception:
            logger.exception("Failed to enqueue task %s", task_id)

    # Use existing sessionId from request or generate a new one
    session_id = body.context.get("session_id") or str(uuid.uuid4())

    return ChatResponse(
        task_id=task_id,
        session_id=session_id,
        status="queued",
        message=f"Task {task_id} queued for {body.agent_type} agent",
    )


@router.post("/tasks/{task_id}/pause")
async def pause_task(task_id: str, _task=Depends(require_task_access)) -> dict:
    """Pause a running agent task by setting a Redis control flag."""
    settings = get_settings()
    redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
    await redis_client.set(f"task:{task_id}:control", "paused", ex=3600)
    return {"status": "paused", "taskId": task_id}


@router.post("/tasks/{task_id}/resume")
async def resume_task(task_id: str, _task=Depends(require_task_access)) -> dict:
    """Resume a paused agent task by clearing the Redis control flag."""
    settings = get_settings()
    redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
    await redis_client.delete(f"task:{task_id}:control")
    return {"status": "resumed", "taskId": task_id}


@router.post("/tasks/{task_id}/stop")
async def stop_task(task_id: str, _task=Depends(require_task_access)) -> dict:
    """Stop a running agent task."""
    settings = get_settings()
    redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
    await redis_client.set(f"task:{task_id}:control", "stopped", ex=3600)
    # Update DB status to cancelled (not failed — user-initiated stop is distinct)
    from ai_driven.db.postgres.repository import TaskRepository
    from ai_driven.db.postgres.session import async_session_factory

    async with async_session_factory() as session:
        task_repo = TaskRepository(session)
        await task_repo.update_status(
            uuid.UUID(task_id), "cancelled", error_message="Task stopped by user",
        )
        await session.commit()
    # Publish complete event so WebSocket clients see the stop
    await redis_client.publish(
        f"task:{task_id}:events",
        json.dumps({"type": "complete", "data": {"stopped": True, "message": "Task cancelled by user"}}),
    )
    return {"status": "cancelled", "taskId": task_id}


# ── Human-in-the-loop clarification ──────────────────────────────────────────

class HumanResponseBody(BaseModel):
    """Body for POST /tasks/{id}/respond — answers the agent's pending question."""
    answer: str


@router.post("/tasks/{task_id}/respond")
async def respond_to_task(
    task_id: str,
    body: HumanResponseBody,
    _task=Depends(require_task_access),
) -> dict:
    """Reply to an agent that invoked `human.ask_clarification`.

    The agent loop is waiting on the Redis key `task:{id}:human_answer`. We
    write the answer there; the pending future resolves and the agent continues
    its next iteration with the clarification as a tool_result.
    """
    settings = get_settings()
    redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
    answer = (body.answer or "").strip()
    if not answer:
        return JSONResponse(
            status_code=400,
            content={"error": {"code": "EMPTY_ANSWER", "message": "answer cannot be empty"}},
        )
    await redis_client.set(f"task:{task_id}:human_answer", answer, ex=3600)
    # Clear the "awaiting_input" control flag so the agent resumes.
    await redis_client.delete(f"task:{task_id}:control")
    # Move status back to running so the UI stops showing the question card.
    from ai_driven.db.postgres.repository import TaskRepository
    from ai_driven.db.postgres.session import async_session_factory
    async with async_session_factory() as session:
        repo = TaskRepository(session)
        await repo.update_status(uuid.UUID(task_id), "running")
        await session.commit()
    # Echo the human answer into (a) the pub/sub channel for live WebSocket
    # subscribers and (b) the persisted event log so any client reloading the
    # task page sees the question was answered and the clarification card clears.
    event_payload = json.dumps({
        "type": "human_response",
        "data": {"answer": answer},
        "timestamp": datetime.now(timezone.utc).isoformat(),
    })
    await redis_client.rpush(f"task:{task_id}:event_log", event_payload)
    await redis_client.publish(f"task:{task_id}:events", event_payload)
    return {"status": "accepted", "taskId": task_id}


async def _auth_from_ws(websocket: WebSocket, task_id: str) -> str | None:
    """Authenticate WebSocket via first-message pattern (RULE-S2).

    Expects ``{"type": "auth", "token": "<api-key>"}`` within 5 seconds.
    In debug mode, auth is optional — missing/invalid tokens are allowed.
    Returns team_id if authorized, None otherwise (connection closed).
    """
    settings = get_settings()
    debug_team = "00000000-0000-0000-0000-000000000000"

    try:
        raw = await asyncio.wait_for(websocket.receive_text(), timeout=5.0)
        msg = json.loads(raw)
    except asyncio.TimeoutError:
        if settings.debug:
            return debug_team
        await websocket.close(code=4401, reason="Auth timeout")
        return None
    except (json.JSONDecodeError, TypeError):
        if settings.debug:
            return debug_team
        await websocket.close(code=4401, reason="Invalid auth message")
        return None

    if msg.get("type") != "auth" or not msg.get("token"):
        # Browsers using the session-token flow may send an empty token
        # because session_token is HttpOnly and unreadable from JS.
        # Fall back to the session_token cookie on the WS request itself.
        cookie_token = websocket.cookies.get("session_token")
        if cookie_token:
            msg = {"type": "auth", "token": cookie_token}
        elif settings.debug:
            return debug_team
        else:
            await websocket.close(code=4401, reason="Missing auth")
            return None

    # Resolve team from the token. Browsers hold an HttpOnly session_token
    # (from /login) and cannot read the raw API key from JS, so the UI sends
    # whichever token it can reach. Try session_token FIRST (Redis lookup is
    # cheap) — then fall back to API-key hash for agents/scripts that still
    # send the raw key.
    from ai_driven.auth.middleware import (
        _get_cached_team,
        _resolve_session,
        _resolve_team_from_db,
        _set_cached_team,
        hash_api_key,
    )

    token = msg["token"]
    team_data: dict | None = None

    session_data = await _resolve_session(token)
    if session_data is not None:
        team_data = {"id": session_data["team_id"]}

    if team_data is None:
        api_key_hash = hash_api_key(token)
        team_data = await _get_cached_team(api_key_hash)
        if team_data is None:
            team_data = await _resolve_team_from_db(api_key_hash)
            if team_data is not None:
                await _set_cached_team(api_key_hash, team_data)

    if team_data is None:
        await websocket.close(code=4401, reason="Invalid token")
        return None

    # Verify team owns the task
    from ai_driven.db.postgres.session import async_session_factory
    from ai_driven.db.postgres.repository import TaskRepository

    async with async_session_factory() as session:
        task_repo = TaskRepository(session)
        task = await task_repo.get_by_id(uuid.UUID(task_id))
        if task is None or str(task.team_id) != team_data["id"]:
            await websocket.close(code=4404)
            return None

    return team_data["id"]


@router.websocket("/stream/{task_id}")
async def stream_agent(websocket: WebSocket, task_id: str) -> None:
    """WebSocket endpoint for streaming agent responses in real-time.

    Uses first-message auth pattern: client must send
    ``{"type": "auth", "token": "<api-key>"}`` within 5 seconds.

    Then subscribes to the Redis pub/sub channel ``task:{task_id}:events``
    and forwards each event to the connected client.
    """
    await websocket.accept()

    # First-message auth — returns team_id or None
    team_id = await _auth_from_ws(websocket, task_id)
    if team_id is None:
        return

    # WebSocket connection limit per team
    from ai_driven.api.chat.ws_limiter import WebSocketLimiter
    ws_limiter = WebSocketLimiter()
    if not await ws_limiter.acquire(team_id):
        await websocket.close(code=4429, reason="Too many connections")
        return

    pubsub = None
    try:
        settings = get_settings()
        redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
        pubsub = redis_client.pubsub()
        channel = f"task:{task_id}:events"
        await pubsub.subscribe(channel)

        # Confirm connection to the client
        await websocket.send_json({"type": "connected", "task_id": task_id})

        step_counter = 0

        while True:
            # Poll Redis for a message (non-blocking, with short timeout)
            message = await pubsub.get_message(
                ignore_subscribe_messages=True, timeout=0.1
            )

            if message is not None and message["type"] == "message":
                try:
                    event = json.loads(message["data"])
                except (json.JSONDecodeError, TypeError):
                    continue

                event_type = event.get("type")
                if event_type not in _ALLOWED_EVENT_TYPES:
                    continue

                # Translate to prototype-compatible step types and
                # assign incremental step numbers.
                translated = _translate_event(event)
                if translated.get("stepType"):
                    step_counter += 1
                    translated["stepNumber"] = step_counter

                await websocket.send_json(translated)

                # Terminal events end the stream
                if event_type in ("complete", "error"):
                    break

            # Check for client messages (non-blocking via wait_for)
            try:
                data = await asyncio.wait_for(
                    websocket.receive_text(), timeout=0.05
                )
                try:
                    msg = json.loads(data)
                except (json.JSONDecodeError, TypeError):
                    pass
                else:
                    if msg.get("type") == "ping":
                        await websocket.send_json({"type": "pong"})
            except asyncio.TimeoutError:
                pass

    except WebSocketDisconnect:
        logger.info("ws_disconnected", extra={"task_id": task_id})
    except Exception:
        logger.error("ws_error", extra={"task_id": task_id}, exc_info=True)
    finally:
        # Release WS connection slot
        try:
            await ws_limiter.release(team_id)
        except Exception:
            pass
        if pubsub is not None:
            try:
                await pubsub.unsubscribe()
                await pubsub.close()
            except Exception:
                pass
