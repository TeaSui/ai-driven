package com.aidriven.lambda;

import com.aidriven.core.model.AgentResult;
import com.aidriven.core.model.ProcessingStatus;
import com.aidriven.core.model.TicketState;
import com.aidriven.core.repository.TicketStateRepository;
import com.aidriven.core.service.SecretsService;
import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.jira.JiraClient;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.aidriven.core.exception.ConflictException;
import com.aidriven.core.exception.HttpClientException;
import com.aidriven.core.util.JsonPathExtractor;
import com.aidriven.core.util.LambdaInputValidator;
import com.aidriven.core.util.OutputSanitizer;

/**
 * Lambda handler for creating Pull Requests in Bitbucket.
 * Called after AI code generation (either agent-based or Bedrock-based).
 *
 * Input:
 * - ticketId, ticketKey: Jira ticket identifiers
 * - success: boolean indicating if code generation succeeded
 * - files: JSON string of generated files
 * - commitMessage, prTitle, prDescription: Git/PR metadata
 * - agentType: (optional) type of agent used, defaults to "bedrock"
 * - dryRun: (optional) if true, skip PR creation
 *
 * Output:
 * - ticketId, ticketKey
 * - prCreated: boolean
 * - prUrl: (if created) URL of the PR
 * - branchName: (if created) name of the feature branch
 */
@Slf4j
public class PrCreatorHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final ObjectMapper objectMapper;
    private final TicketStateRepository ticketStateRepository;
    private final SecretsService secretsService;
    private final String bitbucketSecretArn;
    private final String jiraSecretArn;

    public PrCreatorHandler() {
        this.objectMapper = new ObjectMapper();

        DynamoDbClient dynamoDbClient = DynamoDbClient.create();
        String tableName = System.getenv("DYNAMODB_TABLE_NAME");

        this.ticketStateRepository = new TicketStateRepository(dynamoDbClient, tableName);

        SecretsManagerClient secretsManagerClient = SecretsManagerClient.create();
        this.secretsService = new SecretsService(secretsManagerClient);

        this.bitbucketSecretArn = System.getenv("BITBUCKET_SECRET_ARN");
        this.jiraSecretArn = System.getenv("JIRA_SECRET_ARN");
    }

    // Constructor for testing
    PrCreatorHandler(ObjectMapper objectMapper, TicketStateRepository ticketStateRepository,
            SecretsService secretsService, String bitbucketSecretArn, String jiraSecretArn) {
        this.objectMapper = objectMapper;
        this.ticketStateRepository = ticketStateRepository;
        this.secretsService = secretsService;
        this.bitbucketSecretArn = bitbucketSecretArn;
        this.jiraSecretArn = jiraSecretArn;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> input, Context context) {
        LambdaInputValidator.requireNonEmptyInput(input, "PrCreatorHandler");

        String ticketId = LambdaInputValidator.requireString(input, "ticketId");
        String ticketKey = LambdaInputValidator.requireString(input, "ticketKey");
        String agentType = LambdaInputValidator.optionalString(input, "agentType", "bedrock");
        boolean success = Boolean.TRUE.equals(input.get("success"));

        log.info("PR Creator processing for ticket: {} (agentType={})", ticketKey, agentType);

        // Check for dry-run mode
        boolean dryRun = Boolean.TRUE.equals(input.get("dryRun"));

        if (dryRun) {
            return handleDryRun(ticketId, ticketKey, agentType, input);
        }

        if (!success) {
            log.warn("Agent did not succeed, skipping PR creation for ticket: {}", ticketKey);
            return Map.of(
                    "ticketId", ticketId,
                    "ticketKey", ticketKey,
                    "prCreated", false,
                    "dryRun", dryRun,
                    "reason", "Agent did not succeed");
        }

        try {
            // Parse generated files
            String filesJson = (String) input.get("files");
            List<AgentResult.GeneratedFile> files = objectMapper.readValue(
                    filesJson,
                    new TypeReference<List<AgentResult.GeneratedFile>>() {
                    });

            if (files == null || files.isEmpty()) {
                log.warn("No files to commit for ticket: {}", ticketKey);
                return Map.of(
                        "ticketId", ticketId,
                        "ticketKey", ticketKey,
                        "prCreated", false,
                        "dryRun", dryRun,
                        "reason", "No files generated");
            }

            String commitMessage = (String) input.get("commitMessage");
            String prTitle = (String) input.get("prTitle");
            String prDescription = (String) input.get("prDescription");

            // Create Bitbucket client
            BitbucketClient bitbucketClient;
            try {
                bitbucketClient = BitbucketClient.fromSecrets(secretsService, bitbucketSecretArn);
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize Bitbucket client: " + e.getMessage(), e);
            }

            // Get default branch
            String defaultBranch;
            try {
                defaultBranch = bitbucketClient.getDefaultBranch();
            } catch (HttpClientException e) {
                throw new RuntimeException(
                        String.format("Failed to get default branch for ticket %s: %s", ticketKey, e.getMessage()), e);
            } catch (JsonPathExtractor.JsonPathException e) {
                throw new RuntimeException(
                        String.format("Invalid response when getting default branch for ticket %s: %s", ticketKey, e.getMessage()), e);
            }

            String featureBranch = "ai/" + ticketKey.toLowerCase();

            // Create branch (Bitbucket returns 400 or 409 if branch already exists)
            try {
                bitbucketClient.createBranch(featureBranch, defaultBranch);
            } catch (ConflictException e) {
                log.warn("Branch {} already exists (409), continuing with existing branch", featureBranch);
            } catch (HttpClientException e) {
                String body = e.getResponseBody() != null ? e.getResponseBody().toLowerCase() : "";
                if (e.getStatusCode() == 400 && (body.contains("already exists") || body.contains("branch with that name"))) {
                    log.warn("Branch {} already exists (400), continuing with existing branch", featureBranch);
                } else {
                    log.error("Bitbucket createBranch failed: HTTP {} - Body: {}", e.getStatusCode(), e.getResponseBody());
                    throw new RuntimeException(
                            String.format("Failed to create branch '%s' for ticket %s: HTTP %d - %s",
                                    featureBranch, ticketKey, e.getStatusCode(), e.getResponseBody()), e);
                }
            }

            // Commit files (sanitize commit message)
            String sanitizedCommitMessage = OutputSanitizer.sanitizeCommitMessage(commitMessage);
            try {
                bitbucketClient.commitFiles(featureBranch, files, sanitizedCommitMessage);
            } catch (HttpClientException e) {
                log.error("Bitbucket API returned HTTP {} for commitFiles. Response body: {}",
                        e.getStatusCode(), e.getResponseBody());
                throw new RuntimeException(
                        String.format("Failed to commit %d files to branch '%s' for ticket %s: HTTP %d - %s",
                                files.size(), featureBranch, ticketKey, e.getStatusCode(), e.getResponseBody()), e);
            }

            // Create PR (sanitize AI-generated content before embedding)
            String sanitizedTitle = OutputSanitizer.sanitizePrTitle(
                    Objects.toString(prTitle, "Auto-generated changes"));
            String fullPrTitle = String.format("[AI] %s: %s", ticketKey, sanitizedTitle);

            String sanitizedDescription = OutputSanitizer.sanitizePrDescription(
                    Objects.toString(prDescription, "Auto-generated code changes."));
            String fullPrDescription = String.format(
                    "## Auto-generated by AI-Driven System\n\n" +
                            "**Ticket:** %s\n" +
                            "**Generated by:** %s\n" +
                            "**Files changed:** %d\n\n" +
                            "---\n\n%s",
                    ticketKey, agentType, files.size(), sanitizedDescription);

            BitbucketClient.PullRequestResult prResult;
            try {
                prResult = bitbucketClient.createPullRequest(
                        fullPrTitle,
                        fullPrDescription,
                        featureBranch,
                        defaultBranch);
            } catch (ConflictException e) {
                throw new RuntimeException(
                        String.format("PR already exists for branch '%s' (ticket %s): %s", featureBranch, ticketKey, e.getMessage()), e);
            } catch (HttpClientException e) {
                log.error("Bitbucket API returned HTTP {} for createPullRequest. Response body: {}",
                        e.getStatusCode(), e.getResponseBody());
                throw new RuntimeException(
                        String.format("Failed to create PR for branch '%s' -> '%s' (ticket %s): HTTP %d - %s",
                                featureBranch, defaultBranch, ticketKey, e.getStatusCode(), e.getResponseBody()), e);
            } catch (JsonPathExtractor.JsonPathException e) {
                log.error("Failed to parse Bitbucket createPullRequest response: {}", e.getJsonBody());
                throw new RuntimeException(
                        String.format("Invalid response when creating PR for ticket %s: %s", ticketKey, e.getMessage()), e);
            }

            // Update state to IN_REVIEW
            ticketStateRepository.save(
                    TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.IN_REVIEW)
                            .withAgentType(agentType)
                            .withPrDetails(prResult.url(), featureBranch));

            // Update Jira ticket to "In Review"
            try {
                JiraClient jiraClient = JiraClient.fromSecrets(secretsService, jiraSecretArn);

                var transitions = jiraClient.getTransitions(ticketKey);
                transitions.stream()
                        .filter(t -> t.toStatus().equalsIgnoreCase("In Review")
                                || t.toStatus().equalsIgnoreCase("Code Review"))
                        .findFirst()
                        .ifPresent(t -> {
                            try {
                                jiraClient.transitionTicket(ticketKey, t.id());
                            } catch (Exception e) {
                                log.warn("Failed to transition ticket to In Review", e);
                            }
                        });

                // Add comment with PR link
                jiraClient.addComment(ticketKey,
                        String.format("AI-Driven System created a Pull Request: %s", prResult.url()));

            } catch (Exception e) {
                log.warn("Failed to update Jira", e);
            }

            log.info("Created PR for ticket {}: {}", ticketKey, prResult.url());

            return Map.of(
                    "ticketId", ticketId,
                    "ticketKey", ticketKey,
                    "prCreated", true,
                    "prUrl", prResult.url(),
                    "branchName", featureBranch,
                    "dryRun", dryRun);

        } catch (Exception e) {
            // Log the full error chain for debugging
            String rootCauseMessage = e.getMessage();
            Throwable rootCause = e;
            while (rootCause.getCause() != null) {
                rootCause = rootCause.getCause();
            }
            if (rootCause != e) {
                rootCauseMessage = rootCauseMessage + " | Root cause: " + rootCause.getMessage();
            }
            log.error("Failed to create PR for ticket: {} - {}", ticketKey, rootCauseMessage, e);

            // Update state to FAILED with detailed error
            ticketStateRepository.save(
                    TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.FAILED)
                            .withAgentType(agentType)
                            .withError(rootCauseMessage));

            throw new RuntimeException("Failed to create PR: " + rootCauseMessage, e);
        }
    }

    private Map<String, Object> handleDryRun(String ticketId, String ticketKey,
            String agentType, Map<String, Object> input) {
        log.info("Dry-run mode: Skipping PR creation for ticket: {}", ticketKey);

        // Update state to TEST_COMPLETED
        ticketStateRepository.save(
                TicketState.forTicket(ticketId, ticketKey, ProcessingStatus.TEST_COMPLETED)
                        .withAgentType(agentType));

        // Add Jira comment with generated content summary
        try {
            JiraClient jiraClient = JiraClient.fromSecrets(secretsService, jiraSecretArn);
            String filesJson = (String) input.getOrDefault("files", "[]");

            // Count files for summary
            int fileCount = 0;
            try {
                List<?> filesList = objectMapper.readValue(filesJson, List.class);
                fileCount = filesList.size();
            } catch (Exception ignored) {
            }

            String comment = String.format(
                    "[AI-DRIVEN DRY-RUN TEST]\n\n" +
                            "Processed in TEST MODE. AI generated code but no PR was created.\n\n" +
                            "RESULTS:\n" +
                            "- Generator: %s\n" +
                            "- Would create branch: ai/%s\n" +
                            "- Commit message: %s\n" +
                            "- PR Title: %s\n" +
                            "- Files generated: %d\n\n" +
                            "Remove 'ai-test'/'dry-run'/'test-mode' label and re-trigger to create actual PR.",
                    agentType.toUpperCase(),
                    ticketKey.toLowerCase(),
                    Objects.toString(input.get("commitMessage"), "N/A"),
                    Objects.toString(input.get("prTitle"), "N/A"),
                    fileCount);
            jiraClient.addComment(ticketKey, comment);
        } catch (Exception e) {
            log.warn("Failed to add dry-run comment to Jira", e);
        }

        return Map.of(
                "ticketId", ticketId,
                "ticketKey", ticketKey,
                "prCreated", false,
                "dryRun", true,
                "reason", "Dry-run mode");
    }
}
