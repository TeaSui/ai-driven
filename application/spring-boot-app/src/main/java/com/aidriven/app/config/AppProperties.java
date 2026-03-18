package com.aidriven.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe configuration properties for the AI-Driven application.
 * Maps to the {@code ai-driven} prefix in application.yml.
 *
 * <p>Replaces the static {@code AppConfig.getInstance()} / {@code ConfigLoader.loadFromEnv()}
 * pattern with Spring Boot's {@link ConfigurationProperties} binding.
 */
@ConfigurationProperties(prefix = "ai-driven")
public record AppProperties(
        JiraProperties jira,
        GitHubProperties github,
        BitbucketProperties bitbucket,
        ClaudeProperties claude,
        AwsProperties aws,
        AgentProperties agent,
        SlackProperties slack,
        CostProperties cost,
        ContextProperties context,
        McpProperties mcp
) {

    // ---- Jira ----

    public record JiraProperties(
            String secretArn,
            String webhookSecretArn,
            String webhookSecret
    ) {}

    // ---- GitHub ----

    public record GitHubProperties(
            String secretArn,
            String webhookSecretArn
    ) {}

    // ---- Bitbucket ----

    public record BitbucketProperties(
            String secretArn
    ) {}

    // ---- Claude / AI Provider ----

    public record ClaudeProperties(
            String secretArn,
            String model,
            String modelFallback,
            int maxTokens,
            double temperature,
            String promptVersion,
            String researcherModel,
            int researcherMaxTokens,
            String provider,
            String bedrockRegion,
            int maxContext
    ) {}

    // ---- AWS ----

    public record AwsProperties(
            String region,
            SqsProperties sqs,
            DynamoDbProperties dynamodb,
            S3Properties s3,
            StepFunctionsProperties stepFunctions
    ) {
        public record SqsProperties(String agentQueueUrl) {}
        public record DynamoDbProperties(String stateTable) {}
        public record S3Properties(
                String contextBucket,
                String auditBucket
        ) {}
        public record StepFunctionsProperties(String stateMachineArn) {}
    }

    // ---- Agent ----

    public record AgentProperties(
            boolean enabled,
            int maxTurns,
            int maxWallClockSeconds,
            String triggerPrefix,
            int tokenBudget,
            int recentMessagesToKeep,
            boolean guardrailsEnabled,
            int costBudgetPerTicket,
            boolean classifierUseLlm,
            String mentionKeyword,
            String botAccountId
    ) {}

    // ---- Slack ----

    public record SlackProperties(
            String webhookUrl,
            String channel,
            boolean fallbackToJira
    ) {}

    // ---- Cost Controls ----

    public record CostProperties(
            boolean costAwareMode,
            double monthlyBudgetUsd,
            int maxTokensPerTicket,
            int maxRequestsPerUserPerHour,
            int maxRequestsPerTicketPerHour
    ) {}

    // ---- Context / Fetch ----

    public record ContextProperties(
            String mode,
            int maxFileSizeChars,
            int maxTotalContextChars,
            long maxFileSizeBytes,
            int summarizationThreshold,
            String branchPrefix,
            String defaultPlatform,
            String defaultWorkspace,
            String defaultRepo
    ) {}

    // ---- MCP ----

    public record McpProperties(
            boolean gatewayEnabled,
            String gatewayUrl,
            String serversConfig
    ) {}
}
