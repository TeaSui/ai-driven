"""DataDog APM tracing setup."""

from __future__ import annotations

import logging

from ai_driven.config import Settings

logger = logging.getLogger(__name__)


def setup_tracing(settings: Settings) -> None:
    """Configure DataDog APM tracing."""
    if not settings.datadog_enabled:
        logger.info("DataDog tracing disabled (AI_DRIVEN_DATADOG_ENABLED=false)")
        return

    try:
        from ddtrace import config as dd_config, patch_all, tracer

        dd_config.service = settings.dd_service
        dd_config.env = settings.dd_env

        # Auto-patch supported libraries
        patch_all(
            fastapi=True,
            httpx=True,
            sqlalchemy=True,
            redis=True,
            botocore=True,
            logging=True,
        )

        logger.info(f"DataDog tracing enabled: service={settings.dd_service}, env={settings.dd_env}")
    except ImportError:
        logger.warning("ddtrace not installed — tracing disabled")
