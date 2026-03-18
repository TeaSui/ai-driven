package com.aidriven.app.listener;

import com.aidriven.app.config.AgentConfig;
import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.model.AgentTask;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.source.SourceControlClientResolver;
import com.aidriven.core.tracker.IssueTrackerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * SQS FIFO queue listener for agent tasks.
 * Replaces the Lambda AgentProcessorHandler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSqsListener {

    private final AgentConfig.SwarmOrchestratorFactory swarmFactory;
    private final IssueTrackerClient issueTrackerClient;
    private final SourceControlClientResolver sourceControlClientResolver;
    private final ObjectMapper objectMapper;

    @SqsListener("${ai-driven.aws.sqs.agent-queue-url}")
    public void processAgentTask(String messageBody) {
        AgentTask task = null;
        try {
            task = objectMapper.readValue(messageBody, AgentTask.class);
            setupMdc(task);

            log.info("Processing agent task: ticketKey={}", task.getTicketKey());

            SourceControlClient scClient = sourceControlClientResolver.resolve(
                    task.getPlatform(), task.getRepoOwner(), task.getRepoSlug());
            ProgressTracker tracker = new LoggingProgressTracker(task.getTicketKey());

            var swarm = swarmFactory.create(scClient, tracker);

            AgentRequest request = new AgentRequest(
                    task.getTicketKey(),
                    task.getPlatform(),
                    task.getCommentBody(),
                    task.getCommentAuthor(),
                    null,
                    task.getAckCommentId(),
                    task.getContext(),
                    null);

            AgentResponse result = swarm.process(request, CommentIntent.AI_COMMAND);

            if (result != null && result.text() != null) {
                issueTrackerClient.addComment(task.getContext(), task.getTicketKey(), result.text());
                log.info("Agent response posted to ticket: {}", task.getTicketKey());
            }

        } catch (Exception e) {
            log.error("Agent task processing failed: {}",
                    task != null ? task.getTicketKey() : "unknown", e);
            if (task != null) {
                try {
                    issueTrackerClient.addComment(task.getContext(), task.getTicketKey(),
                            "Agent encountered an error: " + e.getMessage());
                } catch (Exception commentErr) {
                    log.error("Failed to post error comment", commentErr);
                }
            }
        } finally {
            MDC.clear();
        }
    }

    private void setupMdc(AgentTask task) {
        MDC.put("correlationId", task.getCorrelationId() != null
                ? task.getCorrelationId() : UUID.randomUUID().toString());
        MDC.put("ticketKey", task.getTicketKey());
        MDC.put("handler", "agent-sqs-listener");
    }

    @Slf4j
    private static class LoggingProgressTracker implements ProgressTracker {
        private final String ticketKey;

        LoggingProgressTracker(String ticketKey) {
            this.ticketKey = ticketKey;
        }

        @Override
        public void updateProgress(String commentId, List<ToolResult> results) {
            log.info("[{}] Progress: {} tool results", ticketKey, results.size());
        }

        @Override
        public void complete(String commentId, String finalResponse) {
            log.info("[{}] Agent completed", ticketKey);
        }

        @Override
        public void fail(String commentId, String errorMessage) {
            log.error("[{}] Agent failed: {}", ticketKey, errorMessage);
        }
    }
}
