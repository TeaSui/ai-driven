package com.aidriven.lambda.platform;

import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.lambda.model.AgentTask;

/**
 * Strategy contract for platform-specific response handling.
 *
 * <p>Implement this interface for each platform (Jira, GitHub, GitLab, …) to
 * eliminate the {@code if "GITHUB" / else} pattern that currently requires
 * modifying production code when adding a new platform.
 *
 * <p>Implementations are registered in {@link PlatformStrategyRegistry} and
 * looked up by the platform string from {@link AgentTask#getPlatform()}.
 */
public interface PlatformStrategy {

    /** Platform identifier this strategy handles (e.g. "JIRA", "GITHUB"). */
    String platform();

    /**
     * Creates a {@link ProgressTracker} for reporting intermediate steps and
     * the final AI response back to the user.
     *
     * @param task task carrying all context needed to route responses
     * @param sc   source control client resolved for this task's repo
     */
    ProgressTracker createProgressTracker(AgentTask task, SourceControlClient sc);

    /**
     * Posts the final formatted AI response after the agent loop completes.
     * Only called for platforms where the response is NOT already handled by the
     * {@code ProgressTracker.complete()} path.
     *
     * @param task              task carrying ticket key, ack comment id, etc.
     * @param sc                source control client
     * @param formattedResponse AI response formatted for this platform
     */
    void postFinalResponse(AgentTask task, SourceControlClient sc, String formattedResponse);
}
