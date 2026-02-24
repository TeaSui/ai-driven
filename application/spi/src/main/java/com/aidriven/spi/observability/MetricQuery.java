package com.aidriven.spi.observability;

import lombok.Builder;
import lombok.Data;

/**
 * Parameters for a metric query against CloudWatch Metrics.
 */
@Data
@Builder
public class MetricQuery {
    /** CloudWatch namespace, e.g. "AiAgent" or "AWS/Lambda" */
    private String namespace;

    /** Metric name, e.g. "agent.turns" or "Invocations" */
    private String metricName;

    /** Statistic to retrieve: Average, Sum, Minimum, Maximum, SampleCount */
    @Builder.Default
    private String statistic = "Sum";

    /** Aggregation period in minutes (default: 5) */
    @Builder.Default
    private int periodMinutes = 5;

    /** How many minutes in the past to start the query window (default: 60) */
    @Builder.Default
    private int startMinutesAgo = 60;

    /** How many minutes in the past to end the query window (default: 0 = now) */
    @Builder.Default
    private int endMinutesAgo = 0;

    /** Optional dimension name to filter by, e.g. "Platform" */
    private String dimensionName;

    /** Optional dimension value to filter by, e.g. "GITHUB" */
    private String dimensionValue;
}
