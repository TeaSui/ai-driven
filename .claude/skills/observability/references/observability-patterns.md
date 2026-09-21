# General Observability Standards

Platform-agnostic standards for structured logging, metric naming, and alerting.
DataDog-specific implementation is in the parent `SKILL.md`.

## Structured Logging Format

All services MUST emit structured JSON logs. Required fields:

```json
{
  "timestamp": "2026-01-15T10:30:00.000Z",
  "level": "INFO",
  "service": "order-service",
  "env": "prod",
  "version": "1.2.3",
  "correlationId": "req-abc-123",
  "message": "Order created successfully",
  "orderId": "ord-456"
}
```

### Required Fields

| Field | Type | Notes |
|-------|------|-------|
| `timestamp` | ISO 8601 UTC | Always UTC; never local time |
| `level` | enum | `DEBUG` \| `INFO` \| `WARN` \| `ERROR` \| `FATAL` |
| `service` | string | Matches `DD_SERVICE` / service registry name |
| `env` | string | `dev` \| `staging` \| `prod` |
| `version` | string | Semver; matches deployed artifact |
| `correlationId` | string | Request or trace ID; propagated across service calls |
| `message` | string | Human-readable; no PII |

### Forbidden in Logs

- **PII:** names, emails, phone numbers, national IDs, addresses — mask or omit entirely.
- **Payment data:** card numbers, CVV, bank account numbers (even partial).
- **Credentials:** passwords, API keys, tokens, secrets.

When PII context is needed for debugging, log a surrogate key (e.g. `customerId`) and join externally.

### Log Levels

| Level | Use For |
|-------|---------|
| `DEBUG` | Detailed trace for local development; excluded from production by default |
| `INFO` | Normal operations: requests received, orders created, jobs completed |
| `WARN` | Recoverable anomalies: retried operation, deprecated API used, SLA warning |
| `ERROR` | Operation failed, request could not be served, exception caught at boundary |
| `FATAL` | Process-aborting condition; triggers immediate alert |

## Metric Naming Convention

Pattern: `<service_name>.<entity>.<action>[.<outcome>]`

Rules:
- Use `snake_case` throughout.
- Service name prefix is mandatory — never a bare `order.created`.
- Outcome suffix (`failed`, `timeout`, `rejected`) disambiguates error counts from totals.
- Keep cardinality bounded — see tag rules below.

### Examples

```
order_service.order.created              # counter
order_service.order.create.duration      # histogram / timer
order_service.order.create.failed        # counter (error path)
payment_service.payment.authorize.duration
payment_service.payment.authorize.failed
notification_service.email.sent
notification_service.email.bounced
```

### Tag Guidelines

**Good tags** (bounded, low cardinality):

| Tag | Example Values |
|-----|---------------|
| `env` | `prod`, `staging`, `dev` |
| `service` | `order-service`, `payment-service` |
| `version` | `1.2.3` |
| `status` | `success`, `failed`, `timeout` |
| `product_category` | `electronics`, `clothing` |
| `payment_method` | `card`, `wallet`, `bank_transfer` |
| `error_type` | `ValidationError`, `TimeoutError` |
| `region` | `ap-southeast-1` |

**Forbidden tags** (unbounded → cost explosion):

| Tag | Why Forbidden |
|-----|---------------|
| `user_id` | Millions of unique values → millions of time series |
| `order_id` | Same — unbounded cardinality |
| `request_id` / `trace_id` | Use logs for per-request correlation, not metrics |
| `email` | PII + unbounded |

## Alert Severity Levels

| Severity | Page On-Call? | Response SLA | Use For |
|----------|---------------|--------------|---------|
| **P1 — Critical** | Yes, immediately | 15 min | Service down, data loss, payment failures, auth broken, SLA breach |
| **P2 — High** | Yes, 30-min delay | 1 hour | Error rate > 5%, P99 latency > 3s, DLQ depth growing, DB connections exhausted |
| **P3 — Medium** | No (ticket) | Next business day | Non-critical degradation, warning threshold crossed, single-host anomaly |
| **P4 — Low** | No | 3 business days | Informational, capacity planning signals, approaching (not breaching) limits |

### Monitor Naming Convention

```
[<Severity>] <Service> — <What> <Condition>

[P1] order-service — Error rate > 10% for 5 min
[P2] payment-service — P99 latency > 3s for 10 min
[P2] order-service — DLQ order-dlq depth > 100 messages
[P3] auth-service — Login failure rate > 5% (non-paging)
```

## SLO Targets (Baseline)

| Signal | Target | Warning Threshold |
|--------|--------|-------------------|
| HTTP success rate | ≥ 99.5% (30d window) | < 99.9% |
| P99 request latency | ≤ 500 ms | > 300 ms |
| P99 DB query time | ≤ 100 ms | > 50 ms |
| Queue consumer lag | < 1 000 messages | > 500 messages |
| Lambda cold-start P95 | ≤ 3 s | > 2 s |

**Fintech adjustments:** payment and auth services use stricter SLOs:
- Success rate ≥ 99.9% (vs 99.5%)
- P99 latency ≤ 200 ms (vs 500 ms)

These are starting points — override per service based on business criticality.

## Trace Propagation

All services MUST propagate correlation context across calls:

- HTTP: inject `X-Correlation-Id` (or `traceparent` for W3C TraceContext) on outbound requests; extract on inbound.
- Async messages (SQS, Kafka): embed `correlationId` in the message attributes/headers.
- Never generate a new correlation ID mid-flow; only generate at the edge (API gateway or consumer entry point).
