package com.aidriven.core.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Groups token usage metrics for a request or turn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;

    public void add(TokenUsage other) {
        if (other == null)
            return;
        this.inputTokens += other.inputTokens;
        this.outputTokens += other.outputTokens;
        this.totalTokens += other.totalTokens;
    }
}
