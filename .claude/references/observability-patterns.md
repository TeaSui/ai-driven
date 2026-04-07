# Observability Rules

## Structured Logging

**Required fields on every log entry:**
```json
{
  "timestamp": "2026-03-30T10:00:00.000Z",
  "level": "INFO",
  "service": "order-processor",
  "environment": "prod",
  "correlationId": "req-abc123",       // trace requests across services
  "userId": "user-xyz",                 // when user context available (mask PII)
  "requestId": "lambda-request-id",     // platform request ID
  "message": "Order processed",
  "durationMs": 145,
  "data": { }                           // structured context — no PII
}
```

**Log levels:**
- `ERROR`: Exceptions, failures requiring attention, SLA breaches
- `WARN`: Degraded state, retries, fallbacks, approaching limits
- `INFO`: Key business events (order created, payment processed, user registered)
- `DEBUG`: Detailed execution flow — disabled in production by default

**Never log:**
- Passwords, PINs, tokens, API keys
- Full PII (see data-privacy-patterns.md for masking rules)
- Stack traces in user-facing responses (server-side only)
- High-volume debug logs in production (cost and noise)

## Metrics

**Standard metrics to emit for every service:**

| Metric | Type | Description |
|--------|------|-------------|
| `request.count` | Counter | Total requests |
| `request.duration_ms` | Histogram | Latency distribution |
| `request.error.count` | Counter | Errors by type |
| `request.success_rate` | Gauge | % successful |

**AWS Lambda (Embedded Metrics Format):**
```typescript
import { MetricUnits, Metrics } from '@aws-lambda-powertools/metrics';
const metrics = new Metrics({ namespace: 'AiDriven', serviceName: 'order-processor' });

metrics.addMetric('OrdersProcessed', MetricUnits.Count, 1);
metrics.addMetric('ProcessingDuration', MetricUnits.Milliseconds, durationMs);
metrics.publishStoredMetrics();
```

**Metric naming convention:**
- Format: `<service>.<action>.<outcome>` (snake_case)
- Examples: `order.create.success`, `payment.authorize.failure`, `kyc.check.duration_ms`
- Include dimensions: `environment`, `region`, `service`

## Distributed Tracing

- Every request must carry a `correlationId` (X-Request-ID header or generated UUID)
- Propagate correlation ID through: Lambda → SQS → Step Functions → Lambda
- AWS X-Ray enabled on all Lambda functions and API Gateway
- Trace sampling: 100% for errors, 5% for success in production
- Spans must include: service name, operation, duration, status, key attributes

```typescript
// Correlation ID propagation pattern
const correlationId = event.headers?.['x-correlation-id'] ?? crypto.randomUUID();
const childLogger = logger.createChild({ correlationId });
```

## Alerting Standards

**Alert severity levels:**
- **P1 (Critical)**: Service down, data loss risk, security breach → Page on-call immediately
- **P2 (High)**: Degraded performance, error rate elevated → Alert within 5 minutes
- **P3 (Medium)**: Unusual patterns, approaching limits → Alert within 30 minutes
- **P4 (Low)**: Informational, trends → Daily digest

**Required alarms for every production service:**
```typescript
// Error rate alarm
fn.metricErrors({ period: Duration.minutes(5) })
  .createAlarm(stack, `${service}-Errors`, {
    threshold: 5,
    evaluationPeriods: 2,
    alarmDescription: 'P2: Lambda error rate elevated',
  });

// Latency alarm
fn.metricDuration({ statistic: 'p99', period: Duration.minutes(5) })
  .createAlarm(stack, `${service}-Latency-P99`, {
    threshold: 5000, // 5 seconds
    evaluationPeriods: 3,
    alarmDescription: 'P3: P99 latency exceeds 5s',
  });

// DLQ depth
dlq.metricApproximateNumberOfMessagesVisible()
  .createAlarm(stack, `${service}-DLQ-Depth`, {
    threshold: 1,
    evaluationPeriods: 1,
    alarmDescription: 'P2: Messages in DLQ — investigate failures',
  });
```

**Alert fatigue prevention:**
- No alert without a runbook
- Alert on symptoms (user impact), not causes
- Group related alerts
- Set meaningful thresholds — not "alert on any error"

## Dashboards

**Minimum dashboard per service:**
1. Request rate and error rate (side-by-side)
2. Latency P50 / P95 / P99
3. Lambda duration and cold start rate
4. DynamoDB consumed RCU/WCU vs provisioned
5. SQS queue depth and DLQ depth
6. Cost per hour (custom metric from billing)

**Dashboard naming:** `<environment>-<service>-operations`

## Health Checks

**Every API endpoint must have:**
```typescript
// GET /health → 200 with service status
{
  "status": "healthy",
  "version": "1.2.3",
  "timestamp": "2026-03-30T10:00:00Z",
  "dependencies": {
    "database": "healthy",
    "queue": "healthy",
    "external-api": "degraded"
  }
}
```

**Liveness vs Readiness:**
- Liveness: Is the process running? (simple ping)
- Readiness: Can the service handle traffic? (check dependencies)

## Runbook Requirements

Every P1/P2 alarm must link to a runbook with:
1. What this alarm means
2. Immediate triage steps
3. Common root causes
4. Remediation steps
5. Escalation path

Store runbooks in: Confluence under `Operations > Runbooks > <service>`

## Cost Observability

- Tag all AWS resources: `Project`, `Environment`, `Service`, `Team`
- Set AWS Budgets alerts: 80% of monthly budget → email, 100% → page
- Review cost per service weekly using Cost Explorer
- Target: cost per transaction trending down quarter-over-quarter
