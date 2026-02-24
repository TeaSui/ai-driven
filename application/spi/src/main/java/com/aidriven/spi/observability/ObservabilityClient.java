package com.aidriven.spi.observability;

import com.aidriven.spi.model.OperationContext;

/**
 * Contract for querying live log/telemetry data from an observability backend.
 * Implementations include CloudWatch Logs, Datadog, Sentry, etc.
 */
public interface ObservabilityClient {
    /**
     * Query logs from the observability backend.
     *
     * @param context The operation context (tenant/user)
     * @param query   The query parameters
     * @return The query result with log lines, potentially truncated
     * @throws Exception on backend errors
     */
    LogQueryResult queryLogs(OperationContext context, LogQuery query) throws Exception;

    /**
     * Query metrics from the observability backend.
     *
     * @param context The operation context (tenant/user)
     * @param query   The query parameters
     * @return The query result with metric statistics
     * @throws Exception on backend errors
     */
    MetricQueryResult queryMetrics(OperationContext context, MetricQuery query) throws Exception;
}
