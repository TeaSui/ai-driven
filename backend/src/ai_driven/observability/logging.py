"""Structured JSON logging setup with PII masking and trace context injection."""

from __future__ import annotations

import logging
import sys

from ai_driven.config import Settings
from ai_driven.observability.pii_filter import PIIMaskingFilter
from ai_driven.observability.trace_context import TraceContextFilter


def setup_logging(settings: Settings) -> None:
    """Configure structured JSON logging with PII masking and trace context.

    Every log record will include ``trace_id``, ``span_id``, ``task_id``,
    ``team_id``, and ``agent_type`` fields — even when empty — so the schema
    is consistent and filterable in log aggregators.
    """
    log_level = getattr(logging, settings.log_level.upper(), logging.INFO)
    # Local / non-production environments keep IP addresses unmasked so
    # developers can correlate requests across services; production always
    # masks regardless of log level.
    local_mode = settings.environment.value != "production"

    # Filters — applied to every handler
    pii_filter = PIIMaskingFilter(debug_mode=local_mode)
    trace_filter = TraceContextFilter()

    # Use JSON formatter if python-json-logger is available
    try:
        from pythonjsonlogger.json import JsonFormatter

        handler = logging.StreamHandler(sys.stdout)
        formatter = JsonFormatter(
            fmt=(
                "%(asctime)s %(name)s %(levelname)s %(message)s "
                "%(trace_id)s %(span_id)s %(task_id)s %(team_id)s %(agent_type)s"
            ),
            rename_fields={
                "asctime": "timestamp",
                "levelname": "level",
            },
        )
        handler.setFormatter(formatter)
    except ImportError:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(logging.Formatter(
            "%(asctime)s [%(levelname)s] %(name)s "
            "[trace=%(trace_id)s span=%(span_id)s task=%(task_id)s team=%(team_id)s] "
            "%(message)s"
        ))

    # Order matters: trace context first (adds fields), then PII filter (masks them)
    handler.addFilter(trace_filter)
    handler.addFilter(pii_filter)

    root_logger = logging.getLogger()
    root_logger.handlers.clear()
    root_logger.addHandler(handler)
    root_logger.setLevel(log_level)

    # Also add filters to root logger for any handlers added later
    root_logger.addFilter(trace_filter)
    root_logger.addFilter(pii_filter)

    # Reduce noise from third-party libraries
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("botocore").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)
