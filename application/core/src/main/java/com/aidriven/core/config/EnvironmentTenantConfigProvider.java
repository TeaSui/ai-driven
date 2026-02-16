package com.aidriven.core.config;

import com.aidriven.spi.TenantContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

/**
 * Single-tenant configuration provider that reads from environment variables.
 *
 * <p>This is the backward-compatible provider for existing deployments.
 * It creates a single "default" tenant from the current environment
 * configuration, preserving full backward compatibility with the
 * existing AppConfig-based setup.</p>
 *
 * <p>For multi-tenant deployments, replace with a DynamoDB-backed provider.</p>
 */
@Slf4j
public class EnvironmentTenantConfigProvider implements TenantConfigProvider {

    private static final String DEFAULT_TENANT_ID = "default";

    private final TenantContext defaultContext;

    public EnvironmentTenantConfigProvider() {
        this.defaultContext = buildFromEnvironment();
        log.info("Initialized single-tenant config: {}", defaultContext);
    }

    @Override
    public Optional<TenantContext> resolve(String tenantId) {
        if (DEFAULT_TENANT_ID.equals(tenantId)) {
            return Optional.of(defaultContext);
        }
        return Optional.empty();
    }

    @Override
    public TenantContext getDefault() {
        return defaultContext;
    }

    private TenantContext buildFromEnvironment() {
        TenantContext.Builder builder = TenantContext.builder(DEFAULT_TENANT_ID)
                .tenantName("Default Tenant");

        // Issue tracker (Jira is always enabled in legacy mode)
        String jiraSecretArn = System.getenv("JIRA_SECRET_ARN");
        if (jiraSecretArn != null && !jiraSecretArn.isBlank()) {
            builder.moduleConfig("jira", Map.of("secretArn", jiraSecretArn));
        }

        // Source control
        String bbSecretArn = System.getenv("BITBUCKET_SECRET_ARN");
        if (bbSecretArn != null && !bbSecretArn.isBlank()) {
            builder.moduleConfig("bitbucket", Map.of("secretArn", bbSecretArn));
        }

        String ghSecretArn = System.getenv("GITHUB_SECRET_ARN");
        if (ghSecretArn != null && !ghSecretArn.isBlank()) {
            builder.moduleConfig("github", Map.of("secretArn", ghSecretArn));
        }

        // AI engine
        String claudeSecretArn = System.getenv("CLAUDE_SECRET_ARN");
        if (claudeSecretArn != null && !claudeSecretArn.isBlank()) {
            builder.moduleConfig("claude", Map.of(
                    "secretArn", claudeSecretArn,
                    "model", getEnv("CLAUDE_MODEL", "claude-opus-4-6"),
                    "maxTokens", getEnv("CLAUDE_MAX_TOKENS", "32768"),
                    "temperature", getEnv("CLAUDE_TEMPERATURE", "0.2")));
        }

        return builder.build();
    }

    private static String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
