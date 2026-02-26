package com.aidriven.lambda;

import com.aidriven.core.agent.swarm.SwarmOrchestrator;
import com.aidriven.core.agent.CommentIntentClassifier;
import com.aidriven.core.agent.JiraCommentFormatter;
import com.aidriven.core.agent.ProgressTracker;
import com.aidriven.core.agent.model.AgentRequest;
import com.aidriven.core.agent.model.AgentResponse;
import com.aidriven.core.agent.model.CommentIntent;
import com.aidriven.core.config.AgentConfig;
import com.aidriven.core.model.TicketInfo;
import com.aidriven.core.service.IdempotencyService;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.agent.ToolRegistryBuilder;
import com.aidriven.lambda.approval.ApprovalHandler;
import com.aidriven.lambda.factory.ServiceFactory;
import com.aidriven.lambda.model.AgentTask;
import com.aidriven.spi.model.OperationContext;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aidriven.lambda.platform.GitHubPlatformStrategy;
import com.aidriven.lambda.platform.JiraPlatformStrategy;
import com.aidriven.lambda.platform.PlatformStrategy;
import com.aidriven.lambda.platform.PlatformStrategyRegistry;
import com.aidriven.lambda.source.SourceControlClientResolver;

/**
 * Consumes agent tasks from SQS FIFO queue and executes the agent orchestrator.
 */
@Slf4j
@RequiredArgsConstructor
public class AgentProcessorHandler implements RequestHandler<SQSEvent, Void> {

    private final ObjectMapper objectMapper;
    private final ServiceFactory serviceFactory;
    private final JiraClient jiraClient;
    private final IdempotencyService idempotencyService;
    private final JiraCommentFormatter formatter;
    private final CommentIntentClassifier intentClassifier;
    private final ApprovalHandler approvalHandler;
    private final SourceControlClientResolver clientResolver;
    private final PlatformStrategyRegistry platformStrategyRegistry;
    private final ToolRegistryBuilder toolRegistryBuilder;

    public AgentProcessorHandler() {
        this.serviceFactory = ServiceFactory.getInstance();
        this.objectMapper = serviceFactory.getObjectMapper();
        this.jiraClient = serviceFactory.getJiraClient();
        this.idempotencyService = serviceFactory.getIdempotencyService();
        this.formatter = serviceFactory.getJiraCommentFormatter();
        this.approvalHandler = new ApprovalHandler(serviceFactory, jiraClient, formatter);
        this.clientResolver = new SourceControlClientResolver(this.serviceFactory);
        this.platformStrategyRegistry = new PlatformStrategyRegistry()
                .register(new JiraPlatformStrategy(this.jiraClient, this.formatter))
                .register(new GitHubPlatformStrategy());

        AgentConfig agentConfig = serviceFactory.getAppConfig().getAgentConfig();
        this.intentClassifier = agentConfig.classifierUseLlm()
                ? new CommentIntentClassifier(agentConfig.effectiveMentionKeyword(),
                        serviceFactory.getClaudeClient(), true)
                : new CommentIntentClassifier(agentConfig.effectiveMentionKeyword());
        this.toolRegistryBuilder = new ToolRegistryBuilder(serviceFactory, jiraClient);
    }

    // For testing
    public AgentProcessorHandler(ServiceFactory serviceFactory,
            ObjectMapper objectMapper,
            JiraClient jiraClient,
            IdempotencyService idempotencyService,
            ApprovalHandler approvalHandler) {
        this.serviceFactory = serviceFactory;
        this.objectMapper = objectMapper;
        this.jiraClient = jiraClient;
        this.idempotencyService = idempotencyService;
        this.formatter = serviceFactory.getJiraCommentFormatter();
        this.clientResolver = new SourceControlClientResolver(serviceFactory);
        this.platformStrategyRegistry = new PlatformStrategyRegistry()
                .register(new JiraPlatformStrategy(jiraClient, this.formatter))
                .register(new GitHubPlatformStrategy());
        this.intentClassifier = new CommentIntentClassifier();
        this.approvalHandler = approvalHandler;
        this.toolRegistryBuilder = new ToolRegistryBuilder(serviceFactory, jiraClient);
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                processMessage(message);
            } catch (Exception e) {
                log.error("Failed to process SQS message {}: {}", message.getMessageId(), e.getMessage(), e);
                // In SQS Lambda, throwing ensures retry if not using batch failure reporting
                throw new RuntimeException("Failed to process message", e);
            } finally {
                MDC.clear();
            }
        }
        return null;
    }

    private void processMessage(SQSEvent.SQSMessage message) throws Exception {
        AgentTask task = objectMapper.readValue(message.getBody(), AgentTask.class);

        String ticketKey = task.getTicketKey();
        String ackCommentId = task.getAckCommentId();
        OperationContext contextObj = task.getContext();
        String platform = task.getPlatform();

        log.info("Processing agent task for ticket={} (ack={})", ticketKey, ackCommentId);

        if (task.getCorrelationId() != null) {
            MDC.put("correlationId", task.getCorrelationId());
        }

        // Resolve source control client once — reused in both the happy path and error
        // handler.
        // Resolving again inside the catch block would trigger a redundant, potentially
        // failing Jira API call.
        SourceControlClient scClient = resolveSourceControlClient(task);

        try {
            // Check idempotency
            if (!idempotencyService.checkAndRecord(contextObj.tenantId(), ticketKey, ticketKey, ackCommentId)) {
                log.warn("Duplicate event detected via idempotency check: ticket={}, ack={}", ticketKey, ackCommentId);
                return;
            }

            // Check rate limits to prevent abuse / runaways
            idempotencyService.checkRateLimit(contextObj.tenantId());

            // Classify intent
            String rawCommentBody = task.getRawCommentBody() != null ? task.getRawCommentBody() : task.getCommentBody();
            CommentIntent intent = intentClassifier.classify(rawCommentBody, false);
            log.info("Classified intent for ticket={}: {}", ticketKey, intent);

            // Handle APPROVAL intent specially
            if (intent == CommentIntent.APPROVAL) {
                approvalHandler.handle(task, scClient);
                return;
            }

            PlatformStrategy platformStrategy = platformStrategyRegistry.get(platform);
            ProgressTracker progressTracker = platformStrategy.createProgressTracker(task, scClient);

            TicketInfo ticket;
            if ("GITHUB".equalsIgnoreCase(platform)) {
                ticket = TicketInfo.builder()
                        .ticketKey(ticketKey)
                        .summary("GitHub PR #" + ticketKey)
                        .status("OPEN")
                        .description("GitHub Pull Request. Review comments context.")
                        .labels(List.of())
                        .build();
            } else {
                ticket = jiraClient.getTicket(contextObj, ticketKey);
            }

            // Swarm Orchestration (Phase 2)
            SwarmOrchestrator orchestrator = serviceFactory.getSwarmOrchestrator(scClient, progressTracker);

            Map<String, String> prContext = new HashMap<>();
            if (task.getDiffHunk() != null)
                prContext.put("diffHunk", task.getDiffHunk());
            if (task.getFilePath() != null)
                prContext.put("filePath", task.getFilePath());
            if (task.getCommitId() != null)
                prContext.put("commitId", task.getCommitId());

            AgentRequest agentRequest = new AgentRequest(
                    ticketKey,
                    platform,
                    task.getCommentBody(),
                    task.getCommentAuthor(),
                    ticket,
                    progressTracker != null ? ackCommentId : null,
                    contextObj,
                    prContext);

            AgentResponse response = orchestrator.process(agentRequest, intent);

            // Final response dispatch — each strategy decides how to deliver the response
            String finalText;
            if (task.getParentCommentExcerpt() != null || task.getParentCommentAuthorAccountId() != null) {
                finalText = formatter.formatAsReply(
                        response.text(),
                        task.getParentCommentExcerpt(),
                        task.getParentCommentAuthorAccountId(),
                        response.toolsUsed(),
                        response.tokenCount());
            } else {
                finalText = formatter.format(
                        response.text(),
                        response.toolsUsed(),
                        response.tokenCount(),
                        task.getCommentAuthorAccountId());
            }
            platformStrategy.postFinalResponse(task, scClient, finalText);

            log.info("Agent task completed for ticket={} with {} turns, intent={}", ticketKey, response.turnCount(),
                    intent);

        } catch (Exception e) {
            log.error("Error during agent execution for ticket={}", ticketKey, e);
            String safeError = sanitizeError(e.getMessage());
            try {
                // Reuse already-resolved scClient — no second Jira API call.
                PlatformStrategy platformStrategy = platformStrategyRegistry.get(platform);
                ProgressTracker tracker = platformStrategy.createProgressTracker(task, scClient);
                tracker.fail(ackCommentId, safeError);
            } catch (Exception inner) {
                log.error("Failed to report error for ticket={}: {}", ticketKey, inner.getMessage());
            }
            throw e;
        }
    }

    private String sanitizeError(String message) {
        if (message == null)
            return "Unknown error occurred";
        // Basic sanitization — remove potentially sensitive path fragments or excessive
        // detail
        return message.replaceAll("/Users/[^/]+/", "/path/to/user/")
                .replaceAll("(?i)token=[^&\\s]+", "token=REDACTED");
    }

    private SourceControlClient resolveSourceControlClient(AgentTask task) throws Exception {
        if ("GITHUB".equalsIgnoreCase(task.getPlatform())) {
            return clientResolver.resolve("GITHUB", task.getRepoOwner(), task.getRepoSlug());
        }
        // For JIRA, resolve from ticket labels
        TicketInfo ticket = jiraClient.getTicket(task.getContext(), task.getTicketKey());
        return clientResolver.resolveFromLabels(ticket.getLabels());
    }
}
