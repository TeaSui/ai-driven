package com.aidriven.spi.observability;

import lombok.Builder;
import lombok.Data;

/**
 * Result of a metric query from CloudWatch Metrics.
 */
@Data
@Builder
public class MetricQueryResult {
    /** The metric namespace */
    private String namespace;

    /** The metric name */
    private String metricName;

    /** The unit of measurement */
    private String unit;

    /** Average value over the period */
    private Double average;

    /** Sum of values over the period */
    private Double sum;

    /** Minimum value over the period */
    private Double minimum;

    /** Maximum value over the period */
    private Double maximum;

    /** Number of data points */
    private Integer sampleCount;

    /** The statistic that was requested */
    private String statistic;

    /** Period in minutes */
    private Integer periodMinutes;

    /** Start time of the query window (ISO-8601) */
    private String startTime;

    /** End time of the query window (ISO-8601) */
    private String endTime;
}
