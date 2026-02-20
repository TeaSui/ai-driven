package com.aidriven.spi.tenant;

import java.util.*;

/**
 * Immutable tenant configuration.
 *
 * <p>Each tenant (company) has its own configuration specifying which modules
 * to use, credentials, and custom settings. This is loaded from a configuration
 * source (DynamoDB, JSON file, environment) at startup.</p>
 *
 * <p>Example JSON:</p>
 * <pre>{@code
 * {
 *   "tenantId": "acme-corp",
 *   "modules": {
 *     "source-control": "github",
 *     "issue-tracker": "jira",
 *     "ai-provider": "claude"
 *   },
 *   "config": {
 *     "github.owner": "acme-corp",
 *     "github.repo": "backend",
 *     "jira.baseUrl": "https://acme.atlassian.net",
 *     "claude.model": "claude-sonnet-4-5"
 *   },
 *   "secrets": {
 *     "github.token": "arn:aws:secretsmanager:...:github-token",
 *     "jira.credentials": "arn:aws:secretsmanager:...:jira-creds",
 *     "claude.apiKey": "arn:aws:secretsmanager:...:claude-key"
 *   }
 * }
 * }</pre>
 */
public record TenantConfig(
        String tenantId,
        Map<String, String> moduleBindings,
        Map<String, String> config,
        Map<String, String> secrets,
        boolean enabled) {

    public TenantConfig {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        moduleBindings = moduleBindings != null ? Map.copyOf(moduleBindings) : Map.of();
        config = config != null ? Map.copyOf(config) : Map.of();
        secrets = secrets != null ? Map.copyOf(secrets) : Map.of();
    }

    /**
     * Get the module ID bound to a specific type.
     * E.g., getModuleBinding("source-control") → "github"
     */
    public Optional<String> getModuleBinding(String moduleType) {
        return Optional.ofNullable(moduleBindings.get(moduleType));
    }

    /**
     * Builder for fluent construction.
     */
    public static Builder builder(String tenantId) {
        return new Builder(tenantId);
    }

    public static class Builder {
        private final String tenantId;
        private final Map<String, String> moduleBindings = new LinkedHashMap<>();
        private final Map<String, String> config = new LinkedHashMap<>();
        private final Map<String, String> secrets = new LinkedHashMap<>();
        private boolean enabled = true;

        private Builder(String tenantId) {
            this.tenantId = tenantId;
        }

        public Builder bindModule(String moduleType, String moduleId) {
            moduleBindings.put(moduleType, moduleId);
            return this;
        }

        public Builder config(String key, String value) {
            config.put(key, value);
            return this;
        }

        public Builder configs(Map<String, String> configs) {
            config.putAll(configs);
            return this;
        }

        public Builder secret(String key, String secretArn) {
            secrets.put(key, secretArn);
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public TenantConfig build() {
            return new TenantConfig(tenantId, moduleBindings, config, secrets, enabled);
        }
    }
}
