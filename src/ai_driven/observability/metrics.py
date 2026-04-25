"""DataDog custom metrics — AI-specific instrumentation."""

from __future__ import annotations

import logging
import time
from contextlib import contextmanager
from typing import Any, Generator

from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

# Lazy-init DogStatsD client
_statsd = None


def _get_statsd() -> Any:
    global _statsd
    if _statsd is None:
        settings = get_settings()
        if settings.datadog_enabled:
            from datadog import DogStatsd
            _statsd = DogStatsd(
                host="localhost",
                port=8125,
                constant_tags=[f"service:{settings.dd_service}", f"env:{settings.dd_env}"],
            )
        else:
            _statsd = _NoOpStatsd()
    return _statsd


class _NoOpStatsd:
    """No-op statsd client for local development."""
    def increment(self, *a: Any, **kw: Any) -> None: pass
    def gauge(self, *a: Any, **kw: Any) -> None: pass
    def histogram(self, *a: Any, **kw: Any) -> None: pass
    def timing(self, *a: Any, **kw: Any) -> None: pass


def emit_claude_metrics(usage: Any, model: str, provider: str) -> None:
    """Emit Claude API usage metrics to DataDog."""
    statsd = _get_statsd()
    tags = [f"model:{model}", f"provider:{provider}"]

    statsd.increment("ai.claude.tokens.input", usage.input_tokens, tags=tags)
    statsd.increment("ai.claude.tokens.output", usage.output_tokens, tags=tags)

    if hasattr(usage, "cache_read_input_tokens") and usage.cache_read_input_tokens:
        statsd.increment("ai.claude.tokens.cache_read", usage.cache_read_input_tokens, tags=tags)
    if hasattr(usage, "cache_creation_input_tokens") and usage.cache_creation_input_tokens:
        statsd.increment("ai.claude.tokens.cache_creation", usage.cache_creation_input_tokens, tags=tags)

    # Estimate cost (approximate pricing)
    cost = _estimate_cost(usage, model)
    statsd.gauge("ai.claude.cost.usd", cost, tags=tags)


def emit_agent_metrics(
    agent_type: str,
    duration_seconds: float,
    tool_calls: int,
    iterations: int,
    success: bool,
) -> None:
    """Emit agent task metrics to DataDog."""
    statsd = _get_statsd()
    tags = [f"agent_type:{agent_type}", f"success:{success}"]

    statsd.histogram("ai.agent.task.duration", duration_seconds, tags=tags)
    statsd.increment("ai.agent.task.tool_calls", tool_calls, tags=tags)
    statsd.increment("ai.agent.task.iterations", iterations, tags=tags)
    statsd.increment("ai.agent.task.count", 1, tags=tags)


def emit_review_metrics(
    issues_found: int,
    critical_issues: int,
    comments_posted: int,
    duration_seconds: float = 0.0,
    diff_size: int = 0,
    model_used: str = "",
    verdict: str = "",
) -> None:
    """Emit code review metrics."""
    statsd = _get_statsd()
    tags = []
    if model_used:
        tags.append(f"model:{model_used}")
    if verdict:
        tags.append(f"verdict:{verdict}")

    statsd.increment("ai.review.issues_found", issues_found, tags=tags)
    statsd.increment("ai.review.critical_issues", critical_issues, tags=tags)
    statsd.increment("ai.review.comments_posted", comments_posted, tags=tags)
    if duration_seconds > 0:
        statsd.histogram("ai.review.duration", duration_seconds, tags=tags)
    if diff_size > 0:
        statsd.histogram("ai.review.diff_size", diff_size, tags=tags)
    if verdict:
        statsd.increment("ai.review.verdict", 1, tags=tags)
    if model_used:
        statsd.increment("ai.review.model_used", 1, tags=tags)


@contextmanager
def track_duration(metric_name: str, tags: list[str] | None = None) -> Generator[None]:
    """Context manager to track operation duration."""
    statsd = _get_statsd()
    start = time.monotonic()
    try:
        yield
    finally:
        duration = time.monotonic() - start
        statsd.histogram(metric_name, duration, tags=tags or [])


def _estimate_cost(usage: Any, model: str) -> float:
    """Rough cost estimate based on token counts and model pricing."""
    # Prices per 1M tokens (approximate, update as needed)
    pricing = {
        "claude-opus-4-7": {"input": 15.0, "output": 75.0},
        "claude-opus-4-6": {"input": 15.0, "output": 75.0},  # legacy alias
        "claude-sonnet-4-6": {"input": 3.0, "output": 15.0},
        "claude-haiku-4-5": {"input": 0.80, "output": 4.0},
    }
    prices = pricing.get(model, pricing["claude-sonnet-4-6"])
    input_cost = (usage.input_tokens / 1_000_000) * prices["input"]
    output_cost = (usage.output_tokens / 1_000_000) * prices["output"]
    return round(input_cost + output_cost, 6)
