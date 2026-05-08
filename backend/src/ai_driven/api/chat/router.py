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
from pydantic import BaseModel

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
_EDIT_TOOL_NAMES = frozenset(
    {"file.write", "file.write_batch", "create_file", "fs.writeFile", "edit_file"}
)


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


from ai_driven.api._shared import CamelModel, to_camel as _to_camel  # noqa: F401


class ChatMessage(CamelModel):
    message: str
    agentType: str = "coding"  # coding | review | devops
    context: dict[str, Any] = {}


class ChatResponse(CamelModel):
    taskId: str
    sessionId: str
    status: str
    message: str


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

    # Coding / review agents cannot function without a repo_full_name.
    # Reject early with a clear 400 rather than letting the worker
    # dispatch a task whose first tool call hits "SCM client not
    # configured" and stalls on a clarification.
    repo_required_agents = {"coding", "review"}
    if body.agentType in repo_required_agents:
        if not (body.context or {}).get("repo_full_name"):
            return JSONResponse(
                status_code=400,
                content={"error": {"code": "REPO_REQUIRED", "message": f"repo_full_name is required for agentType={body.agentType!r}"}},
            )

    task_id = str(uuid.uuid4())

    # Bind task to trace context so all downstream logs correlate
    bind_task(task_id, agent_type=body.agentType)

    logger.info("chat_message_received", extra={"agent_type": body.agentType})

    # Hydrate Jira ticket context so the orchestrator classifier sees
    # the actual trust-boundary signal (labels like security/auth/
    # payments/pii) rather than defaulting everything to fast_path.
    # The UI's "New run" modal only sends {jira_key, repo_full_name};
    # without this fetch, input_data.labels stays empty and the
    # classifier collapses on summary-only input.
    jira_hydrated: dict[str, Any] = {}
    jira_key_for_hydrate = body.context.get("jira_key")
    if jira_key_for_hydrate:
        try:
            from ai_driven.jira.client import JiraClient
            _jira = JiraClient()
            try:
                issue = await _jira.get_issue(jira_key_for_hydrate)
                jira_hydrated = {
                    "summary": issue.summary,
                    "description": issue.description,
                    "labels": issue.labels or [],
                    "issue_type": issue.issue_type,
                    "acceptance_criteria": issue.acceptance_criteria,
                }
            finally:
                await _jira.close()
        except Exception:
            logger.warning(
                "chat_send.jira_hydrate_failed",
                extra={"jira_key": jira_key_for_hydrate},
                exc_info=True,
            )

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
                agent_type=body.agentType,
                title=body.message[:200],
                input_data={
                    "message": body.message,
                    **body.context,
                    # Hydrated Jira fields override any same-key
                    # values the client may have sent optimistically.
                    **jira_hydrated,
                },
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

    # T20 removed the in-process shadow orchestrator fan-out from the chat
    # path. The orchestrator runs as an SQS worker:
    # TaskProducer.enqueue_task('coding', ...) routes to the orchestrator
    # queue when orchestrator_mode != 'off'. The orchestrator worker then
    # enqueues the downstream coding-task.
    try:
        from ai_driven.queue.producer import TaskProducer

        producer = TaskProducer()
        await producer.enqueue_task(
            task_id=task_id,
            agent_type=body.agentType,
            payload={
                "message": body.message,
                **body.context,
                # Same hydration as input_data — the SQS consumer
                # reads labels/summary/description off the payload
                # when building the orchestrator ticket dict.
                **jira_hydrated,
            },
        )
    except Exception:
        logger.exception("Failed to enqueue task %s", task_id)

    # Use existing sessionId from request or generate a new one
    session_id = body.context.get("session_id") or str(uuid.uuid4())

    return ChatResponse(
        taskId=task_id,
        sessionId=session_id,
        status="queued",
        message=f"Task {task_id} queued for {body.agentType} agent",
    )


@router.post("/tasks/{task_id}/pause")
async def pause_task(task_id: str, _task=Depends(require_task_access)) -> dict:
    """Pause a running agent task.

    Sets a Redis control flag that the agent loop checks between
    iterations AND flips tasks.status to 'paused' in Postgres. Without
    the DB update the UI's Resume button (which is gated on
    ``task.status === 'paused'``) never appears, and any worker-side
    exception while the flag is set silently transitions the row to
    'failed'.
    """
    from ai_driven.db.postgres.repository import TaskRepository
    from ai_driven.db.postgres.session import async_session_factory

    settings = get_settings()
    redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
    await redis_client.set(f"task:{task_id}:control", "paused", ex=3600)

    async with async_session_factory() as session:
        task_repo = TaskRepository(session)
        try:
            await task_repo.update_status(uuid.UUID(task_id), "paused")
            await session.commit()
        except Exception:
            logger.exception("pause_status_update_failed", extra={"task_id": task_id})

    # Notify any live WebSocket subscribers so the UI flips to Resume
    # without waiting for the next polling tick.
    await redis_client.publish(
        f"task:{task_id}:events",
        json.dumps({"type": "phase_change", "data": {"phase": "paused"}}),
    )
    return {"status": "paused", "taskId": task_id}


@router.post("/tasks/{task_id}/resume")
async def resume_task(task_id: str, _task=Depends(require_task_access)) -> dict:
    """Resume a paused agent task.

    Clears the Redis control flag and moves tasks.status from 'paused'
    back to 'running' so the sidebar/task list reflect in-flight state
    immediately. The agent loop's pause-wait (base.py) will observe the
    cleared flag on its next 2s tick and continue.
    """
    from ai_driven.db.postgres.repository import TaskRepository
    from ai_driven.db.postgres.session import async_session_factory

    settings = get_settings()
    redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)
    await redis_client.delete(f"task:{task_id}:control")

    async with async_session_factory() as session:
        task_repo = TaskRepository(session)
        try:
            await task_repo.update_status(uuid.UUID(task_id), "running")
            await session.commit()
        except Exception:
            logger.exception("resume_status_update_failed", extra={"task_id": task_id})

    await redis_client.publish(
        f"task:{task_id}:events",
        json.dumps({"type": "phase_change", "data": {"phase": "resumed"}}),
    )
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
    """Body for POST /tasks/{id}/respond — answers the agent's pending clarification.

    Two flavours for forward + backward compatibility:

      * HITL-v1 (preferred): ``answers`` is a dict keyed by question id,
        ``round_id`` matches the pending round in
        ``task_specialist_state.pending_question``. The endpoint
        records the answer and enqueues a ``specialist_resume`` SQS
        message — the worker is NOT blocked during the wait.
      * Legacy: ``answer`` is a single string. Writes the pre-HITL-v1
        Redis key that the legacy CodingAgent polling loop reads.
        Preserved so the CodingAgent flow keeps working.
    """
    answer: str | None = None
    answers: dict[str, str] | None = None
    round_id: str | None = None


@router.post("/tasks/{task_id}/respond", response_model=None)
async def respond_to_task(
    task_id: str,
    body: HumanResponseBody,
    _task=Depends(require_task_access),
) -> dict | JSONResponse:
    """Reply to an agent that invoked ``human.ask_clarification``.

    When a ``round_id`` is present, routes to the HITL-v1 async
    continuation path: updates ``task_specialist_state`` + enqueues a
    ``specialist_resume`` SQS message. Otherwise falls back to the
    legacy Redis-key path used by the CodingAgent polling loop.
    """
    settings = get_settings()
    redis_client = aioredis.from_url(settings.redis_url, decode_responses=True)

    # ── HITL-v1 async path ───────────────────────────────────────────
    if body.round_id:
        # Normalize answers. An empty dict is acceptable — the user can
        # acknowledge with no answers when the cap is hit, letting the
        # specialist emit a terminal verdict on best-guess defaults.
        answers = {
            str(k): (v if isinstance(v, str) else json.dumps(v))
            for k, v in (body.answers or {}).items()
        }
        from ai_driven.db.postgres.repository import (
            SpecialistStateRepository,
            TaskRepository,
        )
        from ai_driven.db.postgres.session import async_session_factory

        # Find the open specialist state row for this task. The
        # frontend doesn't track which role is pending; we look it up.
        async with async_session_factory() as session:
            from ai_driven.db.postgres.models import TaskSpecialistState
            from sqlalchemy import select
            task_uuid = uuid.UUID(task_id)
            rows = await session.execute(
                select(TaskSpecialistState).where(
                    TaskSpecialistState.task_id == task_uuid,
                    TaskSpecialistState.pending_question.isnot(None),
                )
            )
            row = rows.scalar_one_or_none()
            if row is None:
                return JSONResponse(
                    status_code=404,
                    content={"error": {"code": "NO_PENDING_QUESTION", "message": "no clarification is pending for this task"}},
                )
            # Cap check. When hitl_max_rounds is reached we still
            # accept the answer but the resume wrapper hides the
            # ask_clarification tool, forcing a terminal verdict.
            state_repo = SpecialistStateRepository(session)
            updated = await state_repo.record_answer(
                task_uuid, row.role, body.round_id, answers,
            )
            if updated is None:
                return JSONResponse(
                    status_code=409,
                    content={"error": {"code": "ROUND_MISMATCH", "message": "round_id does not match the pending round"}},
                )
            # Move task back into running so the UI stops rendering
            # the awaiting_input card while the resume SQS message is
            # picked up.
            await TaskRepository(session).update_status(task_uuid, "running")
            await session.commit()
            role = row.role
            rounds_now = updated.rounds
            # Grab the original payload for re-enqueue. We stored the
            # minimal payload at pause time; reconstruct from task row
            # for safety.
            from ai_driven.db.postgres.models import Task
            task_row = await session.get(Task, task_uuid)
            original_payload = dict(task_row.input_data or {}) if task_row else {}

        # Enqueue the specialist_resume SQS message. The wrapper loads
        # state, continues the Claude conversation with the answer,
        # and re-enqueues the coding-task on terminal verdict.
        from ai_driven.queue.producer import TaskProducer
        producer = TaskProducer()
        resume_payload = {
            "role": role,
            "round_id": body.round_id,
            "ticket": {
                "key": original_payload.get("jira_key", ""),
                "summary": original_payload.get("summary", ""),
                "description": original_payload.get("description", ""),
                "labels": original_payload.get("labels", []),
                "acceptance_criteria": original_payload.get("acceptance_criteria", ""),
                "issue_type": original_payload.get("issue_type", ""),
                "triggered_by": original_payload.get("triggered_by", ""),
            },
            "repo": {
                "full_name": original_payload.get("repo_full_name", ""),
                "scm_type": original_payload.get("scm_type", ""),
                "default_branch": original_payload.get("default_branch", "master"),
            },
            "tier": "full_workflow",
            "original_payload": original_payload,
        }
        try:
            await producer.enqueue_task(
                task_id=task_id,
                agent_type="specialist_resume",
                payload=resume_payload,
                skip_orchestrator_routing=True,
            )
        except Exception:
            logger.exception("specialist_resume_enqueue_failed",
                             extra={"task_id": task_id, "round_id": body.round_id})
            return JSONResponse(
                status_code=503,
                content={"error": {"code": "ENQUEUE_FAILED", "message": "could not enqueue resume"}},
            )

        # Persist to the replayable event_log AND publish live. Detail page
        # reads event_log via REST; without the rpush, a page refresh would
        # still see the unmatched clarification_requested event and keep
        # rendering the ClarificationCard even though the answer is in.
        answered_event = json.dumps({
            "type": "specialist.clarification_answered",
            "data": {"role": role, "round_id": body.round_id, "rounds": rounds_now},
            "timestamp": datetime.now(timezone.utc).isoformat(),
        })
        await redis_client.rpush(f"task:{task_id}:event_log", answered_event)
        await redis_client.publish(f"task:{task_id}:events", answered_event)
        return {"status": "resumed", "taskId": task_id, "rounds": rounds_now}

    # ── Legacy single-string-answer path (CodingAgent) ───────────────
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

    Expects ``{"type": "auth", "token": "<api-key-or-session-token>"}``
    within 5 seconds. Returns team_id if authorized, None otherwise
    (connection closed).
    """
    try:
        raw = await asyncio.wait_for(websocket.receive_text(), timeout=5.0)
        msg = json.loads(raw)
    except asyncio.TimeoutError:
        await websocket.close(code=4401, reason="Auth timeout")
        return None
    except (json.JSONDecodeError, TypeError):
        await websocket.close(code=4401, reason="Invalid auth message")
        return None

    if msg.get("type") != "auth" or not msg.get("token"):
        # Browsers using the session-token flow may send an empty token
        # because session_token is HttpOnly and unreadable from JS.
        # Fall back to the session_token cookie on the WS request itself.
        cookie_token = websocket.cookies.get("session_token")
        if cookie_token:
            msg = {"type": "auth", "token": cookie_token}
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
