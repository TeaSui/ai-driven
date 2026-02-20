package com.aidriven.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable context representing a tenant's configuration.
 * Passed to service modules during initialization so they can
 * configure themselves for the specific tenant.
 *
 * <p>Thread-safe and suitable for use in Lambda execution contexts
 * where multiple tenants may be served by the same process.</p>
 */
public final class TenantContext {

    private final String tenantId;
    private final String tenantName;
    private final Map<String, String> config;

    private TenantContext(String tenantId, String tenantName, Map<String, String> config) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.tenantName = Objects.requireNonNull(tenantName, "tenantName must not be null");
        this.config = Map.copyOf(Objects.requireNonNull(config, "config must not be null"));
    }

    public String tenantId() {
        return tenantId;
    }

    public String tenantName() {
        return tenantName;
    }

    /**
     * Returns the full configuration map for this tenant.
     */
    public Map<String, String> config() {
        return config;
    }

    /**
     * Gets a required configuration value.
     *
     * @throws IllegalStateException if the key is not present
     */
    public String requireConfig(String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    String.format("Missing required config '%s' for tenant '%s'", key, tenantId));
        }
        return value;
    }

    /**
     * Gets an optional configuration value.
     */
    public Optional<String> getConfig(String key) {
        return Optional.ofNullable(config.get(key)).filter(v -> !v.isBlank());
    }

    /**
     * Gets a configuration value with a default fallback.
     */
    public String getConfig(String key, String defaultValue) {
        return getConfig(key).orElse(defaultValue);
    }

    /**
     * Gets an integer configuration value with a default fallback.
     */
    public int getIntConfig(String key, int defaultValue) {
        return getConfig(key).map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Gets a boolean configuration value with a default fallback.
     */
    public boolean getBoolConfig(String key, boolean defaultValue) {
        return getConfig(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "TenantContext{tenantId='" + tenantId + "', tenantName='" + tenantName
                + "', configKeys=" + config.keySet() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantContext that)) return false;
        return tenantId.equals(that.tenantId);
    }

    @Override
    public int hashCode() {
        return tenantId.hashCode();
    }

    public static final class Builder {
        private String tenantId;
        private String tenantName;
        private final java.util.HashMap<String, String> config = new java.util.HashMap<>();

        private Builder() {}

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder tenantName(String tenantName) {
            this.tenantName = tenantName;
            return this;
        }

        public Builder config(String key, String value) {
            this.config.put(key, value);
            return this;
        }

        public Builder config(Map<String, String> config) {
            this.config.putAll(config);
            return this;
        }

        public TenantContext build() {
            return new TenantContext(tenantId, tenantName, config);
        }
    }
}
