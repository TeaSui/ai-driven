# DevOps Agent — Incident Response Flow

DataDog alert triggers investigation: query logs/metrics, correlate with deployments, generate RCA, create Jira ticket, notify on-call.

```mermaid
sequenceDiagram
    autonumber
    participant DD as DataDog Alert
    participant API as FastAPI
    participant DA as DevOps Agent
    participant Claude as Claude API
    participant DDapi as DataDog API
    participant GH as GitHub API
    participant Jira as Jira API
    participant Slack as Slack API

    Note over DD,Slack: Scenario: DataDog Alert → Triage → RCA → Notify

    DD->>API: POST /webhooks/datadog/alert
    Note over API: High latency on payments-api
    API->>DA: Dispatch DevOps Agent

    DA->>Claude: Assess severity from alert payload
    Claude-->>DA: P2 - elevated latency, not down

    rect rgb(255, 243, 224)
        Note over DA,DDapi: Phase 1: Investigation
        DA->>DDapi: query_datadog_logs("payments-api error")
        DDapi-->>DA: 847 errors in last 15min
        DA->>DDapi: query_datadog_metrics("latency.p99")
        DDapi-->>DA: p99 jumped from 200ms → 1.2s
        DA->>DDapi: get_service_health("payments-api")
        DDapi-->>DA: Error rate: 12%, Uptime: 99.1%
    end

    rect rgb(227, 242, 253)
        Note over DA,GH: Phase 2: Correlation
        DA->>GH: get_recent_deployments("payments-api")
        GH-->>DA: PR #2415 merged 22min ago
        DA->>Claude: Correlate: deployment + error spike
        Claude-->>DA: Root cause: Redis connection pool exhausted after session store migration
    end

    rect rgb(252, 228, 236)
        Note over DA,Slack: Phase 3: Response
        DA->>GH: suggest_rollback(target: "pre-#2415")
        DA->>Jira: create_jira_incident(P2, "Redis pool exhaustion")
        Jira-->>DA: INC-891 created
        DA->>Slack: notify_oncall("P2 incident INC-891")
        Slack-->>DA: Alert sent to #oncall
    end

    DA->>Claude: Generate RCA summary
    Claude-->>DA: Structured RCA with evidence + remediation
    DA->>API: Store rca_json in incidents table
```

## DevOps Agent Tools

| Tool | Purpose |
|------|---------|
| `query_datadog_logs` | Search logs by query string and time range (ISO 8601) |
| `query_datadog_metrics` | Fetch metric timeseries (Unix epoch seconds) |
| `get_service_health` | APM health: error rate, latency, uptime |
| `get_recent_deployments` | List recent merged PRs as deployment markers |
| `create_jira_incident` | Create Bug ticket with priority + labels |
| `notify_oncall` | Send Slack alert to on-call channel |
| `suggest_rollback` | Identify last safe deployment before incident |

## RCA Output Structure

```json
{
  "root_cause": "Redis connection pool exhaustion after session store migration",
  "confidence": "high",
  "evidence": [
    "847 errors in 15min window correlating with deployment",
    "p99 latency spike from 200ms to 1.2s",
    "PR #2415 (session store migration) merged 22min before alert"
  ],
  "remediation": [
    "Immediate: Increase Redis maxclients and connection pool size",
    "Short-term: Add connection pool monitoring alert",
    "Long-term: Implement connection pooling with PgBouncer pattern"
  ],
  "rollback_target": "commit before PR #2415 merge"
}
```

## Severity Classification

Canonical taxonomy: **P1, P2, P3, P4 (uppercase)** — must match `features/04-ai-devops-assistant.md`.

| Severity | Criteria | Auto-actions |
|----------|----------|-------------|
| **P1** — Critical | Service down, data loss risk, >50% errors | Immediate Slack + Jira + page on-call + suggest rollback |
| **P2** — High | Degraded performance, 5-50% errors, feature broken | Slack + Jira + investigate + suggest rollback |
| **P3** — Medium | Minor degradation, <5% errors | Jira + Slack |
| **P4** — Low | Warning threshold, no user impact | Jira only |

> Note: Review-comment severity (`critical | warning | suggestion`) is a **different axis** — do not conflate with incident severity.
