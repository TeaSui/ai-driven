---
name: observability
description: |
  DataDog observability: APM instrumentation, custom metrics (StatsD/DogStatsD), log correlation with traces,
  dashboard design, monitor/alert configuration, and DataBricks pipeline monitoring.
  Use when: instrumenting services with DataDog, creating dashboards or monitors, setting up log pipelines,
  correlating traces with logs, or monitoring DataBricks jobs.
  Triggers on: DataDog, DD, APM, traces, custom metrics, DogStatsD, monitors, dashboard, DataBricks monitoring,
  log correlation, observability, alerting, SLO.
  Complements references/observability-patterns.md which covers general standards (log format, metric naming, alert severity).
---

# DataDog Observability

General observability standards (structured logging format, metric naming convention, alert severity levels)
are defined in `references/observability-patterns.md`. This skill covers DataDog-specific implementation.

## APM Setup

### Spring Boot

```yaml
# application.yml
management:
  tracing:
    enabled: true
    sampling.probability: 1.0  # 100% in dev/staging, 0.1 in prod

# dd-java-agent handles most instrumentation automatically.
# Run with: java -javaagent:/path/to/dd-java-agent.jar -jar app.jar
```

```properties
# datadog.properties or env vars
dd.service=order-service
dd.env=prod
dd.version=1.2.3
dd.trace.analytics.enabled=true
dd.logs.injection=true          # inject trace/span IDs into logs
dd.trace.db.client.split-by-instance=true
```

**Unified Service Tagging** — always set these three:
- `dd.service` (or `DD_SERVICE`)
- `dd.env` (or `DD_ENV`)
- `dd.version` (or `DD_VERSION`)

These power the Service Catalog, deployments tracking, and version comparison.

### Go — dd-trace-go v2

Read `go.mod`/`go.sum` and the installed release's Go/toolchain requirements first. Core and integration packages have distinct v2 module paths. New instrumentation uses v2; v1 reached vendor EOL on 2025-12-31. Keep a v1 service pinned only as a documented migration exception, not a new-project default. See [support policy](https://docs.datadoghq.com/tracing/trace_collection/compatibility/go/) and [migration guide](https://docs.datadoghq.com/tracing/trace_collection/custom_instrumentation/go/migration/).

```go
package main

import (
    "log/slog"
    "net/http"
    "time"

    httptrace "github.com/DataDog/dd-trace-go/contrib/net/http/v2"
    "github.com/DataDog/dd-trace-go/v2/ddtrace/tracer"
)

func main() {
    if err := tracer.Start(
        tracer.WithService("order-service"),
        tracer.WithEnv("prod"),
        tracer.WithServiceVersion("1.2.3"),
    ); err != nil {
        slog.Error("Tracer initialization failed", "category", "tracer_start")
        return // choose an explicit startup policy for the application
    }
    defer tracer.Stop()

    mux := httptrace.NewServeMux()
    mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
        w.WriteHeader(http.StatusNoContent)
    })
    server := &http.Server{Addr: ":8080", Handler: mux, ReadHeaderTimeout: 5 * time.Second}
    if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
        slog.Error("HTTP server stopped", "category", "http_serve")
    }
}
```

Add the service's shutdown lifecycle and real handlers. This is a source-checked v2 integration example, not a compiled/deployed service; no dependency upgrade is implied. Verify the installed integration's [HTTP API](https://pkg.go.dev/github.com/DataDog/dd-trace-go/contrib/net/http/v2) and run repository tests with already installed modules before adoption.

### Custom Spans

```java
// Java — add custom spans for business operations
import datadog.trace.api.Trace;

@Service
public class PaymentService {

    @Trace(operationName = "payment.authorize", resourceName = "authorize")
    public PaymentResult authorize(PaymentRequest request) {
        // Automatically creates a child span under the current trace
        Span span = GlobalTracer.get().activeSpan();
        span.setTag("payment.amount", request.getAmount());
        span.setTag("payment.currency", request.getCurrency());
        span.setTag("payment.provider", "stripe");
        // ...
    }
}
```

```go
// Go — custom spans
func (s *PaymentService) Authorize(ctx context.Context, req PaymentRequest) (*PaymentResult, error) {
    span, ctx := tracer.StartSpanFromContext(ctx, "payment.authorize",
        tracer.ResourceName("authorize"),
        tracer.Tag("payment.amount", req.Amount),
        tracer.Tag("payment.currency", req.Currency),
    )
    defer span.Finish()

    result, err := s.provider.Charge(ctx, req)
    if err != nil {
        span.SetTag("error", true)
        span.SetTag("error.type", "provider_failure") // sanitized category; no raw response/error text
        return nil, err
    }
    return result, nil
}
```

## Custom Metrics

### DogStatsD (Preferred for Application Metrics)

```java
// Java — using Micrometer with DataDog registry
@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config()
            .commonTags("service", "order-service", "env", System.getenv("DD_ENV"));
    }
}

@Service
public class OrderService {

    private final MeterRegistry metrics;

    public OrderResponse createOrder(CreateOrderRequest req) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            Order order = processOrder(req);
            metrics.counter("order.created",
                "product_category", order.getCategory(),
                "payment_method", order.getPaymentMethod()
            ).increment();
            return toResponse(order);
        } catch (Exception e) {
            metrics.counter("order.create.failed",
                "error_type", e.getClass().getSimpleName()
            ).increment();
            throw e;
        } finally {
            sample.stop(metrics.timer("order.create.duration"));
        }
    }
}
```

```go
// Go — using DogStatsD client
import "github.com/DataDog/datadog-go/v5/statsd"

var dd *statsd.Client

func init() {
    var err error
    dd, err = statsd.New("127.0.0.1:8125",
        statsd.WithNamespace("order_service."),
        statsd.WithTags([]string{"env:" + os.Getenv("DD_ENV")}),
    )
    if err != nil {
        log.Fatal(err)
    }
}

func (s *OrderService) CreateOrder(ctx context.Context, req CreateOrderRequest) (*Order, error) {
    start := time.Now()
    defer func() {
        dd.Timing("order.create.duration", time.Since(start), nil, 1)
    }()

    order, err := s.processOrder(ctx, req)
    if err != nil {
        dd.Incr("order.create.failed", []string{"error:" + errorType(err)}, 1)
        return nil, err
    }

    dd.Incr("order.created", []string{"category:" + order.Category}, 1)
    return order, nil
}
```

### Metric Types and When to Use Them

| DD Type | Use For | Example |
|---------|---------|---------|
| **Count** | Events that happen (increment) | `order.created`, `payment.failed` |
| **Gauge** | Current value at a point in time | `queue.depth`, `active.connections` |
| **Histogram** | Distribution of values | `request.duration`, `payload.size` |
| **Distribution** | Like histogram but aggregated across hosts | `order.total.amount` |
| **Rate** | Events per second | `requests.per_second` |

### Metric Naming (Aligns with observability-patterns.md)

```
<service>.<entity>.<action>[.<outcome>]

order_service.order.created              # count
order_service.order.create.duration      # histogram
order_service.order.create.failed        # count with error tag
order_service.payment.authorize.duration # histogram
```

**Tag wisely:** Tags create time series. `status_code:200` is fine (bounded). `user_id:*` is NOT (unbounded cardinality = cost explosion).

## Log Correlation with Traces

### Automatic (dd-java-agent with dd.logs.injection=true)

```json
{
  "timestamp": "2026-01-15T10:30:00.000Z",
  "level": "INFO",
  "service": "order-service",
  "message": "Order created",
  "dd.trace_id": "1234567890123456789",
  "dd.span_id": "9876543210987654321",
  "dd.service": "order-service",
  "dd.env": "prod",
  "dd.version": "1.2.3",
  "order_id": "ord-456",
  "total": 200.00
}
```

### Manual (Go)

```go
import (
    "context"
    "log/slog"
    "strconv"

    "github.com/DataDog/dd-trace-go/v2/ddtrace/tracer"
)

func logWithTrace(ctx context.Context, logger *slog.Logger, msg string, attrs ...slog.Attr) {
    span, ok := tracer.SpanFromContext(ctx)
    if ok {
        attrs = append(attrs,
            // Decimal lower-64 trace ID for the existing correlation pipeline.
            slog.String("dd.trace_id", strconv.FormatUint(span.Context().TraceIDLower(), 10)),
            slog.String("dd.span_id", strconv.FormatUint(span.Context().SpanID(), 10)),
        )
    }
    logger.LogAttrs(ctx, slog.LevelInfo, msg, attrs...)
}
```

In v2 `TraceID()` is a full 128-bit hexadecimal **string**; `TraceIDLower()` is `uint64`. Do not wrap `TraceID()` in `slog.Uint64`. The example deliberately emits decimal lower-64 and span IDs as strings for a compatible correlation pipeline. If the project uses full 128-bit log correlation, use `slog.String("dd.trace_id", span.Context().TraceID())` and verify that its Datadog pipeline accepts the full-ID format; do not truncate implicitly. See [v2 context methods](https://pkg.go.dev/github.com/DataDog/dd-trace-go/v2/ddtrace/tracer#SpanContext) and [log correlation](https://docs.datadoghq.com/tracing/other_telemetry/connect_logs_and_traces/go/). `msg`/`attrs` must already be allowlisted; the helper does not sanitize arbitrary payloads.

### Log Pipeline (DataDog)

Configure a DD log pipeline to:
1. Parse JSON logs (built-in JSON parser)
2. Remap `level` to DD's `status` attribute
3. Remap `correlationId` to a facet for searching
4. Add `service`, `env`, `version` from log attributes or DD agent tags
5. Exclude DEBUG logs in production (volume/cost)

## Monitors (Alerts)

### Monitor Types

| Type | Use For | Example |
|------|---------|---------|
| **Metric** | Threshold on any metric | Error rate > 5% for 5 min |
| **APM** | Trace-based conditions | P99 latency > 2s, error rate per endpoint |
| **Log** | Log pattern detection | Count of "payment failed" logs > 10 in 5 min |
| **Composite** | Combine multiple monitors | High error rate AND high latency = P1 |
| **Anomaly** | Statistical deviation | Request rate dropped 3 sigma below normal |
| **SLO** | Burn rate alerts | Error budget consuming too fast |

### Monitor Configuration Patterns

```python
# Terraform/DataDog provider pattern for monitors
# High error rate
resource "datadog_monitor" "order_error_rate" {
  name    = "[order-service] High Error Rate"
  type    = "metric alert"
  query   = "sum(last_5m):sum:order_service.order.create.failed{env:prod}.as_rate() / sum:order_service.order.created{env:prod}.as_rate() > 0.05"
  message = <<-EOT
    Order creation error rate is above 5%.
    
    Runbook: https://wiki.example.com/runbooks/order-error-rate
    
    @slack-oncall-backend @pagerduty-backend-p2
  EOT

  monitor_thresholds {
    critical = 0.05
    warning  = 0.02
  }

  notify_no_data    = true
  no_data_timeframe = 10
  renotify_interval = 30
  tags              = ["service:order-service", "env:prod", "team:backend"]
}
```

### SLO Definition

```python
# 99.9% availability SLO
resource "datadog_service_level_objective" "order_availability" {
  name = "[order-service] Order Creation Availability"
  type = "metric"

  query {
    numerator   = "sum:order_service.order.created{env:prod}.as_count()"
    denominator = "sum:order_service.order.created{env:prod}.as_count() + sum:order_service.order.create.failed{env:prod}.as_count()"
  }

  thresholds {
    timeframe = "30d"
    target    = 99.9
    warning   = 99.95
  }

  tags = ["service:order-service", "env:prod"]
}
```

### Alert Anti-Patterns
1. **Alert on every error** — alert on error *rate*, not individual errors. Single errors are noise.
2. **No runbook link** — every P1/P2 monitor must link to a runbook.
3. **Missing recovery notification** — configure `notify_no_data` and recovery messages.
4. **Unbounded tags in monitors** — `by {user_id}` creates a monitor per user. Use `by {endpoint}` or `by {error_type}`.
5. **Too short evaluation window** — 1-minute windows cause flapping. Use 5+ minutes for most monitors.

## Dashboard Design

### Standard Service Dashboard Sections
1. **Overview** — request rate, error rate, P50/P95/P99 latency (top row, always visible)
2. **Endpoints** — top list of endpoints by request count, slowest endpoints, error endpoints
3. **Dependencies** — downstream service latency, database query time, cache hit rate
4. **Infrastructure** — CPU, memory, JVM heap (Spring), goroutine count (Go)
5. **Business Metrics** — orders created, payment success rate, active users (domain-specific)

### Dashboard Naming Convention
```
[env] service-name - Overview        # main operational dashboard
[env] service-name - Business        # business metrics
[env] service-name - Dependencies    # downstream health
```

## DataBricks Pipeline Monitoring

### Key Metrics to Track

| Metric | How to Emit | Alert When |
|--------|-------------|------------|
| Job success/failure | DD integration or custom webhook | Any failure (P2) |
| Job duration | Custom metric from job wrapper | > 2x historical average (P3) |
| Records processed | Emit from Spark job | Drops > 50% from expected (P2) |
| Data quality check results | Emit pass/fail counts | Any critical check fails (P1) |
| Cluster utilization | DD-DataBricks integration | Consistently > 80% or < 20% |
| Cost per job | Custom metric from billing API | Exceeds budget threshold (P3) |

### DataBricks Job Wrapper Pattern

```python
# Emit metrics from DataBricks notebook/job
import requests
import time

DD_API_KEY = dbutils.secrets.get(scope="datadog", key="api-key")

def emit_metric(metric_name, value, tags=None):
    """Send custom metric to DataDog."""
    payload = {
        "series": [{
            "metric": f"databricks.{metric_name}",
            "points": [[int(time.time()), value]],
            "type": "gauge",
            "tags": tags or []
        }]
    }
    requests.post(
        "https://api.datadoghq.com/api/v1/series",
        json=payload,
        headers={"DD-API-KEY": DD_API_KEY}
    )

# Usage in job
start = time.time()
try:
    records = run_etl_pipeline()
    emit_metric("job.records_processed", records, ["job:daily-orders", "env:prod"])
    emit_metric("job.status", 1, ["job:daily-orders", "env:prod", "status:success"])
except Exception as e:
    emit_metric("job.status", 0, ["job:daily-orders", "env:prod", "status:failed"])
    raise
finally:
    duration = time.time() - start
    emit_metric("job.duration_seconds", duration, ["job:daily-orders", "env:prod"])
```

## Instrumenting What Matters

### Always Instrument
- **API endpoints** — latency, error rate, status codes (APM does this automatically)
- **Database queries** — query time, connection pool usage (APM auto-instruments)
- **External HTTP calls** — latency, error rate, timeout rate
- **Message consumers** — processing time, failure rate, DLQ sends
- **Business events** — order created, payment processed, user registered (custom metrics)
- **Cache** — hit rate, miss rate, eviction rate

### Never Instrument
- Individual user actions (unbounded cardinality)
- Debug-level operations in production (cost)
- PII in tags or log attributes (compliance — see security.md)
