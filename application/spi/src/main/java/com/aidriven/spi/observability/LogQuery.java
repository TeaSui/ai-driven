package com.aidriven.spi.observability;

import lombok.Builder;
import lombok.Data;

/**
 * Parameters for a log query against an observability backend.
 */
@Data
@Builder
public class LogQuery {
    /** CloudWatch log group name prefix, e.g. "/aws/lambda/ai-driven" */
    private String logGroupPattern;

    /**
     * Filter pattern (CloudWatch Insights syntax or simple keyword), nullable for
     * "all logs"
     */
    private String filterPattern;

    /** How many minutes in the past to start the query window (default: 60) */
    @Builder.Default
    private int startMinutesAgo = 60;

    /** How many minutes in the past to end the query window (default: 0 = now) */
    @Builder.Default
    private int endMinutesAgo = 0;

    /** Maximum number of log lines to return (default: 100) */
    @Builder.Default
    private int limit = 100;
}
