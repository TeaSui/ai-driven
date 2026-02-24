package com.aidriven.lambda.platform;

import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.model.AgentTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Jira-specific platform strategy.
 *
 * <p>Responses are delivered by editing the acknowledgment comment that was
 * created in {@link com.aidriven.lambda.AgentWebhookHandler} immediately after
 * the task was received. This is the closest Jira Cloud offers to a threaded
 * reply.
 */
@Slf4j
@RequiredArgsConstructor
public class JiraPlatformStrategy implements PlatformStrategy {

    private final JiraClient jiraClient;
    private final JiraCommentFormatter formatter;

    @Override
    public String platform() {
        return "JIRA";
    }

    @Override
    public ProgressTracker createProgressTracker(AgentTask task, SourceControlClient sc) {
        return new ProgressTracker() {
            @Override
            public void updateProgress(String commentId, List<ToolResult> results) {
                log.info("Jira progress: {} tools executed for ticket={}", results.size(), task.getTicketKey());
            }

            @Override
            public void complete(String commentId, String finalResponse) {
                // Handled by postFinalResponse — nothing to do here
            }

            @Override
            public void fail(String commentId, String errorMessage) {
                try {
                    String comment = formatter.formatError(errorMessage);
                    postComment(task, comment);
                } catch (Exception e) {
                    log.error("Failed to post Jira failure for ticket={}", task.getTicketKey(), e);
                }
            }
        };
    }

    @Override
    public void postFinalResponse(AgentTask task, SourceControlClient sc, String formattedResponse) {
        String ticketKey = task.getTicketKey();
        String ackCommentId = task.getAckCommentId();

        try {
            jiraClient.editComment(task.getContext(), ticketKey, ackCommentId, formattedResponse);
        } catch (Exception e) {
            log.warn("Failed to edit ack comment for ticket={}, posting new comment", ticketKey);
            try {
                jiraClient.postComment(task.getContext(), ticketKey, formattedResponse);
            } catch (Exception ex) {
                log.error("Failed to post fallback comment for ticket={}", ticketKey, ex);
            }
        }
    }

    /**
     * Formats the response using the reply format with quoted excerpt.
     * Called by AgentProcessorHandler before postFinalResponse.
     */
    public String formatReply(AgentTask task, String responseText, List<String> toolsUsed, int tokenCount) {
        // Use reply format if we have parent comment info
        if (task.getParentCommentExcerpt() != null || task.getParentCommentAuthorAccountId() != null) {
            return formatter.formatAsReply(
                    responseText,
                    task.getParentCommentExcerpt(),
                    task.getParentCommentAuthorAccountId(),
                    toolsUsed,
                    tokenCount
            );
        }
        // Fall back to standard format
        return formatter.format(responseText, toolsUsed, tokenCount, task.getCommentAuthorAccountId());
    }

    private void postComment(AgentTask task, String comment) {
        try {
            jiraClient.postComment(task.getContext(), task.getTicketKey(), comment);
        } catch (Exception e) {
            log.error("Failed to post Jira comment for ticket={}", task.getTicketKey(), e);
        }
    }
}
