package com.aidriven.lambda;

import com.aidriven.core.agent.AgentOrchestrator;
import com.aidriven.core.agent.ConversationWindowManager;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.agent.tool.ToolProvider;
import com.aidriven.tool.tracker.IssueTrackerToolProvider;
import com.aidriven.tool.source.SourceControlToolProvider;
import com.aidriven.tool.context.CodeContextToolProvider;
import com.aidriven.tool.context.ContextService;
import java.util.Map;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.source.Platform;
import com.aidriven.core.source.PlatformResolver;
import com.aidriven.core.source.RepositoryResolver;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.claude.ClaudeClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Consumes agent tasks from SQS FIFO queue and executes the agent orchestrator.
 * Handles idempotency and updates Jira with progress and final results.
 */
@Slf4j
public class AgentProcessorHandler implements RequestHandler<SQSEvent, Void> {

    private final ObjectMapper objectMapper;
    private final ServiceFactory serviceFactory;
    private final JiraClient jiraClient;
    private final IdempotencyService idempotencyService;
    private final JiraCommentFormatter formatter;

    public AgentProcessorHandler() {
        this.serviceFactory = ServiceFactory.getInstance();
        this.objectMapper = serviceFactory.getObjectMapper();
        this.jiraClient = serviceFactory.getJiraClient();
        this.idempotencyService = serviceFactory.getIdempotencyService();
        this.formatter = new JiraCommentFormatter();
    }

    // For testing
    public AgentProcessorHandler(ServiceFactory serviceFactory,
            ObjectMapper objectMapper,
            JiraClient jiraClient,
            IdempotencyService idempotencyService) {
        this.serviceFactory = serviceFactory;
        this.objectMapper = objectMapper;
        this.jiraClient = jiraClient;
        this.idempotencyService = idempotencyService;
        this.formatter = new JiraCommentFormatter();
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processMessage(message);
            } catch (Exception e) {
                log.error("Failed to process SQS message {}: {}", message.getMessageId(), e.getMessage(), e);
                // We let the exception propagate to trigger DLQ if retries fail
                // But for batch processing, we might want to handle individual failures to
                // avoid reprocessing successful ones.
                // Since this is a FIFO queue with batch size usually 1 or 10, typically 1 for
                // complex tasks.
                throw new RuntimeException("Failed to process message", e);
            }
        }
        return null;
    }

    private void processMessage(SQSEvent.SQSMessage message) throws Exception {
        JsonNode body = objectMapper.readTree(message.getBody());
        String ticketKey = body.get("ticketKey").asText();
        String ackCommentId = body.get("ackCommentId").asText();
        String commentBody = body.get("commentBody").asText();
        String commentAuthor = body.get("commentAuthor").asText();

        log.info("Processing agent task for ticket={} (ack={})", ticketKey, ackCommentId);

        // Check idempotency
        if (!idempotencyService.checkAndRecord(ticketKey, ackCommentId)) {
            log.warn("Duplicate event detected via idempotency check: ticket={}, ack={}", ticketKey, ackCommentId);
            return;
        }

        try {
            // Fresh fetch of ticket info
            TicketInfo ticket = jiraClient.getTicket(ticketKey);
            SourceControlClient scClient = resolveSourceControlClient(ticket);

            // Tools setup
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.register(new SourceControlToolProvider(scClient));
            toolRegistry.register(new IssueTrackerToolProvider(jiraClient));

            ContextService contextService = serviceFactory.createContextService(scClient);
            toolRegistry.register(new CodeContextToolProvider(contextService));

            // Progress tracker implementation via Jira comments
            ProgressTracker progressTracker = new ProgressTracker() {
                @Override
                public void updateProgress(String commentId, List<ToolResult> results) {
                    try {
                        // For now, we don't update intermediate progress to keep noise low,
                        // or we could append "." to the comment.
                        // Ideally, we'd edit the comment with "Step X: Executed Tool Y..."
                        // But Jira edits trigger webhooks, beware loops (though we filter bot authors).
                        log.info("Progress update for {}: {} tools executed", commentId, results.size());
                    } catch (Exception e) {
                        log.warn("Failed to update progress: {}", e.getMessage());
                    }
                }

                @Override
                public void complete(String commentId, String finalResponse) {
                    // Handled by orchestrator return value normally, but good for cleanup
                }

                @Override
                public void fail(String commentId, String errorMessage) {
                    try {
                        jiraClient.addComment(ticketKey, "Agent failed: " + errorMessage);
                    } catch (Exception e) {
                        log.error("Failed to post failure comment", e);
                    }
                }
            };

            ClaudeClient claudeClient = serviceFactory.getClaudeClient();
            ConversationWindowManager windowManager = serviceFactory.getConversationWindowManager();
            AgentOrchestrator orchestrator = new AgentOrchestrator(claudeClient, toolRegistry, progressTracker,
                    windowManager);

            AgentRequest request = new AgentRequest(ticketKey, commentBody, commentAuthor, ticket, ackCommentId);
            AgentResponse response = orchestrator.process(request);

            // Update the ack comment in-place with the final response
            String formattedResponse = formatter.format(
                    response.text(), response.toolsUsed(), response.tokenCount());

            try {
                jiraClient.editComment(ticketKey, ackCommentId, formattedResponse);
                log.info("Updated ack comment {} with final response for ticket={}", ackCommentId, ticketKey);
            } catch (UnsupportedOperationException e) {
                // Fallback: post a new comment if editComment not supported
                log.warn("editComment not supported, posting new comment for ticket={}", ticketKey);
                jiraClient.addComment(ticketKey, formattedResponse);
            }

            log.info("Agent task completed for ticket={} with {} turns", ticketKey, response.turnCount());

        } catch (Exception e) {
            log.error("Error during agent execution for ticket={}", ticketKey, e);
            String errorComment = formatter.formatError(e.getMessage());
            try {
                jiraClient.editComment(ticketKey, ackCommentId, errorComment);
            } catch (Exception editEx) {
                log.warn("Failed to edit ack comment with error, posting new comment", editEx);
                jiraClient.addComment(ticketKey, errorComment);
            }
            throw e; // Rethrow to ensure DLQ routing if needed
        }
    }

    private SourceControlClient resolveSourceControlClient(TicketInfo ticket) throws Exception {
        Platform platform = PlatformResolver.resolve(
                ticket.getLabels(), null, serviceFactory.getAppConfig().getDefaultPlatform());
        RepositoryResolver.ResolvedRepository repo = RepositoryResolver.resolve(
                ticket.getLabels(), null,
                serviceFactory.getAppConfig().getDefaultWorkspace(),
                serviceFactory.getAppConfig().getDefaultRepo(),
                serviceFactory.getAppConfig().getDefaultPlatform());

        String owner = repo != null ? repo.owner() : null;
        String slug = repo != null ? repo.repo() : null;

        return switch (platform) {
            case GITHUB -> serviceFactory.getGitHubClient(owner, slug);
            case BITBUCKET -> serviceFactory.getBitbucketClient(
                    owner != null ? owner : serviceFactory.getAppConfig().getDefaultWorkspace(),
                    slug != null ? slug : serviceFactory.getAppConfig().getDefaultRepo());
        };
    }
}
