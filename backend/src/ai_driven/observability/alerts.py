"""Programmatic DataDog alert definitions for the AI-Driven Development Platform.

This module mirrors the canonical monitor definitions in
``infrastructure/monitoring/datadog_monitors.py`` but is importable from
application code so tests and tooling can inspect or validate thresholds
without needing CDK/Terraform dependencies.

Usage (apply monitors from application code)::

    from ai_driven.observability.alerts import AlertRegistry

    registry = AlertRegistry.for_env("production")
    registry.apply()          # requires DD_API_KEY / DD_APP_KEY in environment
    registry.dry_run()        # print payloads without sending

Threshold environment variables
--------------------------------
All thresholds are configurable so startup and production environments can
differ without code changes:

- ``ALERT_ERROR_RATE_CRITICAL``   (default: 0.01  — 1 %)
- ``ALERT_ERROR_RATE_WARNING``    (default: 0.005 — 0.5 %)
- ``ALERT_LATENCY_CRITICAL_MS``   (default: 10000 — 10 s)
- ``ALERT_LATENCY_WARNING_MS``    (default: 5000  — 5 s)
- ``ALERT_SPEND_CRITICAL_FRACTION`` (default: 0.8 — 80 % of budget)
- ``ALERT_SPEND_WARNING_FRACTION``  (default: 0.6 — 60 % of budget)
- ``ALERT_QUEUE_DEPTH_CRITICAL``  (default: 100)
- ``ALERT_QUEUE_DEPTH_WARNING``   (default: 50)
- ``AI_DRIVEN_DAILY_BUDGET_USD``  (default: 50.0)
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Configurable thresholds
# ---------------------------------------------------------------------------

def _env_float(key: str, default: float) -> float:
    return float(os.environ.get(key, default))


def _env_int(key: str, default: int) -> int:
    return int(os.environ.get(key, default))


@dataclass(frozen=True)
class AlertThresholds:
    """All configurable alert thresholds in one place."""

    error_rate_critical: float = field(
        default_factory=lambda: _env_float("ALERT_ERROR_RATE_CRITICAL", 0.01)
    )
    error_rate_warning: float = field(
        default_factory=lambda: _env_float("ALERT_ERROR_RATE_WARNING", 0.005)
    )
    latency_critical_ms: int = field(
        default_factory=lambda: _env_int("ALERT_LATENCY_CRITICAL_MS", 10_000)
    )
    latency_warning_ms: int = field(
        default_factory=lambda: _env_int("ALERT_LATENCY_WARNING_MS", 5_000)
    )
    spend_critical_fraction: float = field(
        default_factory=lambda: _env_float("ALERT_SPEND_CRITICAL_FRACTION", 0.8)
    )
    spend_warning_fraction: float = field(
        default_factory=lambda: _env_float("ALERT_SPEND_WARNING_FRACTION", 0.6)
    )
    queue_depth_critical: int = field(
        default_factory=lambda: _env_int("ALERT_QUEUE_DEPTH_CRITICAL", 100)
    )
    queue_depth_warning: int = field(
        default_factory=lambda: _env_int("ALERT_QUEUE_DEPTH_WARNING", 50)
    )
    daily_budget_usd: float = field(
        default_factory=lambda: _env_float("AI_DRIVEN_DAILY_BUDGET_USD", 50.0)
    )

    @property
    def spend_critical_usd(self) -> float:
        return self.daily_budget_usd * self.spend_critical_fraction

    @property
    def spend_warning_usd(self) -> float:
        return self.daily_budget_usd * self.spend_warning_fraction


# ---------------------------------------------------------------------------
# Alert definition
# ---------------------------------------------------------------------------

@dataclass
class AlertDefinition:
    """A single DataDog monitor definition."""

    name: str
    query: str
    message: str
    priority: int          # 1 (P1/highest) – 5 (P5/lowest)
    thresholds: dict[str, float]
    tags: list[str] = field(default_factory=list)
    monitor_type: str = "query alert"
    notify_no_data: bool = False
    no_data_timeframe: int = 10   # minutes
    evaluation_delay: int = 60    # seconds

    def to_datadog_payload(self) -> dict[str, Any]:
        """Return a dict suitable for the DataDog Monitors API."""
        return {
            "name": self.name,
            "type": self.monitor_type,
            "query": self.query,
            "message": self.message,
            "priority": self.priority,
            "tags": self.tags,
            "options": {
                "thresholds": self.thresholds,
                "notify_no_data": self.notify_no_data,
                "no_data_timeframe": self.no_data_timeframe,
                "evaluation_delay": self.evaluation_delay,
            },
        }


# ---------------------------------------------------------------------------
# Alert registry
# ---------------------------------------------------------------------------

class AlertRegistry:
    """Builds and optionally applies all DataDog monitors for an environment."""

    def __init__(self, env: str, thresholds: AlertThresholds | None = None) -> None:
        self.env = env
        self.thresholds = thresholds or AlertThresholds()
        self._alerts: list[AlertDefinition] | None = None

    @classmethod
    def for_env(cls, env: str | None = None) -> AlertRegistry:
        """Create a registry for the given environment (defaults to DD_ENV)."""
        return cls(env=env or os.environ.get("DD_ENV", "dev"))

    def build(self) -> list[AlertDefinition]:
        """Build and return all alert definitions. Result is cached."""
        if self._alerts is not None:
            return self._alerts
        self._alerts = self._build_all()
        return self._alerts

    def _base_tags(self) -> list[str]:
        return ["service:ai-driven", f"env:{self.env}", "team:platform"]

    def _build_all(self) -> list[AlertDefinition]:
        t = self.thresholds
        env = self.env
        base = self._base_tags()

        alerts: list[AlertDefinition] = []

        # --- Error rate (P1) ---
        alerts.append(AlertDefinition(
            name=f"[{env.upper()}] API error rate > {t.error_rate_critical:.0%} (P1)",
            query=(
                f"avg(last_5m):sum:api.requests.error{{env:{env}}} / "
                f"sum:api.requests.total{{env:{env}}} "
                f"> {t.error_rate_critical}"
            ),
            message=(
                "@pagerduty-ai-driven-p1\n"
                "API error rate has exceeded {{threshold}}.\n"
                "Current value: {{value}}\n\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/high-error-rate"
            ),
            priority=1,
            thresholds={"critical": t.error_rate_critical, "warning": t.error_rate_warning},
            tags=base + ["severity:p1", "monitor:error-rate"],
        ))

        # --- API latency p95 (P2) ---
        alerts.append(AlertDefinition(
            name=f"[{env.upper()}] API p95 latency > {t.latency_critical_ms}ms (P2)",
            query=(
                f"avg(last_5m):p95:api.request.duration{{env:{env}}} "
                f"> {t.latency_critical_ms}"
            ),
            message=(
                "@slack-ai-driven-alerts\n"
                "API p95 latency has exceeded {{threshold}}ms.\n"
                "Current value: {{value}}ms\n\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/high-latency"
            ),
            priority=2,
            thresholds={
                "critical": float(t.latency_critical_ms),
                "warning": float(t.latency_warning_ms),
            },
            tags=base + ["severity:p2", "monitor:latency"],
        ))

        # --- Daily AI spend (P1) ---
        alerts.append(AlertDefinition(
            name=(
                f"[{env.upper()}] Daily AI spend > "
                f"{t.spend_critical_fraction:.0%} of budget (P1)"
            ),
            query=(
                f"sum(last_1d):sum:ai.cost_usd{{env:{env}}} "
                f"> {t.spend_critical_usd:.4f}"
            ),
            message=(
                "@pagerduty-ai-driven-p1\n"
                f"Daily AI spend has exceeded {t.spend_critical_fraction:.0%} "
                f"of the ${t.daily_budget_usd:.2f} daily budget.\n"
                "Current spend: ${{value}}\n\n"
                "Action: review model usage in the DataDog AI cost dashboard.\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/cost-spike"
            ),
            priority=1,
            thresholds={"critical": t.spend_critical_usd, "warning": t.spend_warning_usd},
            tags=base + ["severity:p1", "monitor:cost"],
        ))

        # --- Task queue depth (P2) ---
        alerts.append(AlertDefinition(
            name=f"[{env.upper()}] Task queue depth > {t.queue_depth_critical} (P2)",
            query=(
                f"avg(last_5m):avg:aws.sqs.approximate_number_of_messages_visible"
                f"{{queuename:ai-driven-coding-tasks,env:{env}}} "
                f"> {t.queue_depth_critical}"
            ),
            message=(
                "@slack-ai-driven-alerts\n"
                "Coding task queue depth has exceeded {{threshold}} messages.\n"
                "Current depth: {{value}}\n\n"
                "Possible causes: worker crash, insufficient ECS capacity, or a backlog spike.\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/queue-depth"
            ),
            priority=2,
            thresholds={
                "critical": float(t.queue_depth_critical),
                "warning": float(t.queue_depth_warning),
            },
            tags=base + ["severity:p2", "monitor:queue-depth", "queue:coding"],
        ))

        # --- DLQ monitors (P1, one per queue) ---
        dlq_queues = [
            ("coding-tasks-dlq", "Coding DLQ", "queue:coding"),
            ("review-tasks-dlq", "Review DLQ", "queue:review"),
            ("devops-alerts-dlq", "DevOps DLQ", "queue:devops"),
        ]
        for queue_name, label, queue_tag in dlq_queues:
            alerts.append(AlertDefinition(
                name=f"[{env.upper()}] {label} non-empty (P1)",
                query=(
                    f"avg(last_5m):avg:aws.sqs.approximate_number_of_messages_visible"
                    f"{{queuename:ai-driven-{queue_name},env:{env}}} > 0"
                ),
                message=(
                    "@pagerduty-ai-driven-p1\n"
                    f"Messages have appeared in {label} — tasks permanently failed after 3 retries.\n"
                    "Current DLQ depth: {{value}}\n\n"
                    "Action: inspect worker CloudWatch logs and DLQ message bodies immediately.\n"
                    "Runbook: https://docs.ai-driven.internal/runbooks/dlq-non-empty"
                ),
                priority=1,
                thresholds={"critical": 0.0},
                tags=base + ["severity:p1", "monitor:dlq", queue_tag],
            ))

        return alerts

    def dry_run(self) -> None:
        """Print all monitor payloads without sending to DataDog."""
        import json

        for alert in self.build():
            print(f"--- {alert.name} ---")
            print(json.dumps(alert.to_datadog_payload(), indent=2))
            print()

    def apply(self) -> list[dict[str, Any]]:
        """Create monitors via the DataDog API.

        Requires DD_API_KEY and DD_APP_KEY to be set in the environment.
        Returns a list of created monitor dicts (with ``id`` fields).
        """
        try:
            from datadog_api_client import ApiClient, Configuration
            from datadog_api_client.v1.api.monitors_api import MonitorsApi
            from datadog_api_client.v1.model.monitor import Monitor
            from datadog_api_client.v1.model.monitor_options import MonitorOptions
            from datadog_api_client.v1.model.monitor_thresholds import MonitorThresholds
        except ImportError as exc:
            raise RuntimeError(
                "datadog-api-client is not installed. "
                "Add it to your dependencies to use apply()."
            ) from exc

        configuration = Configuration()
        configuration.api_key["apiKeyAuth"] = os.environ["DD_API_KEY"]
        configuration.api_key["appKeyAuth"] = os.environ["DD_APP_KEY"]

        created = []
        with ApiClient(configuration) as api_client:
            api_instance = MonitorsApi(api_client)
            for alert in self.build():
                thresholds = MonitorThresholds(**alert.thresholds)
                options = MonitorOptions(
                    thresholds=thresholds,
                    notify_no_data=alert.notify_no_data,
                    no_data_timeframe=alert.no_data_timeframe,
                    evaluation_delay=alert.evaluation_delay,
                )
                monitor = Monitor(
                    name=alert.name,
                    type=alert.monitor_type,
                    query=alert.query,
                    message=alert.message,
                    tags=alert.tags,
                    priority=alert.priority,
                    options=options,
                )
                result = api_instance.create_monitor(monitor)
                logger.info("Created DataDog monitor id=%s: %s", result.id, alert.name)
                created.append(result.to_dict())

        return created
