package com.aidriven.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Centralized configuration management for the application.
 * Validates required environment variables on startup.
 */
public class AppConfig {

    private static final AppConfig INSTANCE = new AppConfig();

    public static AppConfig getInstance() {
        return INSTANCE;
    }

    private final String dynamoDbTableName;
    private final String claudeSecretArn;
    private final String bitbucketSecretArn;
    private final String jiraSecretArn;
    private final String codeContextBucket;
    private final String stateMachineArn;
    private final int maxContextForClaude;
    private final String claudeModel;
    private final int claudeMaxTokens;
    private final double claudeTemperature;
    private final String promptVersion;
    private final String branchPrefix;
    private final String gitHubSecretArn;
    private final String defaultPlatform;
    private final String defaultWorkspace;
    private final String defaultRepo;

    private AppConfig() {
        this.dynamoDbTableName = getRequiredEnv("DYNAMODB_TABLE_NAME");
        this.claudeSecretArn = getRequiredEnv("CLAUDE_SECRET_ARN");
        this.bitbucketSecretArn = getRequiredEnv("BITBUCKET_SECRET_ARN");
        this.jiraSecretArn = getRequiredEnv("JIRA_SECRET_ARN");
        this.codeContextBucket = getRequiredEnv("CODE_CONTEXT_BUCKET");
        // State Machine ARN is optional for non-entry-point handlers
        this.stateMachineArn = System.getenv("STATE_MACHINE_ARN");

        this.maxContextForClaude = getIntEnv("MAX_CONTEXT_FOR_CLAUDE", 700_000);
        this.claudeModel = getEnv("CLAUDE_MODEL", "claude-opus-4-6");
        this.claudeMaxTokens = getIntEnv("CLAUDE_MAX_TOKENS", 32768);
        this.claudeTemperature = getDoubleEnv("CLAUDE_TEMPERATURE", 0.2);
        this.promptVersion = getEnv("PROMPT_VERSION", "v1");
        this.branchPrefix = getEnv("BRANCH_PREFIX", "ai/");

        // Multi-platform configuration (all optional)
        this.gitHubSecretArn = System.getenv("GITHUB_SECRET_ARN");
        this.defaultPlatform = getEnv("DEFAULT_PLATFORM", "BITBUCKET");
        this.defaultWorkspace = System.getenv("DEFAULT_WORKSPACE");
        this.defaultRepo = System.getenv("DEFAULT_REPO");
    }

    public String getDynamoDbTableName() {
        return dynamoDbTableName;
    }

    public String getClaudeSecretArn() {
        return claudeSecretArn;
    }

    public String getBitbucketSecretArn() {
        return bitbucketSecretArn;
    }

    public String getJiraSecretArn() {
        return jiraSecretArn;
    }

    public String getCodeContextBucket() {
        return codeContextBucket;
    }

    public Optional<String> getStateMachineArn() {
        return Optional.ofNullable(stateMachineArn);
    }

    public int getMaxContextForClaude() {
        return maxContextForClaude;
    }

    public String getClaudeModel() {
        return claudeModel;
    }

    public int getClaudeMaxTokens() {
        return claudeMaxTokens;
    }

    public double getClaudeTemperature() {
        return claudeTemperature;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getBranchPrefix() {
        return branchPrefix;
    }

    public String getGitHubSecretArn() {
        return gitHubSecretArn;
    }

    public String getDefaultPlatform() {
        return defaultPlatform;
    }

    public String getDefaultWorkspace() {
        return defaultWorkspace;
    }

    public String getDefaultRepo() {
        return defaultRepo;
    }

    // Configurable Limits with Defaults

    public int getMaxFileSizeChars() {
        return getIntEnv("MAX_FILE_SIZE_CHARS", 100000);
    }

    public int getMaxTotalContextChars() {
        return getIntEnv("MAX_TOTAL_CONTEXT_CHARS", 3000000);
    }

    public ClaudeConfig getClaudeConfig() {
        return new ClaudeConfig(
                maxContextForClaude,
                claudeModel,
                claudeMaxTokens,
                claudeTemperature,
                promptVersion,
                claudeSecretArn);
    }

    public FetchConfig getFetchConfig() {
        return new FetchConfig(
                getMaxFileSizeChars(),
                (long) getMaxTotalContextChars(),
                500_000L, // Static default for now
                getEnv("CONTEXT_MODE", "FULL_REPO"));
    }

    public JiraConfig getJiraConfig() {
        return new JiraConfig(jiraSecretArn, stateMachineArn);
    }

    public BitbucketConfig getBitbucketConfig() {
        return new BitbucketConfig(bitbucketSecretArn);
    }

    public AgentConfig getAgentConfig() {
        return new AgentConfig(
                Boolean.parseBoolean(getEnv("AGENT_ENABLED", "false")),
                getRequiredEnv("AGENT_QUEUE_URL"),
                getIntEnv("AGENT_MAX_TURNS", 10),
                getIntEnv("AGENT_MAX_WALL_CLOCK_SECONDS", 600),
                getEnv("AGENT_TRIGGER_PREFIX", "@ai"),
                getIntEnv("AGENT_TOKEN_BUDGET", 50000),
                getIntEnv("AGENT_RECENT_MESSAGES_TO_KEEP", 2),
                Boolean.parseBoolean(getEnv("AGENT_GUARDRAILS_ENABLED", "true")),
                getIntEnv("AGENT_COST_BUDGET_PER_TICKET", 200000),
                Boolean.parseBoolean(getEnv("AGENT_CLASSIFIER_USE_LLM", "false")));
    }

    /**
     * MCP server configurations as JSON string from environment.
     * Format: JSON array of McpServerConfig objects.
     */
    public String getMcpServersConfig() {
        return getEnv("MCP_SERVERS_CONFIG", "[]");
    }

    public ContextMode getContextMode() {
        String mode = getEnv("CONTEXT_MODE", "FULL_REPO");
        try {
            return ContextMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ContextMode.FULL_REPO;
        }
    }

    /**
     * Validates that all required environment variables are set.
     * Should be called during Lambda cold start (e.g., in ServiceFactory),
     * not during class loading, so tests can load AppConfig without env vars.
     *
     * @throws IllegalStateException if any required variable is missing
     */
    public void validate() {
        List<String> missing = new ArrayList<>();
        if (dynamoDbTableName == null)
            missing.add("DYNAMODB_TABLE_NAME");
        if (claudeSecretArn == null)
            missing.add("CLAUDE_SECRET_ARN");
        if (bitbucketSecretArn == null)
            missing.add("BITBUCKET_SECRET_ARN");
        if (jiraSecretArn == null)
            missing.add("JIRA_SECRET_ARN");
        if (codeContextBucket == null)
            missing.add("CODE_CONTEXT_BUCKET");
        // AGENT_QUEUE_URL is required if AGENT_ENABLED is true, but we can't easily
        // check that coupling here without
        // reading env again. Ideally, we just check core infra envs here.
        // For Phase 2, let's make queue URL optional in validation to not break
        // existing tests,
        // but required in getAgentConfig if used.

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Missing required environment variables: " + String.join(", ", missing));
        }
    }

    /**
     * Helper to get required environment variable.
     * Returns null if the variable is not set; use {@link #validate()} to enforce
     * presence.
     */
    private String getRequiredEnv(String key) {
        String val = System.getenv(key);
        if (val == null || val.isBlank()) {
            return null;
        }
        return val;
    }

    private String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private int getIntEnv(String key, int defaultValue) {
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

    private double getDoubleEnv(String key, double defaultValue) {
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

    public enum ContextMode {
        FULL_REPO,
        INCREMENTAL
    }
}
