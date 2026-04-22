"""DataDog monitor definitions for the AI-Driven Development Platform.

These definitions are authoritative. Apply them via:
  python infrastructure/monitoring/datadog_monitors.py

Requires DD_API_KEY and DD_APP_KEY environment variables.
Thresholds are configurable via environment variables to allow per-environment tuning.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any


# ---------------------------------------------------------------------------
# Configurable thresholds — override per environment
# ---------------------------------------------------------------------------

def _env_float(key: str, default: float) -> float:
    return float(os.environ.get(key, default))


def _env_int(key: str, default: int) -> int:
    return int(os.environ.get(key, default))


ENVIRONMENT = os.environ.get("DD_ENV", "dev")
DAILY_BUDGET_USD = _env_float("AI_DRIVEN_DAILY_BUDGET_USD", 50.0)

THRESHOLDS = {
    # Error rate: fraction of requests that are errors
    "error_rate_critical": _env_float("ALERT_ERROR_RATE_CRITICAL", 0.01),   # 1 % — P1
    "error_rate_warning": _env_float("ALERT_ERROR_RATE_WARNING", 0.005),    # 0.5 % — warn

    # API latency p95 in milliseconds
    "latency_critical": _env_int("ALERT_LATENCY_CRITICAL_MS", 10_000),      # 10 s — P2
    "latency_warning": _env_int("ALERT_LATENCY_WARNING_MS", 5_000),         # 5 s — warn

    # Daily AI spend as a fraction of budget
    "spend_critical_fraction": _env_float("ALERT_SPEND_CRITICAL_FRACTION", 0.8),   # 80 % — P1
    "spend_warning_fraction": _env_float("ALERT_SPEND_WARNING_FRACTION", 0.6),     # 60 % — warn

    # SQS main queue depth
    "queue_depth_critical": _env_int("ALERT_QUEUE_DEPTH_CRITICAL", 100),    # P2
    "queue_depth_warning": _env_int("ALERT_QUEUE_DEPTH_WARNING", 50),       # warn

    # DLQ depth — any non-zero value is an incident
    "dlq_depth_critical": 0,    # P1 — not configurable; 0 is always correct
}


# ---------------------------------------------------------------------------
# Monitor spec dataclass
# ---------------------------------------------------------------------------

@dataclass
class MonitorSpec:
    name: str
    type: str  # "metric alert", "query alert", etc.
    query: str
    message: str
    tags: list[str]
    priority: int  # 1 = P1 (highest), 5 = P5 (lowest)
    thresholds: dict[str, float]
    notify_no_data: bool = False
    no_data_timeframe: int = 10  # minutes
    evaluation_delay: int = 60   # seconds — avoids flapping on sparse metrics
    extra: dict[str, Any] = field(default_factory=dict)


def build_monitors(env: str = ENVIRONMENT) -> list[MonitorSpec]:
    """Return all monitor specs for the given environment."""
    service_tag = f"service:ai-driven"
    env_tag = f"env:{env}"
    base_tags = [service_tag, env_tag, "team:platform"]

    spend_critical = DAILY_BUDGET_USD * THRESHOLDS["spend_critical_fraction"]
    spend_warning = DAILY_BUDGET_USD * THRESHOLDS["spend_warning_fraction"]

    return [
        # ------------------------------------------------------------------
        # P1 — Error rate
        # ------------------------------------------------------------------
        MonitorSpec(
            name=f"[{env.upper()}] API error rate > {THRESHOLDS['error_rate_critical']:.0%} (P1)",
            type="query alert",
            query=(
                f"avg(last_5m):sum:api.requests.error{{env:{env}}} / "
                f"sum:api.requests.total{{env:{env}}} "
                f"> {THRESHOLDS['error_rate_critical']}"
            ),
            message=(
                "@pagerduty-ai-driven-p1\n"
                "API error rate has exceeded {{threshold}}.\n"
                "Current value: {{value}}\n\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/high-error-rate\n"
                "{{#is_alert}}Firing{{/is_alert}}{{#is_recovery}}Recovered{{/is_recovery}}"
            ),
            tags=base_tags + ["severity:p1", "monitor:error-rate"],
            priority=1,
            thresholds={
                "critical": THRESHOLDS["error_rate_critical"],
                "warning": THRESHOLDS["error_rate_warning"],
            },
            notify_no_data=False,
        ),

        # ------------------------------------------------------------------
        # P2 — API latency p95
        # ------------------------------------------------------------------
        MonitorSpec(
            name=f"[{env.upper()}] API p95 latency > {THRESHOLDS['latency_critical']}ms (P2)",
            type="query alert",
            query=(
                f"avg(last_5m):p95:api.request.duration{{env:{env}}} "
                f"> {THRESHOLDS['latency_critical']}"
            ),
            message=(
                "@slack-ai-driven-alerts\n"
                "API p95 latency has exceeded {{threshold}}ms.\n"
                "Current value: {{value}}ms\n\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/high-latency\n"
                "{{#is_alert}}Firing{{/is_alert}}{{#is_recovery}}Recovered{{/is_recovery}}"
            ),
            tags=base_tags + ["severity:p2", "monitor:latency"],
            priority=2,
            thresholds={
                "critical": float(THRESHOLDS["latency_critical"]),
                "warning": float(THRESHOLDS["latency_warning"]),
            },
            notify_no_data=False,
        ),

        # ------------------------------------------------------------------
        # P1 — Daily AI spend
        # ------------------------------------------------------------------
        MonitorSpec(
            name=f"[{env.upper()}] Daily AI spend > {THRESHOLDS['spend_critical_fraction']:.0%} of budget (P1)",
            type="query alert",
            query=(
                f"sum(last_1d):sum:ai.cost_usd{{env:{env}}} "
                f"> {spend_critical:.4f}"
            ),
            message=(
                "@pagerduty-ai-driven-p1\n"
                f"Daily AI spend has exceeded {THRESHOLDS['spend_critical_fraction']:.0%} "
                f"of the ${DAILY_BUDGET_USD:.2f} daily budget.\n"
                "Current spend: ${{value}}\n\n"
                "Action: Review model usage in the DataDog AI cost dashboard.\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/cost-spike\n"
                "{{#is_alert}}Firing{{/is_alert}}{{#is_recovery}}Recovered{{/is_recovery}}"
            ),
            tags=base_tags + ["severity:p1", "monitor:cost"],
            priority=1,
            thresholds={
                "critical": spend_critical,
                "warning": spend_warning,
            },
            notify_no_data=False,
        ),

        # ------------------------------------------------------------------
        # P2 — Main task queue depth
        # ------------------------------------------------------------------
        MonitorSpec(
            name=f"[{env.upper()}] Task queue depth > {THRESHOLDS['queue_depth_critical']} (P2)",
            type="query alert",
            query=(
                f"avg(last_5m):avg:aws.sqs.approximate_number_of_messages_visible"
                f"{{queuename:ai-driven-coding-tasks,env:{env}}} "
                f"> {THRESHOLDS['queue_depth_critical']}"
            ),
            message=(
                "@slack-ai-driven-alerts\n"
                "Coding task queue depth has exceeded {{threshold}} messages.\n"
                "Current depth: {{value}}\n\n"
                "Possible causes: worker crash, insufficient ECS capacity, or a backlog spike.\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/queue-depth\n"
                "{{#is_alert}}Firing{{/is_alert}}{{#is_recovery}}Recovered{{/is_recovery}}"
            ),
            tags=base_tags + ["severity:p2", "monitor:queue-depth", "queue:coding"],
            priority=2,
            thresholds={
                "critical": float(THRESHOLDS["queue_depth_critical"]),
                "warning": float(THRESHOLDS["queue_depth_warning"]),
            },
            notify_no_data=False,
        ),

        # ------------------------------------------------------------------
        # P1 — DLQ non-empty (coding tasks)
        # ------------------------------------------------------------------
        MonitorSpec(
            name=f"[{env.upper()}] Coding DLQ non-empty (P1)",
            type="query alert",
            query=(
                f"avg(last_5m):avg:aws.sqs.approximate_number_of_messages_visible"
                f"{{queuename:ai-driven-coding-tasks-dlq,env:{env}}} "
                f"> {THRESHOLDS['dlq_depth_critical']}"
            ),
            message=(
                "@pagerduty-ai-driven-p1\n"
                "Messages have appeared in the Coding DLQ — tasks permanently failed after 3 retries.\n"
                "Current DLQ depth: {{value}}\n\n"
                "Action: inspect worker CloudWatch logs and DLQ message bodies immediately.\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/dlq-non-empty\n"
                "{{#is_alert}}Firing{{/is_alert}}{{#is_recovery}}Recovered{{/is_recovery}}"
            ),
            tags=base_tags + ["severity:p1", "monitor:dlq", "queue:coding"],
            priority=1,
            thresholds={"critical": float(THRESHOLDS["dlq_depth_critical"])},
            notify_no_data=False,
        ),

        # ------------------------------------------------------------------
        # P1 — DLQ non-empty (review tasks)
        # ------------------------------------------------------------------
        MonitorSpec(
            name=f"[{env.upper()}] Review DLQ non-empty (P1)",
            type="query alert",
            query=(
                f"avg(last_5m):avg:aws.sqs.approximate_number_of_messages_visible"
                f"{{queuename:ai-driven-review-tasks-dlq,env:{env}}} "
                f"> {THRESHOLDS['dlq_depth_critical']}"
            ),
            message=(
                "@pagerduty-ai-driven-p1\n"
                "Messages have appeared in the Review DLQ — review tasks permanently failed.\n"
                "Current DLQ depth: {{value}}\n\n"
                "Action: inspect worker CloudWatch logs and DLQ message bodies immediately.\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/dlq-non-empty\n"
                "{{#is_alert}}Firing{{/is_alert}}{{#is_recovery}}Recovered{{/is_recovery}}"
            ),
            tags=base_tags + ["severity:p1", "monitor:dlq", "queue:review"],
            priority=1,
            thresholds={"critical": float(THRESHOLDS["dlq_depth_critical"])},
            notify_no_data=False,
        ),

        # ------------------------------------------------------------------
        # P1 — DLQ non-empty (devops alerts)
        # ------------------------------------------------------------------
        MonitorSpec(
            name=f"[{env.upper()}] DevOps DLQ non-empty (P1)",
            type="query alert",
            query=(
                f"avg(last_5m):avg:aws.sqs.approximate_number_of_messages_visible"
                f"{{queuename:ai-driven-devops-alerts-dlq,env:{env}}} "
                f"> {THRESHOLDS['dlq_depth_critical']}"
            ),
            message=(
                "@pagerduty-ai-driven-p1\n"
                "Messages have appeared in the DevOps DLQ — devops alert tasks permanently failed.\n"
                "Current DLQ depth: {{value}}\n\n"
                "Action: inspect worker CloudWatch logs and DLQ message bodies immediately.\n"
                "Runbook: https://docs.ai-driven.internal/runbooks/dlq-non-empty\n"
                "{{#is_alert}}Firing{{/is_alert}}{{#is_recovery}}Recovered{{/is_recovery}}"
            ),
            tags=base_tags + ["severity:p1", "monitor:dlq", "queue:devops"],
            priority=1,
            thresholds={"critical": float(THRESHOLDS["dlq_depth_critical"])},
            notify_no_data=False,
        ),
    ]


# ---------------------------------------------------------------------------
# Apply monitors via DataDog API
# ---------------------------------------------------------------------------

def apply_monitors(env: str = ENVIRONMENT, dry_run: bool = False) -> None:
    """Create or update DataDog monitors.

    Set DD_API_KEY and DD_APP_KEY before calling this.
    Pass dry_run=True to print the monitor payloads without sending them.
    """
    from datadog_api_client import ApiClient, Configuration
    from datadog_api_client.v1.api.monitors_api import MonitorsApi
    from datadog_api_client.v1.model.monitor import Monitor
    from datadog_api_client.v1.model.monitor_options import MonitorOptions
    from datadog_api_client.v1.model.monitor_thresholds import MonitorThresholds

    configuration = Configuration()
    configuration.api_key["apiKeyAuth"] = os.environ["DD_API_KEY"]
    configuration.api_key["appKeyAuth"] = os.environ["DD_APP_KEY"]

    monitors = build_monitors(env)

    with ApiClient(configuration) as api_client:
        api_instance = MonitorsApi(api_client)

        for spec in monitors:
            thresholds = MonitorThresholds(**{
                k: v for k, v in spec.thresholds.items()
            })
            options = MonitorOptions(
                thresholds=thresholds,
                notify_no_data=spec.notify_no_data,
                no_data_timeframe=spec.no_data_timeframe,
                evaluation_delay=spec.evaluation_delay,
            )
            monitor = Monitor(
                name=spec.name,
                type=spec.type,
                query=spec.query,
                message=spec.message,
                tags=spec.tags,
                priority=spec.priority,
                options=options,
            )

            if dry_run:
                import json
                print(f"DRY RUN — would create monitor: {spec.name}")
                print(json.dumps(monitor.to_dict(), indent=2, default=str))
                print()
            else:
                result = api_instance.create_monitor(monitor)
                print(f"Created monitor id={result.id}: {spec.name}")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Apply DataDog monitors")
    parser.add_argument("--env", default=ENVIRONMENT, help="Target environment (dev/staging/prod)")
    parser.add_argument("--dry-run", action="store_true", help="Print payloads without creating")
    args = parser.parse_args()

    apply_monitors(env=args.env, dry_run=args.dry_run)
