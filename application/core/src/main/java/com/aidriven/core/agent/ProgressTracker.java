package com.aidriven.core.agent;

import com.aidriven.core.agent.tool.ToolResult;

import java.util.List;

/**
 * Interface for tracking agent progress and updating the user.
 * In Phase 1/2, this updates the Jira acknowledgment comment in-place.
 */
public interface ProgressTracker {

    /**
     * Updates the progress comment with the latest tool execution results.
     *
     * @param commentId The ID of the acknowledgment comment to update
     * @param results   The results of recent tool executions
     */
    void updateProgress(String commentId, List<ToolResult> results);

    /**
     * Marks the progress as complete and optionally replaces the content.
     */
    void complete(String commentId, String finalResponse);

    /**
     * Reports a failure to the user.
     */
    void fail(String commentId, String errorMessage);
}
