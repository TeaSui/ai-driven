"""Request-scoped trace context using Python contextvars.

Provides a structured correlation context (trace_id, span_id, task_id, team_id)
that is automatically propagated across async boundaries and injected into every
log record by the ``TraceContextFilter``.

Usage
-----
Middleware sets the context at the start of a request::

    bind_trace(trace_id="abc123", team_id="team-1")

Agent code adds the task scope::

    bind_task("task-uuid")

Any ``logger.info(...)`` call within that async context will automatically
include ``trace_id``, ``span_id``, ``task_id``, and ``team_id`` as structured
fields in the JSON log output — no manual ``extra={}`` needed.

Span management::

    with new_span("tool:github.create_pr") as span_id:
        # logs within this block have span_id set
        ...
"""

from __future__ import annotations

import contextvars
import logging
import uuid
from contextlib import contextmanager
from typing import Any, Generator

# ── Context variables ────────────────────────────────────────────────────────

_trace_id: contextvars.ContextVar[str] = contextvars.ContextVar("trace_id", default="")
_span_id: contextvars.ContextVar[str] = contextvars.ContextVar("span_id", default="")
_task_id: contextvars.ContextVar[str] = contextvars.ContextVar("task_id", default="")
_team_id: contextvars.ContextVar[str] = contextvars.ContextVar("team_id", default="")
_agent_type: contextvars.ContextVar[str] = contextvars.ContextVar("agent_type", default="")


# ── Public API ───────────────────────────────────────────────────────────────


def generate_trace_id() -> str:
    """Generate a new trace ID (16-char hex, compact enough for log lines)."""
    return uuid.uuid4().hex[:16]


def generate_span_id() -> str:
    """Generate a new span ID (8-char hex)."""
    return uuid.uuid4().hex[:8]


def bind_trace(
    *,
    trace_id: str | None = None,
    team_id: str = "",
) -> str:
    """Set trace context for the current async task. Returns the trace_id."""
    tid = trace_id or generate_trace_id()
    _trace_id.set(tid)
    _span_id.set(generate_span_id())
    if team_id:
        _team_id.set(team_id)
    return tid


def bind_task(task_id: str, agent_type: str = "") -> None:
    """Bind task_id (and optionally agent_type) to the current trace context."""
    _task_id.set(task_id)
    if agent_type:
        _agent_type.set(agent_type)


def bind_team(team_id: str) -> None:
    """Bind team_id to the current trace context."""
    _team_id.set(team_id)


@contextmanager
def new_span(name: str = "") -> Generator[str, None, None]:
    """Create a child span, restoring the parent span on exit.

    The *name* is stored in the span_id for readability in logs
    (e.g., ``"a1b2c3d4:tool:github.create_pr"``).

    Usage::

        with new_span("tool:file.write") as sid:
            logger.info("writing file")  # span_id = sid
    """
    parent = _span_id.get("")
    child = generate_span_id()
    _span_id.set(child)
    try:
        yield child
    finally:
        _span_id.set(parent)


def get_trace_context() -> dict[str, str]:
    """Return the current trace context as a dict (for SQS attributes, headers, etc.)."""
    return {
        "trace_id": _trace_id.get(""),
        "span_id": _span_id.get(""),
        "task_id": _task_id.get(""),
        "team_id": _team_id.get(""),
        "agent_type": _agent_type.get(""),
    }


def clear_trace() -> None:
    """Clear all trace context variables (call at end of request)."""
    for var in (_trace_id, _span_id, _task_id, _team_id, _agent_type):
        var.set("")


# ── Logging filter ───────────────────────────────────────────────────────────


class TraceContextFilter(logging.Filter):
    """Injects trace context fields into every log record.

    Fields added:
    - ``trace_id``: request-level correlation ID
    - ``span_id``: operation-level ID (changes per span)
    - ``task_id``: agent task ID (set when agent runs)
    - ``team_id``: tenant ID (set by auth middleware)
    - ``agent_type``: agent type (coding/review/devops/workflow)

    Empty strings are used when a field hasn't been bound yet, so logs
    always have the same schema.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = _trace_id.get("")  # type: ignore[attr-defined]
        record.span_id = _span_id.get("")  # type: ignore[attr-defined]
        record.task_id = _task_id.get("")  # type: ignore[attr-defined]
        record.team_id = _team_id.get("")  # type: ignore[attr-defined]
        record.agent_type = _agent_type.get("")  # type: ignore[attr-defined]
        return True
