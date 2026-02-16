package com.aidriven.lambda;

import com.aidriven.core.agent.AgentOrchestrator;
import com.aidriven.core.agent.CommentIntentClassifier;
import com.aidriven.core.agent.ConversationWindowManager;
import com.aidriven.core.agent.CostTracker;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.agent.guardrail.ApprovalStore;
import com.aidriven.core.agent.guardrail.GuardedToolRegistry;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.agent.tool.ToolRegistry;
import com.aidriven.core.agent.tool.ToolResult;
import com.aidriven.core.config.AgentConfig;
import com.aidriven.mcp.McpBridgeToolProvider;
import com.aidriven.tool.tracker.IssueTrackerToolProvider;
import com.aidriven.tool.source.SourceControlToolProvider;
import com.aidriven.tool.context.CodeContextToolProvider;
import com.aidriven.tool.context.ContextService;
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
 *
 * <p>
 * Phase 3: Added intent classification, guardrails, approval handling, and cost
 * tracking.
 * </p>
 * <p>
 * Phase 4: Added MCP bridge tool providers for external integrations.
 * </p>
 */
@Slf4j
public class AgentProcessorHandler implements RequestHandler<SQSEvent, Void> {

    private final ObjectMapper objectMapper;
    private final ServiceFactory serviceFactory;
    private final JiraClient jiraClient;
    private final IdempotencyService idempotencyService;
    private final JiraCommentFormatter formatter;
    private final CommentIntentClassifier intentClassifier;

    public AgentProcessorHandler() {
        this.serviceFactory = ServiceFactory.getInstance();
        this.objectMapper = serviceFactory.getObjectMapper();
        this.jiraClient = serviceFactory.getJiraClient();
        this.idempotencyService = serviceFactory.getIdempotencyService();
        this.formatter = new JiraCommentFormatter();

        AgentConfig agentConfig = serviceFactory.getAppConfig().getAgentConfig();
        this.intentClassifier = agentConfig.classifierUseLlm()
                ? new CommentIntentClassifier(serviceFactory.getClaudeClient(), true)
                : new CommentIntentClassifier();
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
        this.intentClassifier = new CommentIntentClassifier();
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processMessage(message);
            } catch (Exception e) {
                log.error("Failed to process SQS message {}: {}", message.getMessageId(), e.getMessage(), e);
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
            // Classify intent
            CommentIntent intent = intentClassifier.classify(commentBody, false);
            log.info("Classified intent for ticket={}: {}", ticketKey, intent);

            // Handle APPROVAL intent specially — execute pending gated action
            if (intent == CommentIntent.APPROVAL) {
                handleApproval(ticketKey, ackCommentId, commentBody, commentAuthor);
                return;
            }

            // Fresh fetch of ticket info
            TicketInfo ticket = jiraClient.getTicket(ticketKey);
            SourceControlClient scClient = resolveSourceControlClient(ticket);

            // Tools setup
            ToolRegistry toolRegistry = new ToolRegistry();
            toolRegistry.register(new SourceControlToolProvider(scClient));
            toolRegistry.register(new IssueTrackerToolProvider(jiraClient));

            ContextService contextService = serviceFactory.createContextService(scClient);
            toolRegistry.register(new CodeContextToolProvider(contextService));

            // Register MCP tool providers (Phase 4)
            for (McpBridgeToolProvider mcpProvider : serviceFactory.getMcpToolProviders()) {
                toolRegistry.register(mcpProvider);
            }

            // Wrap with guardrails (Phase 3)
            GuardedToolRegistry guardedToolRegistry = serviceFactory.createGuardedToolRegistry(toolRegistry);

            // Progress tracker implementation via Jira comments
            ProgressTracker progressTracker = createProgressTracker(ticketKey);

            // Build orchestrator with Phase 3+ features
            ClaudeClient claudeClient = serviceFactory.getClaudeClient();
            ConversationWindowManager windowManager = serviceFactory.getConversationWindowManager();
            CostTracker costTracker = serviceFactory.getCostTracker();
            AgentConfig agentConfig = serviceFactory.getAppConfig().getAgentConfig();

            AgentOrchestrator orchestrator = new AgentOrchestrator(
                    claudeClient, toolRegistry, guardedToolRegistry,
                    progressTracker, windowManager, costTracker,
                    agentConfig.maxTurns());

            AgentRequest request = new AgentRequest(ticketKey, commentBody, commentAuthor, ticket, ackCommentId);
            AgentResponse response = orchestrator.process(request, intent);

            // Update the ack comment in-place with the final response
            String formattedResponse = formatter.format(
                    response.text(), response.toolsUsed(), response.tokenCount());

            postResponse(ticketKey, ackCommentId, formattedResponse);
            log.info("Agent task completed for ticket={} with {} turns, intent={}", ticketKey, response.turnCount(),
                    intent);

        } catch (Exception e) {
            log.error("Error during agent execution for ticket={}", ticketKey, e);
            String errorComment = formatter.formatError(e.getMessage());
            postResponse(ticketKey, ackCommentId, errorComment);
            throw e;
        }
    }

    /**
     * Handles APPROVAL intent: finds pending approval and executes the gated
     * action.
     */
    private void handleApproval(String ticketKey, String ackCommentId, String commentBody,
            String commentAuthor) throws Exception {
        ApprovalStore approvalStore = serviceFactory.getApprovalStore();
        var pendingOpt = approvalStore.getLatestPending(ticketKey);

        if (pendingOpt.isEmpty()) {
            String msg = "No pending approval found for this ticket. " +
                    "There may be no action awaiting approval, or it may have expired.";
            postResponse(ticketKey, ackCommentId, formatter.format(msg, List.of(), 0));
            return;
        }

        var pending = pendingOpt.get();

        // Check if this is an approval or rejection
        String lower = commentBody.toLowerCase().trim();
        if (lower.contains("reject") || lower.contains("cancel") || lower.contains("deny")) {
            approvalStore.consumeApproval(ticketKey, pending.sk());
            String msg = "Rejected: " + pending.approvalPrompt() + "\nThe action has been cancelled.";
            postResponse(ticketKey, ackCommentId, formatter.format(msg, List.of(), 0));
            log.info("Approval rejected for ticket={} tool={}", ticketKey, pending.toolName());
            return;
        }

        // Execute the approved action
        log.info("Executing approved action for ticket={}: tool={}", ticketKey, pending.toolName());
        TicketInfo ticket = jiraClient.getTicket(ticketKey);
        SourceControlClient scClient = resolveSourceControlClient(ticket);

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new SourceControlToolProvider(scClient));
        toolRegistry.register(new IssueTrackerToolProvider(jiraClient));

        ContextService contextService = serviceFactory.createContextService(scClient);
        toolRegistry.register(new CodeContextToolProvider(contextService));

        // Register MCP providers
        for (McpBridgeToolProvider mcpProvider : serviceFactory.getMcpToolProviders()) {
            toolRegistry.register(mcpProvider);
        }

        GuardedToolRegistry guardedToolRegistry = serviceFactory.createGuardedToolRegistry(toolRegistry);
        ToolResult result = guardedToolRegistry.executeApproved(ticketKey, pending);

        String msg = result.isError()
                ? "Approved action failed: " + result.content()
                : "Approved and executed: " + pending.toolName() + "\n\n" + result.content();
        postResponse(ticketKey, ackCommentId, formatter.format(msg, List.of(pending.toolName()), 0));
    }

    private ProgressTracker createProgressTracker(String ticketKey) {
        return new ProgressTracker() {
            @Override
            public void updateProgress(String commentId, List<ToolResult> results) {
                log.info("Progress update for {}: {} tools executed", commentId, results.size());
            }

            @Override
            public void complete(String commentId, String finalResponse) {
                // Handled by orchestrator return value
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
    }

    /**
     * Posts a response to Jira, preferring editComment with fallback to addComment.
     */
    private void postResponse(String ticketKey, String ackCommentId, String formattedResponse) {
        try {
            jiraClient.editComment(ticketKey, ackCommentId, formattedResponse);
            log.info("Updated ack comment {} for ticket={}", ackCommentId, ticketKey);
        } catch (UnsupportedOperationException e) {
            log.warn("editComment not supported, posting new comment for ticket={}", ticketKey);
            try {
                jiraClient.addComment(ticketKey, formattedResponse);
            } catch (Exception ex) {
                log.error("Failed to post fallback comment for ticket={}", ticketKey, ex);
            }
        } catch (Exception e) {
            log.warn("Failed to edit ack comment, posting new comment for ticket={}", ticketKey);
            try {
                jiraClient.addComment(ticketKey, formattedResponse);
            } catch (Exception ex) {
                log.error("Failed to post fallback comment for ticket={}", ticketKey, ex);
            }
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
