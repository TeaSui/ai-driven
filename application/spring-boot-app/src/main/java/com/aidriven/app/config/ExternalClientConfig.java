package com.aidriven.app.config;

import com.aidriven.bitbucket.BitbucketClient;
import com.aidriven.claude.BedrockClient;
import com.aidriven.claude.ClaudeProvider;
import com.aidriven.claude.SpringAiClientAdapter;
import com.aidriven.core.agent.AiClient;
import com.aidriven.core.resilience.CircuitBreaker;
import com.aidriven.core.service.SecretsService;
import com.aidriven.github.GitHubClient;
import com.aidriven.jira.JiraClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.regions.Region;

/**
 * External API client beans: Jira, GitHub, Bitbucket, and Claude AI clients.
 *
 * <p>Replaces {@code ExternalClientFactory}'s lazy ConcurrentHashMap cache.
 * Provider routing logic (SPRING_AI vs BEDROCK) is preserved exactly as-is.
 *
 * <p>Four AI client variants are created (main/researcher/reviewer/tester)
 * using {@link Qualifier} annotations. The reviewer and tester clients use
 * the researcher model (typically Haiku) for cost efficiency.
 */
@Slf4j
@Configuration
public class ExternalClientConfig {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final int CIRCUIT_BREAKER_RESET_MS = 30_000;

    // ---- Circuit Breakers ----

    @Bean
    CircuitBreaker jiraCircuitBreaker() {
        return new CircuitBreaker("Jira", CIRCUIT_BREAKER_THRESHOLD, CIRCUIT_BREAKER_RESET_MS);
    }

    @Bean
    CircuitBreaker gitHubCircuitBreaker() {
        return new CircuitBreaker("GitHub", CIRCUIT_BREAKER_THRESHOLD, CIRCUIT_BREAKER_RESET_MS);
    }

    // ---- Jira ----

    @Bean
    @Lazy
    JiraClient jiraClient(SecretsService secretsService, CircuitBreaker jiraCircuitBreaker,
                           AppProperties properties) {
        return JiraClient.fromSecrets(secretsService, properties.jira().secretArn())
                .withCircuitBreaker(jiraCircuitBreaker);
    }

    // ---- GitHub ----

    @Bean
    @Lazy
    GitHubClient gitHubClient(SecretsService secretsService, CircuitBreaker gitHubCircuitBreaker,
                               AppProperties properties) {
        String secretArn = properties.github().secretArn();
        if (secretArn == null || secretArn.isBlank()) {
            throw new IllegalStateException("ai-driven.github.secret-arn is not configured");
        }
        return GitHubClient.fromSecrets(secretsService, secretArn)
                .withCircuitBreaker(gitHubCircuitBreaker);
    }

    // ---- Bitbucket ----

    @Bean
    @Lazy
    BitbucketClient bitbucketClient(SecretsService secretsService, AppProperties properties) {
        return BitbucketClient.fromSecrets(secretsService, properties.bitbucket().secretArn());
    }

    // ---- AI Clients ----

    @Bean
    @Qualifier("mainAiClient")
    @Lazy
    AiClient mainAiClient(SecretsService secretsService, AppProperties properties) {
        AppProperties.ClaudeProperties claude = properties.claude();
        return createAiClient(secretsService, properties, claude.model(), claude.maxTokens());
    }

    @Bean
    @Qualifier("researcherAiClient")
    @Lazy
    AiClient researcherAiClient(SecretsService secretsService, AppProperties properties) {
        AppProperties.ClaudeProperties claude = properties.claude();
        return createAiClient(secretsService, properties, claude.researcherModel(), claude.researcherMaxTokens());
    }

    @Bean
    @Qualifier("reviewerAiClient")
    @Lazy
    AiClient reviewerAiClient(SecretsService secretsService, AppProperties properties) {
        AppProperties.ClaudeProperties claude = properties.claude();
        // Reviewer uses researcher model (typically Haiku) for cost efficiency
        return createAiClient(secretsService, properties, claude.researcherModel(), claude.researcherMaxTokens());
    }

    @Bean
    @Qualifier("testerAiClient")
    @Lazy
    AiClient testerAiClient(SecretsService secretsService, AppProperties properties) {
        AppProperties.ClaudeProperties claude = properties.claude();
        // Tester uses researcher model (typically Haiku) for cost efficiency
        return createAiClient(secretsService, properties, claude.researcherModel(), claude.researcherMaxTokens());
    }

    // ---- Provider Routing ----

    private AiClient createAiClient(SecretsService secretsService, AppProperties properties,
                                     String model, int maxTokens) {
        AppProperties.ClaudeProperties claude = properties.claude();
        String providerName = claude.provider() != null && !claude.provider().isBlank()
                ? claude.provider() : "SPRING_AI";
        ClaudeProvider provider = ClaudeProvider.fromString(providerName);

        log.info("Creating AiClient with provider={}, model={}", provider, model);

        return switch (provider) {
            case BEDROCK -> createBedrockClient(claude, model, maxTokens);
            case SPRING_AI -> createSpringAiClient(secretsService, claude, model, maxTokens);
        };
    }

    private AiClient createBedrockClient(AppProperties.ClaudeProperties claude,
                                          String model, int maxTokens) {
        String regionName = claude.bedrockRegion() != null && !claude.bedrockRegion().isBlank()
                ? claude.bedrockRegion() : "us-east-1";
        Region region = Region.of(regionName);
        return new BedrockClient(model, maxTokens, claude.temperature(), region);
    }

    private AiClient createSpringAiClient(SecretsService secretsService,
                                            AppProperties.ClaudeProperties claude,
                                            String model, int maxTokens) {
        SpringAiClientAdapter client = SpringAiClientAdapter.fromSecrets(
                secretsService, claude.secretArn());
        return client.withModel(model)
                .withMaxTokens(maxTokens)
                .withTemperature(claude.temperature());
    }
}
