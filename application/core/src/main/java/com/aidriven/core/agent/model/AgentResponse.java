package com.aidriven.core.agent.model;

import java.util.List;

/**
 * Response from the agent orchestrator after processing a request.
 *
 * @param text       Final response text (in markdown)
 * @param toolsUsed  Names of tools that were called during processing
 * @param tokenCount Total tokens consumed (input + output across all turns)
 * @param turnCount  Number of ReAct loop turns executed
 */
public record AgentResponse(
        String text,
        List<String> toolsUsed,
        int tokenCount,
        int turnCount) {
}
