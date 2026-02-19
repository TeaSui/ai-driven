package com.aidriven.spi;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Simple implementation of {@link ModuleContext} for testing and standalone usage.
 * Backed by in-memory maps for configuration and secrets.
 */
public class SimpleModuleContext implements ModuleContext {

    private final String tenantId;
    private final Map<String, String> config;
    private final Map<String, String> secrets;

    private SimpleModuleContext(String tenantId, Map<String, String> config, Map<String, String> secrets) {
        this.tenantId = tenantId;
        this.config = Collections.unmodifiableMap(new HashMap<>(config));
        this.secrets = Collections.unmodifiableMap(new HashMap<>(secrets));
    }

    @Override
    public Optional<String> getConfig(String key) {
        return Optional.ofNullable(config.get(key));
    }

    @Override
    public String getSecret(String key) {
        String value = secrets.get(key);
        if (value == null) {
            throw new IllegalStateException("Secret not available: " + key);
        }
        return value;
    }

    @Override
    public Map<String, String> getSecrets() {
        return secrets;
    }

    @Override
    public String tenantId() {
        return tenantId;
    }

    @Override
    public Map<String, String> getAllConfig() {
        return config;
    }

    public static Builder builder(String tenantId) {
        return new Builder(tenantId);
    }

    public static class Builder {
        private final String tenantId;
        private final Map<String, String> config = new HashMap<>();
        private final Map<String, String> secrets = new HashMap<>();

        private Builder(String tenantId) {
            this.tenantId = tenantId;
        }

        public Builder config(String key, String value) {
            config.put(key, value);
            return this;
        }

        public Builder configs(Map<String, String> configs) {
            config.putAll(configs);
            return this;
        }

        public Builder secret(String key, String value) {
            secrets.put(key, value);
            return this;
        }

        public Builder secrets(Map<String, String> secrets) {
            this.secrets.putAll(secrets);
            return this;
        }

        public SimpleModuleContext build() {
            return new SimpleModuleContext(tenantId, config, secrets);
        }
    }
}
