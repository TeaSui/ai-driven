package com.aidriven.core.config;

/**
 * Loads configuration from environment variables.
 * Extracted from AppConfig to adhere to Single Responsibility Principle.
 */
public final class ConfigLoader {

    private ConfigLoader() {
        // Utility class
    }

    /**
     * Loads AppConfig from environment variables.
     *
     * @return A fully populated AppConfig instance
     */
    public static AppConfig loadFromEnv() {
        return AppConfig.builder()
                .dynamoDbTableName(getRequiredEnv("DYNAMODB_TABLE_NAME"))
                .claudeSecretArn(getRequiredEnv("CLAUDE_SECRET_ARN"))
                .bitbucketSecretArn(getRequiredEnv("BITBUCKET_SECRET_ARN"))
                .jiraSecretArn(getRequiredEnv("JIRA_SECRET_ARN"))
                .codeContextBucket(getRequiredEnv("CODE_CONTEXT_BUCKET"))
                .stateMachineArn(System.getenv("STATE_MACHINE_ARN"))
                .maxContextForClaude(getIntEnv("MAX_CONTEXT_FOR_CLAUDE", 700_000))
                .claudeModel(getEnv("CLAUDE_MODEL", "claude-sonnet-4-6"))
                .claudeModelFallback(getEnv("CLAUDE_MODEL_FALLBACK", "claude-sonnet-4-6"))
                .claudeMaxTokens(getIntEnv("CLAUDE_MAX_TOKENS", 32768))
                .claudeTemperature(getDoubleEnv("CLAUDE_TEMPERATURE", 0.2))
                .promptVersion(getEnv("PROMPT_VERSION", "v1"))
                .claudeProvider(getEnv("CLAUDE_PROVIDER", "BEDROCK")) // ANTHROPIC_API - BEDROCK
                .bedrockRegion(getEnv("BEDROCK_REGION", "ap-southeast-1"))
                .branchPrefix(getEnv("BRANCH_PREFIX", "ai/"))
                .gitHubSecretArn(System.getenv("GITHUB_SECRET_ARN"))
                .jiraWebhookSecret(System.getenv("JIRA_WEBHOOK_SECRET"))
                .jiraWebhookSecretArn(System.getenv("JIRA_WEBHOOK_SECRET_ARN"))
                .gitHubAgentWebhookSecretArn(System.getenv("GITHUB_AGENT_WEBHOOK_SECRET_ARN"))
                .defaultPlatform(getEnv("DEFAULT_PLATFORM", "BITBUCKET"))
                .defaultWorkspace(System.getenv("DEFAULT_WORKSPACE"))
                .defaultRepo(System.getenv("DEFAULT_REPO"))
                .maxFileSizeChars(getIntEnv("MAX_FILE_SIZE_CHARS", 100_000))
                .maxTotalContextChars(getIntEnv("MAX_TOTAL_CONTEXT_CHARS", 3_000_000))
                // ADR-013: INCREMENTAL is the default context strategy.
                .contextMode(parseContextMode(getEnv("CONTEXT_MODE", "INCREMENTAL")))
                .maxFileSizeBytes(getLongEnv("MAX_FILE_SIZE_BYTES", 500_000L))
                .slackWebhookUrl(System.getenv("SLACK_WEBHOOK_URL"))
                .slackChannel(System.getenv("SLACK_CHANNEL"))
                .slackFallbackToJira(getBooleanEnv("SLACK_FALLBACK_TO_JIRA", true))
                // impl-12: Cost controls
                .costAwareMode(getBooleanEnv("COST_AWARE_MODE", true))
                .monthlyBudgetUsd(getDoubleEnv("MONTHLY_BUDGET_USD", 100.0))
                .maxTokensPerTicket(getIntEnv("MAX_TOKENS_PER_TICKET", 200_000))
                .maxRequestsPerUserPerHour(getIntEnv("MAX_REQUESTS_PER_USER_PER_HOUR", 10))
                .maxRequestsPerTicketPerHour(getIntEnv("MAX_REQUESTS_PER_TICKET_PER_HOUR", 20))
                .build();
    }

    /**
     * Gets a required environment variable.
     * Returns null if not set (validation happens separately).
     */
    public static String getRequiredEnv(String key) {
        String val = System.getenv(key);
        return (val == null || val.isBlank()) ? null : val;
    }

    /**
     * Gets an environment variable with a default value.
     */
    public static String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    /**
     * Gets an integer environment variable with a default value.
     */
    public static int getIntEnv(String key, int defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Gets a double environment variable with a default value.
     */
    public static double getDoubleEnv(String key, double defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Gets a boolean environment variable with a default value.
     */
    public static boolean getBooleanEnv(String key, boolean defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * Gets a long environment variable with a default value.
     */
    public static long getLongEnv(String key, long defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Parses the CONTEXT_MODE env var into a ContextMode enum.
     * Defaults to INCREMENTAL (ADR-013) on missing or invalid values.
     */
    private static AppConfig.ContextMode parseContextMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return AppConfig.ContextMode.INCREMENTAL;
        }
        try {
            return AppConfig.ContextMode.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Log is not available in static context here; warn is handled at AppConfig
            // level.
            // Safe fallback to INCREMENTAL per ADR-013.
            return AppConfig.ContextMode.INCREMENTAL;
        }
    }
}
