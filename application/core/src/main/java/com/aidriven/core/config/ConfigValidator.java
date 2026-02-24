package com.aidriven.core.config;

import com.aidriven.core.exception.ConfigurationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates configuration completeness and correctness.
 * Extracted from AppConfig to adhere to Single Responsibility Principle.
 */
public final class ConfigValidator {

    private ConfigValidator() {
        // Utility class
    }

    /**
     * Validates that all required configuration is present.
     *
     * @param config The configuration to validate
     * @throws ConfigurationException if any required configuration is missing
     */
    public static void validate(AppConfig config) {
        List<String> missing = new ArrayList<>();

        if (config.getDynamoDbTableName() == null) {
            missing.add("DYNAMODB_TABLE_NAME");
        }
        if (config.getClaudeSecretArn() == null) {
            missing.add("CLAUDE_SECRET_ARN");
        }
        if (config.getBitbucketSecretArn() == null) {
            missing.add("BITBUCKET_SECRET_ARN");
        }
        if (config.getJiraSecretArn() == null) {
            missing.add("JIRA_SECRET_ARN");
        }
        if (config.getCodeContextBucket() == null) {
            missing.add("CODE_CONTEXT_BUCKET");
        }

        if (!missing.isEmpty()) {
            throw new ConfigurationException(
                    "Missing required environment variables: " + String.join(", ", missing));
        }
    }

    /**
     * Validates agent-specific configuration when agent mode is enabled.
     *
     * @param config The configuration to validate
     * @throws ConfigurationException if agent configuration is incomplete
     */
    public static void validateAgentConfig(AppConfig config) {
        AgentConfig agentConfig = config.getAgentConfig();

        if (!agentConfig.enabled()) {
            return; // Agent is disabled, no validation needed
        }

        List<String> missing = new ArrayList<>();

        if (agentConfig.queueUrl() == null || agentConfig.queueUrl().isBlank()) {
            missing.add("AGENT_QUEUE_URL");
        }

        if (!missing.isEmpty()) {
            throw new ConfigurationException(
                    "Agent mode is enabled but missing required configuration: " + String.join(", ", missing));
        }
    }

    /**
     * Validates MCP Gateway configuration when gateway is enabled.
     *
     * @param config The configuration to validate
     * @throws ConfigurationException if MCP Gateway configuration is incomplete
     */
    public static void validateMcpGatewayConfig(AppConfig config) {
        if (!config.isMcpGatewayEnabled()) {
            return; // Gateway is disabled, no validation needed
        }

        if (config.getMcpGatewayUrl().isEmpty()) {
            throw new ConfigurationException(
                    "MCP Gateway is enabled but MCP_GATEWAY_URL is not configured");
        }
    }

    /**
     * Performs full validation including all optional feature configurations.
     *
     * @param config The configuration to validate
     * @throws ConfigurationException if any configuration is invalid
     */
    public static void validateFull(AppConfig config) {
        validate(config);
        validateAgentConfig(config);
        validateMcpGatewayConfig(config);
    }
}
