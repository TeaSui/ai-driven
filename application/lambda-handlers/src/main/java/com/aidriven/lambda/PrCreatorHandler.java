package com.aidriven.lambda;

import com.aidriven.core.exception.ConflictException;
import com.aidriven.core.exception.HttpClientException;
import com.aidriven.core.model.AgentResult;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.logging.LoggingUtils;
import software.amazon.lambda.powertools.tracing.Tracing;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.util.JsonPathExtractor;
import com.aidriven.jira.JiraClient;
import com.aidriven.lambda.factory.ServiceFactory;
import com.aidriven.lambda.source.SourceControlClientResolver;
import com.aidriven.spi.model.BranchName;
import com.aidriven.spi.model.OperationContext;
import com.aidriven.spi.provider.SourceControlProvider;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lambda handler for creating Pull Requests via the source control platform.
 * Supports both Bitbucket and GitHub through the {@link SourceControlClient}
 * interface.
 */
@Slf4j
@RequiredArgsConstructor
public class PrCreatorHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final JiraClient jiraClient;
    private final com.aidriven.core.agent.ConversationRepository conversationRepository;
    private final SourceControlClient testSourceControlClient;
    private final String branchPrefix;
    private final ServiceFactory serviceFactory;
    private final SourceControlClientResolver clientResolver;

    /** No-arg constructor required by AWS Lambda runtime. */
    public PrCreatorHandler() {
        this(ServiceFactory.getInstance());
    }

    private PrCreatorHandler(ServiceFactory factory) {
        this.serviceFactory = factory;
        this.objectMapper = factory.getObjectMapper();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.jiraClient = factory.getJiraClient();
        this.conversationRepository = factory.getConversationRepository();
        this.branchPrefix = factory.getAppConfig().getBranchPrefix();
        this.testSourceControlClient = null;
        this.clientResolver = new SourceControlClientResolver(factory);
    }

    /** Constructor for testing. */
    public PrCreatorHandler(ObjectMapper objectMapper, TicketStateRepository ticketStateRepository,
            JiraClient jiraClient, com.aidriven.core.agent.ConversationRepository conversationRepository,
            ServiceFactory factory, SourceControlClient testSourceControlClient,
            String branchPrefix) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.jiraClient = jiraClient;
        this.conversationRepository = conversationRepository;
        this.serviceFactory = factory;
        this.testSourceControlClient = testSourceControlClient;
        this.branchPrefix = branchPrefix;
        this.clientResolver = new SourceControlClientResolver(factory);
    }

    @Override
    @Logging(logEvent = true)
    @Tracing
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {

        String ticketId = (String) input.get("ticketId");
        String ticketKey = (String) input.get("ticketKey");
        String agentType = (String) input.getOrDefault("agentType", "bedrock");
        boolean dryRun = Boolean.TRUE.equals(input.get("dryRun"));
        boolean success = Boolean.TRUE.equals(input.get("success"));

        LoggingUtils.appendKey("ticketKey", ticketKey);
        LoggingUtils.appendKey("correlationId", context.getAwsRequestId());

        log.info("PR Creator processing for ticket: {} (agentType={}, dryRun={})", ticketKey, agentType, dryRun);

        if (dryRun) {
            return handleDryRun(ticketId, ticketKey, agentType, input);
        }

        if (!success) {
            log.warn("Agent did not succeed, skipping PR creation for ticket: {}", ticketKey);
            return createBaseOutput(ticketId, ticketKey, false, dryRun, "Agent did not succeed");
        }

        try {
            List<AgentResult.GeneratedFile> files = parseFiles(input);
            if (files.isEmpty()) {
                log.warn("No files to commit for ticket: {}", ticketKey);
                return createBaseOutput(ticketId, ticketKey, false, dryRun, "No files generated");
            }

            OperationContext opContext = extractOperationContext(input);
            SourceControlClient client = resolveSourceControlClient(input, ticketKey);
            BranchName defaultBranch = client.getDefaultBranch(opContext);
            BranchName featureBranch = BranchName.of(branchPrefix + ticketKey.toLowerCase());

            if (!createFeatureBranch(opContext, client, featureBranch, defaultBranch, ticketKey)) {
                log.info("Stopping session for ticket {} because branch {} already exists", ticketKey, featureBranch);

                // Check if we already posted this warning to avoid duplicates
                String pk = TicketState.createPk(opContext.tenantId(), ticketId);
                String sk = TicketState.createCurrentStateSk();
                boolean alreadyWarned = ticketStateRepository.get(pk, sk)
                        .map(state -> ProcessingStatus.FAILED.getValue().equals(state.getStatus())
                                && state.getErrorMessage() != null
                                && state.getErrorMessage().contains("already exists"))
                        .orElse(false);

                if (!alreadyWarned) {
                    String message = String
                            .format("Branch '%s' already exists. To avoid overwriting manual work, the AI has stopped. "
                                    +
                                    "Delete the branch or remove the trigger label if you want to abort.",
                                    featureBranch.value());
                    jiraClient.postComment(opContext, ticketKey, "[AI WARN] " + message);

                    // Save state as FAILED with this error so we don't repeat
                    ticketStateRepository.save(
                            TicketState.forTicket(opContext.tenantId(), ticketId, ticketKey,
                                    ProcessingStatus.FAILED)
                                    .withError("Branch already exists: " + featureBranch.value()));
                }

                return createBaseOutput(ticketId, ticketKey, false, dryRun, "Branch already exists");
            }

            commitChanges(opContext, client, featureBranch, files, (String) input.get("commitMessage"), ticketKey);

            String prTitle = buildPrTitle(ticketKey, (String) input.get("prTitle"));
            String prDescription = buildPrDescription(ticketKey, agentType, files.size(),
                    (String) input.get("prDescription"));
            SourceControlProvider.PullRequestResult prResult = client.createPullRequest(
                    opContext, prTitle, prDescription, featureBranch, defaultBranch);

            updateTicketState(opContext.tenantId(), ticketId, ticketKey, agentType, prResult, featureBranch.value());
            updateJira(opContext, ticketKey, prResult.url());
            persistConversationContext(opContext, ticketKey, prResult, agentType);

            log.info("Successfully created PR for ticket {}: {}", ticketKey, prResult.url());
            return createSuccessOutput(ticketId, ticketKey, dryRun, prResult.url(), featureBranch.value(),
                    input.get("platform"));

        } catch (Exception e) {
            OperationContext opContext = extractOperationContext(input);
            return handleError(opContext.tenantId(), ticketId, ticketKey, agentType, e);
        } finally {
            // Context cleared by Powertools
        }
    }

    private List<AgentResult.GeneratedFile> parseFiles(Map<String, Object> input) throws Exception {
        String filesJson = (String) input.get("files");
        if (filesJson == null || filesJson.isBlank()) {
            return List.of();
        }
        return objectMapper.readValue(filesJson, new TypeReference<List<AgentResult.GeneratedFile>>() {
        });
    }

    private SourceControlClient resolveSourceControlClient(Map<String, Object> input, String ticketKey) {
        if (this.testSourceControlClient != null) {
            return this.testSourceControlClient;
        }
        String platformStr = (String) input.get("platform");
        String repoOwner = (String) input.get("repoOwner");
        String repoSlug = (String) input.get("repoSlug");
        log.info("Resolving source control client for ticket {} (platform={}, repo={}/{})",
                ticketKey, platformStr, repoOwner, repoSlug);
        return clientResolver.resolve(platformStr, repoOwner, repoSlug);
    }

    private boolean createFeatureBranch(OperationContext context, SourceControlClient client, BranchName branch,
            BranchName base,
            String ticketKey)
            throws Exception {
        try {
            client.createBranch(context, branch, base);
            return true;
        } catch (ConflictException e) {
            log.warn("Branch {} already exists (409)", branch.value());
            return false;
        } catch (HttpClientException e) {
            String body = e.getResponseBody() != null ? e.getResponseBody().toLowerCase() : "";
            int status = e.getStatusCode();
            if (status == 422 && body.contains("already exists")) {
                log.warn("Branch {} already exists (422 github)", branch.value());
                return false;
            } else if (status == 400 && (body.contains("already exists") || body.contains("branch with that name"))) {
                log.warn("Branch {} already exists (400 bitbucket)", branch.value());
                return false;
            } else {
                log.error("createBranch failed for ticket {}: HTTP {} - Body: {}", ticketKey, status,
                        e.getResponseBody());
                throw new RuntimeException(String.format("Failed to create branch '%s' for ticket %s: HTTP %d - %s",
                        branch.value(), ticketKey, status, e.getResponseBody()), e);
            }
        }
    }

    private void commitChanges(OperationContext context, SourceControlClient client, BranchName branch,
            List<AgentResult.GeneratedFile> files,
            String message, String ticketKey) throws Exception {
        String sanitizedMessage = Objects.toString(message, "AI-generated code fixes");
        try {
            client.commitFiles(context, branch, files, sanitizedMessage);
        } catch (HttpClientException e) {
            log.error("commitFiles failed for ticket {}: HTTP {} - Body: {}", ticketKey, e.getStatusCode(),
                    e.getResponseBody());
            throw new RuntimeException(
                    String.format("Failed to commit %d files to branch '%s' for ticket %s: HTTP %d - %s",
                            files.size(), branch.value(), ticketKey, e.getStatusCode(), e.getResponseBody()),
                    e);
        }
    }

    private String buildPrTitle(String ticketKey, String title) {
        String baseTitle = Objects.toString(title, "Auto-generated changes");
        if (baseTitle.toUpperCase().startsWith(ticketKey.toUpperCase())) {
            return String.format("[AI] %s", baseTitle);
        }
        return String.format("[AI] %s: %s", ticketKey, baseTitle);
    }

    private String buildPrDescription(String ticketKey, String agentType, int fileCount, String description) {
        String baseDescription = Objects.toString(description, "Auto-generated code changes.");

        String footer = String.format(
                "---\n\n" +
                        "🤖 This pull request was generated by AI (model: %s)\n" +
                        "Ticket: %s | Generated: %s",
                agentType, ticketKey, java.time.Instant.now().toString());

        if (baseDescription.contains("🤖 This pull request was generated by AI")) {
            return baseDescription; // Footer already present
        }

        return String.format(
                "## Auto-generated by AI-Driven System\n\n" +
                        "**Ticket:** %s\n" +
                        "**Generated by:** %s\n" +
                        "**Files changed:** %d\n\n" +
                        "%s\n\n%s",
                ticketKey, agentType, fileCount, baseDescription, footer);
    }

    private void updateTicketState(String tenantId, String ticketId, String ticketKey, String agentType,
            SourceControlProvider.PullRequestResult prResult, String branch) {
        ticketStateRepository.save(
                TicketState.forTicket(tenantId, ticketId, ticketKey, ProcessingStatus.IN_REVIEW)
                        .withAgentType(agentType)
                        .withPrDetails(prResult.url(), branch));
    }

    private void updateJira(OperationContext context, String ticketKey, String prUrl) {
        try {
            var transitions = jiraClient.getTransitions(context, ticketKey);
            transitions.stream()
                    .filter(t -> t.toStatus().equalsIgnoreCase("In Review")
                            || t.toStatus().equalsIgnoreCase("Code Review"))
                    .findFirst()
                    .ifPresent(t -> {
                        try {
                            jiraClient.transitionTicket(context, ticketKey, t.id());
                        } catch (Exception e) {
                            log.warn("Failed to transition ticket {} to In Review", ticketKey, e);
                        }
                    });

            jiraClient.postComment(context, ticketKey,
                    String.format("AI-Driven System created a Pull Request: %s", prUrl));
        } catch (Exception e) {
            log.warn("Failed to update Jira for ticket {}", ticketKey, e);
        }
    }

    private Map<String, Object> createBaseOutput(String id, String key, boolean prCreated, boolean dryRun,
            String reason) {
        Map<String, Object> out = new HashMap<>();
        out.put("ticketId", id);
        out.put("ticketKey", key);
        out.put("prCreated", prCreated);
        out.put("dryRun", dryRun);
        if (reason != null)
            out.put("reason", reason);
        return out;
    }

    private Map<String, Object> createSuccessOutput(String id, String key, boolean dryRun, String url, String branch,
            Object platform) {
        Map<String, Object> out = createBaseOutput(id, key, true, dryRun, null);
        out.put("prUrl", url);
        out.put("branchName", branch);
        if (platform != null)
            out.put("platform", platform);
        return out;
    }

    private Map<String, Object> handleError(String tenantId, String ticketId, String ticketKey, String agentType,
            Exception e) {
        String message = e.getMessage();
        Throwable root = e;
        while (root.getCause() != null)
            root = root.getCause();
        if (root != e)
            message += " | Root cause: " + root.getMessage();

        log.error("Failed to create PR for ticket: {} - {}", ticketKey, message, e);

        ticketStateRepository.save(
                TicketState.forTicket(tenantId, ticketId, ticketKey, ProcessingStatus.FAILED)
                        .withAgentType(agentType)
                        .withError(message));

        throw new RuntimeException("Failed to create PR: " + message, e);
    }

    private Map<String, Object> handleDryRun(String ticketId, String ticketKey, String agentType,
            Map<String, Object> input) {
        log.info("Dry-run mode: Skipping PR creation for ticket: {}", ticketKey);
        OperationContext opContext = extractOperationContext(input);
        ticketStateRepository.save(
                TicketState.forTicket(opContext.tenantId(), ticketId, ticketKey, ProcessingStatus.TEST_COMPLETED)
                        .withAgentType(agentType));

        try {
            int fileCount = parseFiles(input).size();
            String comment = String.format(
                    "[AI-DRIVEN DRY-RUN TEST]\n\n" +
                            "Processed in TEST MODE. AI generated code but no PR was created.\n\n" +
                            "RESULTS:\n" +
                            "- Generator: %s\n" +
                            "- Would create branch: %s%s\n" +
                            "- Commit message: %s\n" +
                            "- PR Title: %s\n" +
                            "- Files generated: %d\n\n" +
                            "Remove 'ai-test'/'dry-run'/'test-mode' label and re-trigger to create actual PR.",
                    agentType.toUpperCase(), branchPrefix, ticketKey.toLowerCase(),
                    Objects.toString(input.get("commitMessage"), "N/A"),
                    Objects.toString(input.get("prTitle"), "N/A"), fileCount);
            jiraClient.postComment(opContext, ticketKey, comment);
        } catch (Exception e) {
            log.warn("Failed to add dry-run comment to Jira for ticket {}", ticketKey, e);
        }

        return createBaseOutput(ticketId, ticketKey, false, true, "Dry-run mode");
    }

    private void persistConversationContext(OperationContext context, String ticketKey,
            SourceControlProvider.PullRequestResult prResult, String agentType) {
        try {
            String summary = String.format(
                    "I have automatically created a Pull Request for ticket %s.\n\n" +
                            "**PR URL:** %s\n" +
                            "**Branch:** %s\n" +
                            "**Status:** Generated code and pushed changes.",
                    ticketKey, prResult.url(), prResult.url()); // prResult doesn't have branch easily here but it's
                                                                // in the URL usually

            // Construct Claude-compatible content blocks
            List<Map<String, String>> blocks = List.of(Map.of("type", "text", "text", summary));
            String contentJson = objectMapper.writeValueAsString(blocks);

            com.aidriven.core.agent.model.ConversationMessage msg = com.aidriven.core.agent.model.ConversationMessage
                    .builder()
                    .pk(com.aidriven.core.agent.model.ConversationMessage.createPk(context.tenantId(), ticketKey))
                    .sk(com.aidriven.core.agent.model.ConversationMessage.createSk(java.time.Instant.now(), 0))
                    .role("assistant")
                    .author("ai-agent-" + agentType)
                    .contentJson(contentJson)
                    .timestamp(java.time.Instant.now())
                    .ttl(com.aidriven.core.agent.model.ConversationMessage.defaultTtl())
                    .build();

            conversationRepository.save(msg);
            log.info("Persisted PR creation context to conversation history for {}", ticketKey);
        } catch (Exception e) {
            log.warn("Failed to persist conversation context for {}: {}", ticketKey, e.getMessage());
        }
    }

    private OperationContext extractOperationContext(Map<String, Object> input) {
        if (!input.containsKey("context") || !(input.get("context") instanceof Map)) {
            return OperationContext.builder().tenantId("default").build();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) input.get("context");
        String tenantId = (String) context.getOrDefault("tenantId", "default");
        String userId = (String) context.getOrDefault("userId", "system");

        return OperationContext.builder()
                .tenantId(tenantId)
                .userId(userId)
                .build();
    }
}
