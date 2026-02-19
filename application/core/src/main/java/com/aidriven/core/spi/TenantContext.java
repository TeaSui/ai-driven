package com.aidriven.core.spi;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds tenant-specific configuration for multi-tenant deployments.
 *
 * <p>In a SaaS model, each company (tenant) may use different:
 * <ul>
 *   <li>Source control platforms (Bitbucket vs GitHub vs GitLab)</li>
 *   <li>Issue trackers (Jira vs Linear vs Shortcut)</li>
 *   <li>AI models (Claude Opus vs Sonnet vs Haiku)</li>
 *   <li>Credentials and API endpoints</li>
 * </ul>
 *
 * <p>TenantContext is stored per-request (via ThreadLocal) and used by
 * the ServiceProviderRegistry to select the correct provider.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // At request entry point (webhook handler)
 * TenantContext.set(TenantContext.of("acme-corp", Map.of(
 *     "source_control", "github",
 *     "issue_tracker", "jira"
 * )));
 *
 * // In business logic
 * String scProvider = TenantContext.current().getPreference("source_control");
 * SourceControlClient client = registry.get(SourceControlClient.class, scProvider);
 *
 * // Cleanup
 * TenantContext.clear();
 * }</pre>
 */
public class TenantContext {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private final String tenantId;
    private final Map<String, String> preferences;
    private final Map<String, String> secrets;

    private TenantContext(String tenantId, Map<String, String> preferences, Map<String, String> secrets) {
        this.tenantId = tenantId;
        this.preferences = new ConcurrentHashMap<>(preferences);
        this.secrets = new ConcurrentHashMap<>(secrets);
    }

    /**
     * Creates a tenant context with preferences.
     */
    public static TenantContext of(String tenantId, Map<String, String> preferences) {
        return new TenantContext(tenantId, preferences, Map.of());
    }

    /**
     * Creates a tenant context with preferences and secrets.
     */
    public static TenantContext of(String tenantId, Map<String, String> preferences, Map<String, String> secrets) {
        return new TenantContext(tenantId, preferences, secrets);
    }

    /**
     * Sets the current tenant context for this thread.
     */
    public static void set(TenantContext context) {
        CURRENT.set(context);
    }

    /**
     * Gets the current tenant context.
     *
     * @return Current context, or a default empty context if none set
     */
    public static TenantContext current() {
        TenantContext ctx = CURRENT.get();
        return ctx != null ? ctx : of("default", Map.of());
    }

    /**
     * Clears the current tenant context. Must be called in finally blocks.
     */
    public static void clear() {
        CURRENT.remove();
    }

    public String getTenantId() {
        return tenantId;
    }

    /**
     * Gets a tenant preference (e.g., preferred source control platform).
     */
    public Optional<String> getPreference(String key) {
        return Optional.ofNullable(preferences.get(key));
    }

    /**
     * Gets a tenant preference with a default value.
     */
    public String getPreference(String key, String defaultValue) {
        return preferences.getOrDefault(key, defaultValue);
    }

    /**
     * Gets a tenant-specific secret.
     */
    public Optional<String> getSecret(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    /**
     * Returns all preferences as an unmodifiable map.
     */
    public Map<String, String> getPreferences() {
        return Map.copyOf(preferences);
    }

    @Override
    public String toString() {
        return "TenantContext{tenantId='" + tenantId + "', preferences=" + preferences.keySet() + "}";
    }
}
