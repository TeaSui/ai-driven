package com.aidriven.lambda.factory;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.claude.BedrockClient;
import com.aidriven.claude.ClaudeClient;
import com.aidriven.claude.ClaudeProvider;
import com.aidriven.core.agent.AiClient;
import com.aidriven.core.config.AppConfig;
import com.aidriven.core.config.ClaudeConfig;
import com.aidriven.core.service.SecretsService;
import com.aidriven.core.source.Platform;
import com.aidriven.core.source.SourceControlClient;
import com.aidriven.core.resilience.CircuitBreaker;
import com.aidriven.github.GitHubClient;
import com.aidriven.jira.JiraClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.regions.Region;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Factory for creating external API clients (Jira, GitHub, Bitbucket, Claude).
 * Extracted from ServiceFactory to adhere to Single Responsibility Principle.
 *
 * <p>
 * Each client is lazily initialized and cached as a singleton to reuse
 * expensive HTTP connection pools across Lambda invocations.
 */
@Slf4j
@RequiredArgsConstructor
public class ExternalClientFactory {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final int CIRCUIT_BREAKER_RESET_MS = 30000;

    private final SecretsService secretsService;
    private final AppConfig appConfig;

    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private <T> T getCached(String key, Supplier<T> supplier) {
        return (T) cache.computeIfAbsent(key, k -> supplier.get());
    }

    // --- Jira ---

    public JiraClient jiraClient() {
        CircuitBreaker cb = jiraCircuitBreaker();
        return getCached("JiraClient",
                () -> JiraClient.fromSecrets(secretsService, appConfig.getJiraSecretArn())
                        .withCircuitBreaker(cb));
    }

    public CircuitBreaker jiraCircuitBreaker() {
        return getCached("JiraCircuitBreaker",
                () -> new CircuitBreaker("Jira", CIRCUIT_BREAKER_THRESHOLD, CIRCUIT_BREAKER_RESET_MS));
    }

    // --- GitHub ---

    public GitHubClient gitHubClient() {
        String secretArn = appConfig.getGitHubSecretArn();
        if (secretArn == null || secretArn.isBlank()) {
            throw new IllegalStateException("GITHUB_SECRET_ARN is not configured");
        }
        CircuitBreaker cb = gitHubCircuitBreaker();
        return getCached("GitHubClient",
                () -> GitHubClient.fromSecrets(secretsService, secretArn)
                        .withCircuitBreaker(cb));
    }

    public GitHubClient gitHubClient(String owner, String repo) {
        if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
            return gitHubClient();
        }
        return gitHubClient().withRepository(owner, repo);
    }

    public CircuitBreaker gitHubCircuitBreaker() {
        return getCached("GitHubCircuitBreaker",
                () -> new CircuitBreaker("GitHub", CIRCUIT_BREAKER_THRESHOLD, CIRCUIT_BREAKER_RESET_MS));
    }

    // --- Bitbucket ---

    public BitbucketClient bitbucketClient() {
        return getCached("BitbucketClient",
                () -> BitbucketClient.fromSecrets(secretsService, appConfig.getBitbucketSecretArn()));
    }

    public BitbucketClient bitbucketClient(String workspace, String repoSlug) {
        if (workspace == null || workspace.isBlank() || repoSlug == null || repoSlug.isBlank()) {
            return bitbucketClient();
        }
        return bitbucketClient().withRepository(workspace, repoSlug);
    }

    // --- Claude ---

    public AiClient claudeClient() {
        return getCached("AiClient",
                () -> createAiClient(appConfig.getClaudeConfig().model(), appConfig.getClaudeConfig().maxTokens()));
    }

    public AiClient researcherClient() {
        return getCached("ResearcherAiClient", () -> {
            ClaudeConfig config = appConfig.getClaudeConfig();
            return createAiClient(config.researcherModel(), config.researcherMaxTokens());
        });
    }

    private AiClient createAiClient(String model, int maxTokens) {
        ClaudeConfig config = appConfig.getClaudeConfig();
        ClaudeProvider provider = ClaudeProvider.fromString(config.provider());

        log.info("Creating AiClient with provider={}, model={}", provider, model);

        return switch (provider) {
            case ANTHROPIC_API -> createAnthropicClient(model, maxTokens);
            case BEDROCK -> createBedrockClient(model, maxTokens);
        };
    }

    private AiClient createAnthropicClient(String model, int maxTokens) {
        ClaudeClient client = ClaudeClient.fromSecrets(secretsService, appConfig.getClaudeSecretArn());
        return client.withModel(model)
                .withMaxTokens(maxTokens)
                .withTemperature(appConfig.getClaudeConfig().temperature());
    }

    private AiClient createBedrockClient(String model, int maxTokens) {
        software.amazon.awssdk.regions.Region region = software.amazon.awssdk.regions.Region
                .of(appConfig.getClaudeConfig().bedrockRegion());
        return new BedrockClient(model, maxTokens, appConfig.getClaudeConfig().temperature(), region);
    }

    // --- Unified Source Control ---

    public SourceControlClient sourceControlClient(Platform platform) {
        return switch (platform) {
            case GITHUB -> gitHubClient();
            case BITBUCKET -> bitbucketClient();
        };
    }

    public SourceControlClient sourceControlClient(Platform platform, String ownerOrWorkspace, String repo) {
        return switch (platform) {
            case GITHUB -> gitHubClient(ownerOrWorkspace, repo);
            case BITBUCKET -> bitbucketClient(ownerOrWorkspace, repo);
        };
    }
}
