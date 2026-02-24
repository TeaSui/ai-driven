package com.aidriven.spi.observability;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of an observability log query.
 */
@Data
@Builder
public class LogQueryResult {
    /** Individual log lines, pre-formatted for the LLM context window */
    private List<String> lines;

    /** True if the output was truncated due to token budget constraints */
    private boolean truncated;

    /** Number of lines before truncation */
    private int totalLines;
}
