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
    private final SourceControlClient testSourceControlClient;
    private final String branchPrefix;
    private final ServiceFactory serviceFactory;

    /** No-arg constructor required by AWS Lambda runtime. */
    public PrCreatorHandler() {
        this(ServiceFactory.getInstance());
    }

    private PrCreatorHandler(ServiceFactory factory) {
        this.serviceFactory = factory;
        this.objectMapper = factory.getObjectMapper();
        this.ticketStateRepository = factory.getTicketStateRepository();
        this.jiraClient = factory.getJiraClient();
        this.branchPrefix = factory.getAppConfig().getBranchPrefix();
        this.testSourceControlClient = null;
    }

    /** Constructor for testing. */
    public PrCreatorHandler(ObjectMapper objectMapper, TicketStateRepository ticketStateRepository,
            JiraClient jiraClient, ServiceFactory factory, SourceControlClient testSourceControlClient,
            String branchPrefix) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.jiraClient = jiraClient;
        this.serviceFactory = factory;
        this.testSourceControlClient = testSourceControlClient;
        this.branchPrefix = branchPrefix;
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

            SourceControlClient client = resolveSourceControlClient(input, ticketKey);
            String defaultBranch = getDefaultBranch(client, ticketKey);
            String featureBranch = branchPrefix + ticketKey.toLowerCase();

            createFeatureBranch(client, featureBranch, defaultBranch, ticketKey);
            commitChanges(client, featureBranch, files, (String) input.get("commitMessage"), ticketKey);

            SourceControlClient.PullRequestResult prResult = createPullRequest(
                    client, ticketKey, agentType, featureBranch, defaultBranch,
                    (String) input.get("prTitle"), (String) input.get("prDescription"), files.size());

            updateTicketState(ticketId, ticketKey, agentType, prResult, featureBranch);
            updateJira(ticketKey, prResult.url());

            log.info("Successfully created PR for ticket {}: {}", ticketKey, prResult.url());
            return createSuccessOutput(ticketId, ticketKey, dryRun, prResult.url(), featureBranch,
                    input.get("platform"));

        } catch (Exception e) {
            return handleError(ticketId, ticketKey, agentType, e);
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

        if ("GITHUB".equalsIgnoreCase(platformStr)) {
            log.info("Using GitHub client for ticket {} (resolved repo={}/{})", ticketKey, repoOwner, repoSlug);
            return serviceFactory.getGitHubClient(repoOwner, repoSlug);
        } else {
            log.info("Using Bitbucket client for ticket {} (repo={}/{})", ticketKey, repoOwner, repoSlug);
            return serviceFactory.getBitbucketClient(repoOwner, repoSlug);
        }
    }

    private String getDefaultBranch(SourceControlClient client, String ticketKey) throws Exception {
        try {
            return client.getDefaultBranch();
        } catch (HttpClientException | JsonPathExtractor.JsonPathException e) {
            throw new RuntimeException(String.format("Failed to get default branch for ticket %s: %s",
                    ticketKey, e.getMessage()), e);
        }
    }

    private void createFeatureBranch(SourceControlClient client, String branch, String base, String ticketKey)
            throws Exception {
        try {
            client.createBranch(branch, base);
        } catch (ConflictException e) {
            log.warn("Branch {} already exists (409), continuing with existing branch", branch);
        } catch (HttpClientException e) {
            String body = e.getResponseBody() != null ? e.getResponseBody().toLowerCase() : "";
            if (e.getStatusCode() == 400
                    && (body.contains("already exists") || body.contains("branch with that name"))) {
                log.warn("Branch {} already exists (400), continuing with existing branch", branch);
            } else {
                log.error("createBranch failed for ticket {}: HTTP {} - Body: {}", ticketKey, e.getStatusCode(),
                        e.getResponseBody());
                throw new RuntimeException(String.format("Failed to create branch '%s' for ticket %s: HTTP %d - %s",
                        branch, ticketKey, e.getStatusCode(), e.getResponseBody()), e);
            }
        }
    }

    private void commitChanges(SourceControlClient client, String branch, List<AgentResult.GeneratedFile> files,
            String message, String ticketKey) throws Exception {
        String sanitizedMessage = Objects.toString(message, "AI-generated code fixes");
        try {
            client.commitFiles(branch, files, sanitizedMessage);
        } catch (HttpClientException e) {
            log.error("commitFiles failed for ticket {}: HTTP {} - Body: {}", ticketKey, e.getStatusCode(),
                    e.getResponseBody());
            throw new RuntimeException(
                    String.format("Failed to commit %d files to branch '%s' for ticket %s: HTTP %d - %s",
                            files.size(), branch, ticketKey, e.getStatusCode(), e.getResponseBody()),
                    e);
        }
    }

    private SourceControlClient.PullRequestResult createPullRequest(SourceControlClient client, String ticketKey,
            String agentType, String branch, String base, String title, String description, int fileCount)
            throws Exception {

        String fullTitle = buildPrTitle(ticketKey, title);
        String fullDescription = buildPrDescription(ticketKey, agentType, fileCount, description);

        try {
            return client.createPullRequest(fullTitle, fullDescription, branch, base);
        } catch (HttpClientException | JsonPathExtractor.JsonPathException e) {
            log.error("createPullRequest failed for ticket {}: {}", ticketKey, e.getMessage());
            throw new RuntimeException(String.format("Failed to create PR for branch '%s' (ticket %s): %s",
                    branch, ticketKey, e.getMessage()), e);
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
        return String.format(
                "## Auto-generated by AI-Driven System\n\n" +
                        "**Ticket:** %s\n" +
                        "**Generated by:** %s\n" +
                        "**Files changed:** %d\n\n" +
                        "---\n\n%s",
                ticketKey, agentType, fileCount, baseDescription);
    }

    private void updateTicketState(String ticketId, String ticketKey, String agentType,
            SourceControlClient.PullRequestResult prResult, String branch) {
        ticketStateRepository.save(
                TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.IN_REVIEW)
                        .withAgentType(agentType)
                        .withPrDetails(prResult.url(), branch));
    }

    private void updateJira(String ticketKey, String prUrl) {
        try {
            var transitions = jiraClient.getTransitions(ticketKey);
            transitions.stream()
                    .filter(t -> t.toStatus().equalsIgnoreCase("In Review")
                            || t.toStatus().equalsIgnoreCase("Code Review"))
                    .findFirst()
                    .ifPresent(t -> {
                        try {
                            jiraClient.transitionTicket(ticketKey, t.id());
                        } catch (Exception e) {
                            log.warn("Failed to transition ticket {} to In Review", ticketKey, e);
                        }
                    });

            jiraClient.addComment(ticketKey, String.format("AI-Driven System created a Pull Request: %s", prUrl));
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

    private Map<String, Object> handleError(String ticketId, String ticketKey, String agentType, Exception e) {
        String message = e.getMessage();
        Throwable root = e;
        while (root.getCause() != null)
            root = root.getCause();
        if (root != e)
            message += " | Root cause: " + root.getMessage();

        log.error("Failed to create PR for ticket: {} - {}", ticketKey, message, e);

        ticketStateRepository.save(
                TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.FAILED)
                        .withAgentType(agentType)
                        .withError(message));

        throw new RuntimeException("Failed to create PR: " + message, e);
    }

    private Map<String, Object> handleDryRun(String ticketId, String ticketKey, String agentType,
            Map<String, Object> input) {
        log.info("Dry-run mode: Skipping PR creation for ticket: {}", ticketKey);
        ticketStateRepository.save(
                TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.TEST_COMPLETED).withAgentType(agentType));

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
            jiraClient.addComment(ticketKey, comment);
        } catch (Exception e) {
            log.warn("Failed to add dry-run comment to Jira for ticket {}", ticketKey, e);
        }

        return createBaseOutput(ticketId, ticketKey, false, true, "Dry-run mode");
    }
}
