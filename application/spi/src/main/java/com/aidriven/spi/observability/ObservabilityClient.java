package com.aidriven.spi.observability;

import com.aidriven.spi.model.OperationContext;

/**
 * Contract for querying live log and metric data from an observability backend.
 *
 * <p>This interface provides the AI agent with the ability to inspect
 * application logs and metrics at runtime, enabling it to diagnose production
 * issues, verify deployments, and correlate errors with recent changes.</p>
 *
 * <h3>Known Implementations</h3>
 * <ul>
 *   <li>{@code CloudWatchObservabilityClient} -- queries AWS CloudWatch Logs
 *       (via CloudWatch Logs Insights) and CloudWatch Metrics</li>
 * </ul>
 *
 * <h3>Query Model</h3>
 * <p>Both log and metric queries use a time-window model specified in
 * "minutes ago" relative to the current time. This simplifies agent usage
 * since the model does not need to compute absolute timestamps.</p>
 *
 * <h3>Tool Integration</h3>
 * <p>This client is typically exposed to the AI model via a
 * {@code MonitoringToolProvider} that maps tool calls to the corresponding
 * query methods. The tool provider is enabled per-ticket via the
 * {@code "tool:monitoring"} label.</p>
 *
 * @see LogQuery
 * @see LogQueryResult
 * @see MetricQuery
 * @see MetricQueryResult
 * @since 1.0
 */
public interface ObservabilityClient {

    /**
     * Queries log entries from the observability backend.
     *
     * <p>The query is defined by a {@link LogQuery} that specifies the log
     * group pattern, filter expression, time window, and result limit.
     * Results may be truncated if they exceed the backend's response size
     * limits.</p>
     *
     * <p>Example usage for querying Lambda errors in the last hour:</p>
     * <pre>{@code
     * LogQuery query = LogQuery.builder()
     *     .logGroupPattern("/aws/lambda/ai-driven")
     *     .filterPattern("ERROR")
     *     .startMinutesAgo(60)
     *     .limit(50)
     *     .build();
     * LogQueryResult result = client.queryLogs(context, query);
     * }</pre>
     *
     * @param context the operation context carrying tenant and tracing
     *                information; never {@code null}
     * @param query   the log query parameters; never {@code null}
     * @return the query result containing matched log lines and metadata;
     *         never {@code null}
     * @throws Exception if the query fails (invalid query syntax, backend
     *                   unavailable, permission denied, or timeout)
     */
    LogQueryResult queryLogs(OperationContext context, LogQuery query) throws Exception;

    /**
     * Queries metric data points from the observability backend.
     *
     * <p>The query is defined by a {@link MetricQuery} that specifies the
     * metric namespace, name, statistic type, aggregation period, time window,
     * and optional dimension filters. Results contain aggregated data points
     * at the specified period granularity.</p>
     *
     * <p>Example usage for querying Lambda invocation counts:</p>
     * <pre>{@code
     * MetricQuery query = MetricQuery.builder()
     *     .namespace("AWS/Lambda")
     *     .metricName("Invocations")
     *     .statistic("Sum")
     *     .periodMinutes(5)
     *     .startMinutesAgo(60)
     *     .build();
     * MetricQueryResult result = client.queryMetrics(context, query);
     * }</pre>
     *
     * @param context the operation context carrying tenant and tracing
     *                information; never {@code null}
     * @param query   the metric query parameters; never {@code null}
     * @return the query result containing metric data points and statistics;
     *         never {@code null}
     * @throws Exception if the query fails (metric not found, backend
     *                   unavailable, permission denied, or timeout)
     */
    MetricQueryResult queryMetrics(OperationContext context, MetricQuery query) throws Exception;
}
