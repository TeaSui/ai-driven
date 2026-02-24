# impl-19: Deep Integrations — CloudWatch Observability

## Status: ✅ COMPLETED

## Context & Motivation

Currently, when a user asks the agent to investigate a bug reported in a Jira ticket, the agent can only perform static code analysis (searching for logical errors in the codebase) but lacks real-time visibility into production runtime behavior, logs, metrics, errors, or alerts.

This limitation forces developers to manually switch between Jira, Datadog/Sentry/CloudWatch, and the code — defeating the purpose of an autonomous agent. Deep integrations with observability tools would enable the agent to query live telemetry, correlate errors with code changes, and propose fixes more accurately and quickly.

---

## Implementation

### Phase 1a: CloudWatch Logs + Metrics ✅ DONE

#### SPI Contracts (`spi/src/main/java/com/aidriven/spi/observability/`)

| File | Description |
|------|-------------|
| `ObservabilityClient.java` | Interface with `queryLogs()` and `queryMetrics()` methods |
| `LogQuery.java` | Query parameters: log group, filter pattern, time window, limit |
| `LogQueryResult.java` | Result: lines, truncated flag, total count |
| `MetricQuery.java` | Query parameters: namespace, metric, statistic, dimensions, time window |
| `MetricQueryResult.java` | Result: average, sum, min, max, sample count, unit |

#### CloudWatch Implementation (`core/src/main/java/com/aidriven/core/observability/`)

| File | Description |
|------|-------------|
| `CloudWatchObservabilityClient.java` | Implements `ObservabilityClient` using CloudWatch Logs Insights + GetMetricStatistics |
| `AgentMetrics.java` | EMF (Embedded Metric Format) writer for publishing agent metrics |

**CloudWatchObservabilityClient Features:**
- Uses Logs Insights API (`StartQuery`/`GetQueryResults`) for rich log querying
- Falls back to `FilterLogEvents` on Insights failures
- Polls with configurable timeout (20 attempts × 500ms)
- Truncates results to ~8000 chars to protect context window
- Uses `GetMetricStatistics` for metric queries with dimension filtering

**AgentMetrics EMF Features:**
- Records: `agent.turns`, `agent.tokens.total`, `agent.tools.count`, `agent.latency.ms`, `agent.errors`
- Dimensions: `TenantId`, `Platform`
- Custom properties and metrics via `putProperty()` and `putMetric()`
- `flush()` outputs JSON to stdout in CloudWatch EMF format

#### Agent Tools (`tool-observability/`)

| Tool | Description |
|------|-------------|
| `observability_query_logs` | Query CloudWatch Logs with filter patterns |
| `observability_query_metrics` | Query CloudWatch Metrics with statistics |

**Example Usage:**
```
@ai what errors occurred in the last hour?
→ Uses observability_query_logs with filter="ERROR"

@ai what was the average agent latency yesterday?
→ Uses observability_query_metrics with metric="agent.latency.ms", statistic="Average"
```

#### Integration with AgentOrchestrator

`AgentOrchestrator.process()` automatically:
1. Creates `AgentMetrics` at start of each run
2. Records turns, tokens, tools, latency on completion
3. Calls `flush()` to emit EMF JSON

---

### Tests

| Test File | Coverage |
|-----------|----------|
| `AgentMetricsTest.java` | EMF format validation, all metric types, dimension handling |
| `ObservabilityToolProviderTest.java` | 11 tests covering both tools, parameter passing, error handling |

---

## Design Decisions

- **CloudWatch-first**: Uses native AWS services already in the stack, avoiding external dependencies
- **EMF over PutMetricData**: Zero API calls — CloudWatch parses EMF from Lambda logs automatically
- **Insights + Fallback**: Logs Insights provides powerful querying, but falls back gracefully on errors
- **Context Protection**: 8000-char truncation prevents log dumps from overwhelming the context window