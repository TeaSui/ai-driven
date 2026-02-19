package com.aidriven.contracts.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tenant-specific configuration for multi-tenant deployments.
 * <p>
 * Each tenant (company/client) has its own configuration specifying
 * which integrations are active, credentials references, and custom settings.
 * </p>
 *
 * <p>
 * In single-tenant mode, a default tenant configuration is used.
 * In multi-tenant mode, the tenant is resolved from the incoming request
 * (e.g., Jira project key prefix, API key, or webhook source).
 * </p>
 */
public record TenantConfiguration(
        String tenantId,
        String tenantName,
        SourceControlConfig sourceControl,
        IssueTrackerConfig issueTracker,
        AiModelConfig aiModel,
        List<String> enabledToolNamespaces,
        Map<String, String> customSettings) {

    /**
     * Source control configuration for a tenant.
     */
    public record SourceControlConfig(
            String platform,
            String secretArn,
            String defaultWorkspace,
            String defaultRepo) {
    }

    /**
     * Issue tracker configuration for a tenant.
     */
    public record IssueTrackerConfig(
            String platform,
            String secretArn,
            String baseUrl) {
    }

    /**
     * AI model configuration for a tenant.
     */
    public record AiModelConfig(
            String provider,
            String secretArn,
            String defaultModel,
            int maxTokens,
            double temperature) {
    }

    /**
     * Returns a custom setting value, or empty if not set.
     */
    public Optional<String> getSetting(String key) {
        if (customSettings == null) return Optional.empty();
        return Optional.ofNullable(customSettings.get(key));
    }

    /**
     * Checks if a tool namespace is enabled for this tenant.
     */
    public boolean isToolEnabled(String namespace) {
        if (enabledToolNamespaces == null || enabledToolNamespaces.isEmpty()) {
            return false;
        }
        return enabledToolNamespaces.contains(namespace);
    }
}
