package com.aidriven.spi.tenant;

import com.aidriven.spi.ModuleContext;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Default implementation of {@link ModuleContext} backed by a {@link TenantConfig}.
 *
 * <p>Secrets are resolved lazily via a provided resolver function,
 * allowing different secret backends (AWS SM, Vault, env vars, etc.).</p>
 */
public class DefaultModuleContext implements ModuleContext {

    private final TenantConfig tenantConfig;
    private final Function<String, String> secretResolver;
    private final Function<String, Map<String, Object>> secretJsonResolver;

    public DefaultModuleContext(TenantConfig tenantConfig,
                                Function<String, String> secretResolver,
                                Function<String, Map<String, Object>> secretJsonResolver) {
        this.tenantConfig = Objects.requireNonNull(tenantConfig);
        this.secretResolver = Objects.requireNonNull(secretResolver);
        this.secretJsonResolver = secretJsonResolver != null ? secretJsonResolver : key -> Map.of();
    }

    /**
     * Simplified constructor for testing (no secret resolution).
     */
    public DefaultModuleContext(TenantConfig tenantConfig) {
        this(tenantConfig, key -> null, key -> Map.of());
    }

    @Override
    public String tenantId() {
        return tenantConfig.tenantId();
    }

    @Override
    public String getRequiredConfig(String key) {
        return getConfig(key).orElseThrow(() ->
                new IllegalArgumentException("Missing required config: " + key + " for tenant " + tenantId()));
    }

    @Override
    public Optional<String> getConfig(String key) {
        return Optional.ofNullable(tenantConfig.config().get(key));
    }

    @Override
    public Map<String, String> getAllConfig() {
        return tenantConfig.config();
    }

    @Override
    public String resolveSecret(String secretKey) {
        // First check if there's a secret ARN mapping
        String secretArn = tenantConfig.secrets().get(secretKey);
        if (secretArn != null) {
            return secretResolver.apply(secretArn);
        }
        // Fall back to direct resolution
        return secretResolver.apply(secretKey);
    }

    @Override
    public Map<String, Object> resolveSecretJson(String secretKey) {
        String secretArn = tenantConfig.secrets().get(secretKey);
        if (secretArn != null) {
            return secretJsonResolver.apply(secretArn);
        }
        return secretJsonResolver.apply(secretKey);
    }
}
